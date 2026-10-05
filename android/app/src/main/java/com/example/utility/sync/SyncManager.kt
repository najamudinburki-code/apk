// SyncManager.kt
package com.example.utility.sync

/*
 * app/build.gradle.kts:
 *
 * implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 * implementation("io.socket:socket.io-client:2.1.1") {
 *     exclude(group = "org.json", module = "json")
 * }
 *
 * AndroidManifest.xml:
 *
 * <uses-permission android:name="android.permission.INTERNET" />
 * <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
 *
 * Create one SyncManager per device and backend.
 * Delivery is at least once: an acknowledgement lost after persistence can
 * cause a duplicate. Server-side deduplication is required for exactly-once storage.
 * This engine runs while its process is alive; use WorkManager for scheduled
 * background execution after process termination.
 */

import android.content.ContentValues
import android.content.Context
import com.example.systemhealth.ConnectionDiagnostics
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class SyncManager(
    context: Context,
    private val serverUrl: String,
    private val deviceId: String,
    allowLocalHttp: Boolean = false,
    private val deviceTokenProvider: () -> String
) {
    companion object {
        private const val TAG = "SyncManager"
        private const val MAX_PAYLOAD_BYTES = 48 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val ACK_TIMEOUT_MS = 15_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L

        // Keeps sequential uploads below the example backend's event rate limit.
        private const val UPLOAD_INTERVAL_MS = 50L
    }

    private val endpoint = ServerAddressPolicy.validate(serverUrl, allowLocalHttp)
    private val appContext = context.applicationContext

    private val closed = AtomicBoolean(false)
    private val lifecycleJob = SupervisorJob()
    private val scope = CoroutineScope(lifecycleJob + Dispatchers.IO)
    private val databaseMutex = Mutex()
    private val online = MutableStateFlow(false)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    private val database = QueueDatabase(
        context.applicationContext,
        databaseName(serverUrl, deviceId)
    )

    init {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(deviceId)) {
            "Invalid deviceId."
        }

        scope.launch {
            online.collectLatest { available ->
                if (!available) return@collectLatest

                // collectLatest cancels this entire block when connectivity is lost.
                while (currentCoroutineContext().isActive) {
                    try {
                        drainQueue()
                        wakeups.receive()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Keep the worker alive if local database access fails.
                        Log.w(TAG, "Sync worker failed; queued data is retained.", error)
                        delay(MAX_BACKOFF_MS)
                    }
                }
            }
        }
    }

    /**
     * Persists data before requesting an upload. Call from a coroutine.
     * Accepted input is a JSON object matching the backend's data:receive event.
     */
    suspend fun queueData(json: String) {
        check(!closed.get()) { "SyncManager is closed." }

        val tokenizer = JSONTokener(json)
        val payload = tokenizer.nextValue() as? JSONObject
            ?: throw IllegalArgumentException("Data must be a JSON object.")

        require(tokenizer.nextClean() == '\u0000') {
            "Unexpected content after the JSON object."
        }

        val normalized = payload.toString()
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Payload exceeds $MAX_PAYLOAD_BYTES bytes."
        }

        withContext(Dispatchers.IO) {
            databaseMutex.withLock {
                check(!closed.get()) { "SyncManager is closed." }

                database.writableDatabase.insertOrThrow(
                    "sync_queue",
                    null,
                    ContentValues().apply {
                        put("payload", normalized)
                        put("created_at", System.currentTimeMillis())
                    }
                )
            }
        }

        syncData()
    }

    /**
     * Requests an asynchronous upload of every queued record.
     * Offline requests remain pending until NetworkMonitor reports connectivity.
     */
    fun syncData() {
        if (!closed.get()) {
            wakeups.trySend(Unit)
        }
    }

    internal fun networkAvailable() {
        if (!closed.get()) {
            online.value = true
            syncData()
        }
    }

    /**
     * Cancels the active connection, acknowledgement wait, and retry delay.
     * Unacknowledged records remain in SQLite.
     */
    fun pauseUploads() {
        online.value = false
    }

    /**
     * Stop NetworkMonitor first, then call this from a coroutine.
     */
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return

        online.value = false
        lifecycleJob.cancelAndJoin()
        wakeups.close()

        withContext(Dispatchers.IO) {
            databaseMutex.withLock {
                database.close()
            }
        }
    }

    private suspend fun drainQueue() {
        var failures = 0

        while (currentCoroutineContext().isActive) {
            var record = oldestRecord() ?: return
            var session: Session? = null
            var failed = false

            try {
                session = createSession()
                awaitConnection(session)

                while (currentCoroutineContext().isActive) {
                    currentCoroutineContext().ensureActive()
                    sendAndAwaitAcknowledgement(session, record.payload)

                    // Remove data only after the server confirms persistence.
                    databaseMutex.withLock {
                        database.writableDatabase.delete(
                            "sync_queue",
                            "id = ?",
                            arrayOf(record.id.toString())
                        )
                    }

                    failures = 0
                    delay(UPLOAD_INTERVAL_MS)
                    record = oldestRecord() ?: return
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                failures = min(failures + 1, 16)
                Log.w(TAG, "Upload failed; queued data is retained.", error)
            } finally {
                // Each failed session is discarded, including buffered emissions
                // and pending Socket.IO acknowledgements.
                session?.socket?.disconnect()
                session?.socket?.off()
            }

            if (failed) {
                val exponential = min(
                    MAX_BACKOFF_MS,
                    INITIAL_BACKOFF_MS * (1L shl min(failures - 1, 6))
                )

                // Equal jitter avoids synchronized retries across devices.
                delay(Random.nextLong(exponential / 2, exponential + 1))
            }
        }
    }

    private fun createSession(): Session {
        val token = deviceTokenProvider()
        require(token.isNotBlank() && token.length <= 256) {
            "Invalid device token."
        }

        val options = IO.Options().apply {
            forceNew = true
            multiplex = false

            // The durable queue owns retries; avoid a second retry mechanism.
            reconnection = false
            timeout = CONNECT_TIMEOUT_MS
            auth = mapOf<String, String>(
                "role" to "device",
                "device_id" to deviceId,
                "token" to token
            )
        }

        val socket = IO.socket(endpoint, options)
        val session = Session(socket)

        socket.on(Socket.EVENT_CONNECT) {
            session.connected.complete(Unit)
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) {
            session.failure.complete(IOException("Socket.IO connection failed."))
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            session.failure.complete(IOException("Socket.IO disconnected."))
        }

        return session
    }

    private suspend fun awaitConnection(session: Session) {
        currentCoroutineContext().ensureActive()
        session.socket.connect()

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            select<Boolean> {
                session.failure.onAwait { throw it }
                session.connected.onAwait { true }
            }
        }

        if (connected != true) {
            throw IOException("Socket.IO connection timed out.")
        }
    }

    private suspend fun sendAndAwaitAcknowledgement(
        session: Session,
        json: String
    ) {
        currentCoroutineContext().ensureActive()

        if (!session.socket.connected()) {
            throw IOException("Socket.IO is not connected.")
        }

        val acknowledgement = CompletableDeferred<JSONObject>()

        session.socket.emit(
            "data:receive",
            JSONObject(json),
            Ack { arguments ->
                val response = arguments.firstOrNull() as? JSONObject

                if (response == null) {
                    acknowledgement.completeExceptionally(
                        IOException("Invalid server acknowledgement.")
                    )
                } else {
                    acknowledgement.complete(response)
                }
            }
        )

        val response = withTimeoutOrNull(ACK_TIMEOUT_MS) {
            select<JSONObject> {
                session.failure.onAwait { throw it }
                acknowledgement.onAwait { it }
            }
        } ?: throw IOException("Server acknowledgement timed out.")

        if (!response.optBoolean("ok", false)) {
            throw IOException("Server rejected the upload.")
        }
        ConnectionDiagnostics.recordUpload(appContext, serverUrl, deviceId)
    }

    private suspend fun oldestRecord(): QueueRecord? =
        databaseMutex.withLock {
            database.readableDatabase.query(
                "sync_queue",
                arrayOf("id", "payload"),
                null,
                null,
                null,
                null,
                "id ASC",
                "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    QueueRecord(
                        id = cursor.getLong(0),
                        payload = cursor.getString(1)
                    )
                }
            }
        }

    private data class QueueRecord(
        val id: Long,
        val payload: String
    )

    private class Session(val socket: Socket) {
        val connected = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<IOException>()
    }

    private class QueueDatabase(
        context: Context,
        name: String
    ) : SQLiteOpenHelper(context, name, null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE sync_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // Add non-destructive migrations when increasing the schema version.
            error("Missing queue migration: $oldVersion -> $newVersion")
        }
    }

    private fun databaseName(serverUrl: String, deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$serverUrl\n$deviceId".toByteArray(Charsets.UTF_8))

        val suffix = digest.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

        return "sync_queue_$suffix.db"
    }
}
