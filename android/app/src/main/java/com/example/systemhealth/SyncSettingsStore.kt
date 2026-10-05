package com.example.systemhealth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import com.example.utility.sync.ServerAddressPolicy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Deliberately avoids generated data-class toString() exposing enrollment tokens.
internal class SyncSettings(
    val serverUrl: String,
    val deviceId: String,
    val deviceToken: String
)

// Call load/save from an IO dispatcher. Keys remain in Android Keystore.
internal object SyncSettingsStore {
    private const val PREFS = "system_health_enrollment"
    private const val KEY_ALIAS = "system_health_enrollment_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun hasConfiguration(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
            it.contains("ciphertext") && it.getBoolean("approved", true)
        }

    fun validate(serverUrl: String, deviceId: String, deviceToken: String): SyncSettings {
        ServerAddressPolicy.validate(serverUrl, allowDebugHttp = BuildConfig.DEBUG)
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(deviceId))
        require(deviceToken.isNotBlank() && deviceToken.length <= 256)
        require(deviceToken.none { it.isWhitespace() || it.isISOControl() })
        return SyncSettings(serverUrl.removeSuffix("/"), deviceId, deviceToken)
    }

    @Synchronized
    fun save(context: Context, settings: SyncSettings) {
        persist(context, settings, true)
    }

    @Synchronized
    fun savePending(context: Context, settings: SyncSettings) {
        persist(context, settings, false)
    }

    @Synchronized
    fun markApproved(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(preferences.contains("ciphertext")) { "Enrollment has not been prepared" }
        if (!preferences.edit().putBoolean("approved", true).commit()) throw IOException("Enrollment approval could not be saved")
    }

    private fun persist(context: Context, settings: SyncSettings, approved: Boolean) {
        val checked = validate(settings.serverUrl, settings.deviceId, settings.deviceToken)
        val plaintext = JSONObject()
            .put("server_url", checked.serverUrl)
            .put("device_id", checked.deviceId)
            .put("device_token", checked.deviceToken)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        val ciphertext = try {
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("approved", approved)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        if (!saved) throw IOException("Enrollment settings could not be saved")
    }

    @Synchronized
    fun load(context: Context): SyncSettings? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = preferences.getString("ciphertext", null) ?: return null
        val iv = preferences.getString("iv", null) ?: error("Enrollment IV is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE, key(create = false),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        cipher.updateAAD(context.packageName.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        return try {
            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            validate(
                json.getString("server_url"), json.getString("device_id"),
                json.getString("device_token")
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "Enrollment encryption key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
