// SettingsBackupTool.kt
package com.example.utility.backup

/*
 * Requires minSdk 26 and kotlinx-coroutines-android.
 *
 * Android prevents ordinary apps from reading another app's private
 * SharedPreferences. Requests for other installed packages return
 * PROTECTED_APP_DATA; those apps must provide their own authorized exporter.
 *
 * This tool reads the calling app's preferences without modifying them.
 * Backup contents can contain sensitive values; store backups securely.
 */

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SettingsBackupTool(context: Context) {

    // Preserve a caller-supplied device-protected storage context.
    private val storageContext = context
    private val ownPackage = context.packageName

    /**
     * preferenceNames are the names passed to Context.getSharedPreferences().
     * Specify names explicitly rather than probing another app's directories.
     */
    suspend fun backupAppSettings(
        packageName: String,
        preferenceNames: Collection<String>
    ): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject().apply {
            put("package_name", packageName)
            put("storage", storageType())
            put("settings", JSONObject())
            put("errors", JSONArray())
        }

        val settings = result.getJSONObject("settings")
        val errors = result.getJSONArray("errors")

        try {
            currentCoroutineContext().ensureActive()

            if (!validPackageName(packageName)) {
                addError(errors, null, "INVALID_PACKAGE_NAME")
                return@withContext finish(result)
            }

            if (packageName != ownPackage) {
                // Package visibility restrictions may hide installed packages.
                @Suppress("DEPRECATION")
                storageContext.packageManager.getApplicationInfo(packageName, 0)

                addError(
                    errors,
                    null,
                    "PROTECTED_APP_DATA",
                    "This app must provide an authorized settings exporter."
                )
                return@withContext finish(result)
            }

            val userManager = storageContext.getSystemService(UserManager::class.java)
            if (!storageContext.isDeviceProtectedStorage &&
                userManager?.isUserUnlocked != true
            ) {
                addError(errors, null, "USER_STORAGE_LOCKED")
                return@withContext finish(result)
            }

            for (name in preferenceNames.distinct().sorted()) {
                currentCoroutineContext().ensureActive()

                if (!validPreferenceName(name)) {
                    addError(errors, name, "INVALID_PREFERENCE_NAME")
                    continue
                }

                try {
                    val preferenceFile = resolveOwnPreferenceFile(name)

                    if (!preferenceFile.isFile) {
                        addError(errors, name, "PREFERENCES_NOT_FOUND")
                        continue
                    }

                    if (!preferenceFile.canRead()) {
                        addError(errors, name, "PREFERENCES_ACCESS_DENIED")
                        continue
                    }

                    val preferences = storageContext.getSharedPreferences(
                        name,
                        Context.MODE_PRIVATE
                    )

                    // Copy the returned map; never modify framework-owned values.
                    val snapshot = preferences.all.toMap()
                    val encoded = JSONObject()

                    for ((key, value) in snapshot.toSortedMap()) {
                        currentCoroutineContext().ensureActive()

                        try {
                            encoded.put(key, formatSetting(value))
                        } catch (_: IllegalArgumentException) {
                            addError(
                                errors,
                                name,
                                "UNSUPPORTED_SETTING_TYPE",
                                key = key
                            )
                        }
                    }

                    settings.put(name, encoded)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    addError(errors, name, "PREFERENCES_ACCESS_DENIED")
                } catch (_: IOException) {
                    addError(errors, name, "PREFERENCES_IO_ERROR")
                } catch (_: RuntimeException) {
                    // Do not expose exception messages that may contain values.
                    addError(errors, name, "PREFERENCES_READ_FAILED")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: PackageManager.NameNotFoundException) {
            addError(
                errors,
                null,
                "PACKAGE_NOT_INSTALLED_OR_NOT_VISIBLE"
            )
        } catch (_: SecurityException) {
            addError(errors, null, "APP_DATA_ACCESS_DENIED")
        } catch (_: RuntimeException) {
            addError(errors, null, "BACKUP_FAILED")
        }

        finish(result)
    }

    suspend fun readSharedPreferences(
        packageName: String,
        preferenceName: String
    ): JSONObject = backupAppSettings(
        packageName,
        listOf(preferenceName)
    )

    suspend fun backupOwnSettings(
        preferenceNames: Collection<String>
    ): JSONObject = backupAppSettings(
        ownPackage,
        preferenceNames
    )

    /**
     * Audits explicitly requested packages. Protected packages produce errors.
     * Does not require QUERY_ALL_PACKAGES or storage permissions.
     */
    suspend fun backupInstalledApps(
        requests: Map<String, Collection<String>>
    ): JSONObject {
        val apps = JSONArray()

        for ((packageName, preferenceNames) in requests.toSortedMap()) {
            currentCoroutineContext().ensureActive()
            apps.put(backupAppSettings(packageName, preferenceNames))
        }

        return JSONObject().apply {
            put("schema_version", 1)
            put("created_at_ms", System.currentTimeMillis())
            put("apps", apps)
        }
    }

    /**
     * Type metadata preserves SharedPreferences types during restoration.
     * Long and Float values use strings to preserve their exact representation.
     */
    fun formatSetting(value: Any?): JSONObject = when (value) {
        null -> typedValue("null", JSONObject.NULL)
        is String -> typedValue("string", value)
        is Boolean -> typedValue("boolean", value)
        is Int -> typedValue("int", value)
        is Long -> typedValue("long", value.toString())
        is Float -> typedValue("float", value.toString())
        is Set<*> -> {
            require(value.all { it is String }) {
                "Only string sets are supported."
            }

            val strings = value.map { it as String }.sorted()
            typedValue("string_set", JSONArray(strings))
        }
        else -> throw IllegalArgumentException("Unsupported preference type.")
    }

    private fun resolveOwnPreferenceFile(name: String): File {
        require(validPreferenceName(name))

        val appRoot = File(storageContext.applicationInfo.dataDir).canonicalFile
        val directory = File(appRoot, "shared_prefs")

        if (Files.isSymbolicLink(directory.toPath())) {
            throw SecurityException("Preference directory is a symbolic link.")
        }

        val canonicalDirectory = directory.canonicalFile
        if (!isInside(appRoot, canonicalDirectory)) {
            throw SecurityException("Preference directory escapes app storage.")
        }

        val file = File(canonicalDirectory, "$name.xml")
        if (Files.isSymbolicLink(file.toPath())) {
            throw SecurityException("Preference file is a symbolic link.")
        }

        val canonicalFile = file.canonicalFile
        if (canonicalFile.parentFile != canonicalDirectory) {
            throw SecurityException("Preference file escapes its directory.")
        }

        return canonicalFile
    }

    private fun finish(result: JSONObject): JSONObject {
        val errors = result.getJSONArray("errors")
        val settings = result.getJSONObject("settings")

        return result.apply {
            put(
                "status",
                when {
                    errors.length() == 0 -> "ok"
                    settings.length() > 0 -> "partial"
                    else -> "error"
                }
            )
            put("preference_files_backed_up", settings.length())
        }
    }

    private fun typedValue(type: String, value: Any): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("value", value)
        }

    private fun addError(
        errors: JSONArray,
        preferenceName: String?,
        code: String,
        message: String? = null,
        key: String? = null
    ) {
        errors.put(JSONObject().apply {
            put("code", code)
            preferenceName?.let { put("preference_name", it) }
            message?.let { put("message", it) }
            key?.let { put("key", it) }
        })
    }

    private fun validPackageName(value: String): Boolean =
        value.length <= 255 &&
            Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
                .matches(value)

    private fun validPreferenceName(value: String): Boolean =
        value != "." &&
            value != ".." &&
            Regex("[A-Za-z0-9._-]{1,128}").matches(value)

    private fun isInside(root: File, file: File): Boolean =
        file.path == root.path ||
            file.path.startsWith(root.path + File.separator)

    private fun storageType(): String =
        if (storageContext.isDeviceProtectedStorage) {
            "device_protected"
        } else {
            "credential_protected"
        }
}
