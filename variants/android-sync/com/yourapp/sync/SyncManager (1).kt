package com.yourapp.sync

// build.gradle.kts:
//   implementation("io.socket:socket.io-client:2.1.0") { exclude(group = "org.json", module = "json") }
//   implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
// AndroidManifest.xml:
//   <uses-permission android:name="android.permission.INTERNET" />
//   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
// minSdk 24

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import io.socket.engineio.client.transports.WebSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Offline-first sync engine.
 *
 * Server contract (Socket.IO):
 *   event:   config.syncEvent  (default "sync_data")
 *   payload: { batchId, sentAt, items: [ { id, createdAt, attempts, data } ] }
 *   ack:     true | "ok" | { success: true } | { status: "ok" }   -> batch accepted
 *            anything else                                         -> batch rejected
 *
 * Items are deleted locally only after a successful ack, so delivery is at-least-once.
 * The server should de-duplicate on items[].id.
 *
 * Batches the server explicitly rejects [Config.maxItemRejections] times are moved to a
 * dead-letter table so a single bad payload cannot block the queue forever.
 */
class SyncManager private constructor(
    appContext: Context,
    private val config: Config
) {

    data class Config(
        val serverUrl: String,
        val syncEvent: String = "sync_data",
        val authToken: String? = null,
        val batchSize: Int = 50,
        val connectTimeoutMs: Long = 10_000L,
        val ackTimeoutMs: Long = 15_000L,
        val initialBackoffMs: Long = 1_000L,
        val maxBackoffMs: Long = 5 * 60_000L,
        val backoffMultiplier: Double = 2.0,
        val maxRetries: Int = 10,
        /** 0 = never dead-letter. */
        val maxItemRejections: Int = 5
    ) {
        init {
            require(serverUrl.isNotBlank()) { "serverUrl must not be blank" }
            require(batchSize in 1..500) { "batchSize must be between 1 and 500" }
            require(connectTimeoutMs > 0 && ackTimeoutMs > 0) { "Timeouts must be > 0" }
            require(initialBackoffMs > 0 && maxBackoffMs >= initialBackoffMs) { "Invalid backoff bounds" }
            require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
            require(maxRetries >= 0) { "maxRetries must be >= 0" }
            require(maxItemRejections >= 0) { "maxItemRejections must be >= 0" }
        }
    }

    sealed class SyncState {
        object Idle : SyncState()
        object Paused : SyncState()
        object Offline : SyncState()
        data class Syncing(val pending: Long) : SyncState()
        data class WaitingForRetry(val attempt: Int, val delayMs: Long, val reason: String?) : SyncState()
        data class Failed(val reason: String?) : SyncState()
    }

    open class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** The server answered, but refused the batch. Counts toward dead-lettering. */
    class ServerRejectedException(message: String) : SyncException(message)

    private data class QueuedItem(
        val id: Long,
        val uuid: String,
        val payload: String,
        val createdAt: Long,
        val attempts: Int
    )

    private val db = SyncQueueDb(appContext)

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Single thread => inserts keep FIFO order and SQLite access is serialized.
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sync-db").apply { isDaemon = true }
    }
    private val dbDispatcher = dbExecutor.asCoroutineDispatcher()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Unhandled sync error", t)
        }
    )

    /** Coalesces sync requests: any number of calls while a run is active => one follow-up run. */
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    /** Wakes a run that is sleeping in exponential backoff (e.g. network just came back). */
    private val retryWake = Channel<Unit>(Channel.CONFLATED)

    private val paused = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val socketLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var currentRun: Job? = null

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val isPaused: Boolean get() = paused.get()

    init {
        scope.launch {
            for (ignored in trigger) {
                if (paused.get()) continue

                val run = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        runSyncWithRetry()
                    } finally {
                        // A paused/cancelled run must never leave a socket open behind it.
                        if (paused.get() || !currentCoroutineContext().isActive) disconnectSocket()
                    }
                }
                // Publish before starting so pauseUploads() can always see and cancel it.
                currentRun = run
                if (paused.get()) run.cancel() else run.start()
                run.join()
                currentRun = null
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /**
     * Validates [json] (object or array) and persists it to the local queue, then requests a sync.
     * @throws IllegalArgumentException if [json] is not valid JSON.
     * @throws IllegalStateException if called after [shutdown].
     */
    fun queueData(json: String) {
        check(!closed.get()) { "SyncManager has been shut down" }
        val normalized = validateJson(json)
        scope.launch(dbDispatcher) {
            try {
                db.insert(UUID.randomUUID().toString(), normalized, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue data", e)
                return@launch
            }
            // New data must not cut an active backoff short, or a dead server gets hammered.
            requestSync(skipBackoff = false)
        }
    }

    /**
     * Sends all queued data to the server. Safe to call repeatedly; requests are coalesced.
     * If a run is currently waiting in backoff, it retries immediately.
     */
    fun syncData() = requestSync(skipBackoff = true)

    /** Cancels the in-flight upload and blocks new ones until [resumeUploads]. Queued data is kept. */
    fun pauseUploads() {
        paused.set(true)
        currentRun?.cancel()
        disconnectSocket()
        _state.value = SyncState.Paused
    }

    /** Allows uploads again. Call [syncData] afterwards to start draining the queue. */
    fun resumeUploads() {
        if (paused.getAndSet(false) && _state.value is SyncState.Paused) {
            _state.value = SyncState.Idle
        }
    }

    suspend fun pendingCount(): Long = withContext(dbDispatcher) { db.count() }

    suspend fun deadLetterCount(): Long = withContext(dbDispatcher) { db.deadLetterCount() }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        trigger.close()
        retryWake.close()
        scope.cancel()
        disconnectSocket()
        dbExecutor.execute { db.close() }
        dbDispatcher.close()
        synchronized(Companion) { if (INSTANCE === this) INSTANCE = null }
    }

    // ---------------------------------------------------------------------------------------------
    // Sync + retry
    // ---------------------------------------------------------------------------------------------

    private fun requestSync(skipBackoff: Boolean) {
        if (closed.get()) return
        if (paused.get()) {
            _state.value = SyncState.Paused
            return
        }
        if (skipBackoff) retryWake.trySend(Unit)
        trigger.trySend(Unit)
    }

    private suspend fun runSyncWithRetry() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (paused.get()) {
                _state.value = SyncState.Paused
                return
            }
            if (!isNetworkAvailable()) {
                _state.value = SyncState.Offline
                return
            }
            try {
                drainQueue(onBatchDelivered = { attempt = 0 })
                _state.value = if (paused.get()) SyncState.Paused else SyncState.Idle
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                Log.w(TAG, "Sync attempt $attempt failed: ${e.message}")
                disconnectSocket()

                if (attempt > config.maxRetries) {
                    _state.value = SyncState.Failed(e.message)
                    return
                }

                val wait = backoffDelay(attempt)
                _state.value = SyncState.WaitingForRetry(attempt, wait, e.message)
                awaitBackoff(wait)
            }
        }
    }

    /** Sleeps for [delayMs] unless [syncData] is called in the meantime. */
    private suspend fun awaitBackoff(delayMs: Long) {
        retryWake.tryReceive() // drop stale wake-ups from before this wait started
        withTimeoutOrNull(delayMs) { retryWake.receiveCatching() }
    }

    private suspend fun drainQueue(onBatchDelivered: () -> Unit) {
        val initialPending = withContext(dbDispatcher) { db.count() }
        if (initialPending == 0L) return

        _state.value = SyncState.Syncing(initialPending)
        val s = ensureConnected()

        while (true) {
            currentCoroutineContext().ensureActive()
            if (paused.get()) return

            val batch = withContext(dbDispatcher) { db.fetchBatch(config.batchSize) }
            if (batch.isEmpty()) return
            val ids = batch.map { it.id }

            try {
                sendBatch(s, batch)
            } catch (e: ServerRejectedException) {
                withContext(NonCancellable + dbDispatcher) {
                    db.markRejected(ids, e.message)
                    if (config.maxItemRejections > 0) {
                        val moved = db.moveToDeadLetter(ids, config.maxItemRejections)
                        if (moved > 0) Log.e(TAG, "Moved $moved item(s) to dead-letter after repeated rejection")
                    }
                }
                throw e
            }

            // Server has the data: delete it even if we get paused/cancelled right now.
            withContext(NonCancellable + dbDispatcher) { db.delete(ids) }
            onBatchDelivered()

            val remaining = withContext(dbDispatcher) { db.count() }
            _state.value = SyncState.Syncing(remaining)
        }
    }

    private fun backoffDelay(attempt: Int): Long {
        val exponential = config.initialBackoffMs * config.backoffMultiplier.pow((attempt - 1).toDouble())
        val capped = min(exponential, config.maxBackoffMs.toDouble()).toLong().coerceAtLeast(1L)
        val half = capped / 2
        return half + Random.nextLong(0, capped - half + 1) // "equal jitter": [capped/2, capped]
    }

    // ---------------------------------------------------------------------------------------------
    // Socket.IO
    // ---------------------------------------------------------------------------------------------

    private fun obtainSocket(): Socket = synchronized(socketLock) {
        socket ?: IO.socket(URI.create(config.serverUrl), buildSocketOptions()).also { s ->
            s.on(Socket.EVENT_DISCONNECT) { args ->
                Log.d(TAG, "Socket disconnected: ${args.firstOrNull()}")
            }
            socket = s
        }
    }

    private fun buildSocketOptions(): IO.Options = IO.Options().apply {
        forceNew = true
        reconnection = false // retries are handled by our own backoff
        timeout = config.connectTimeoutMs
        transports = arrayOf(WebSocket.NAME)
        config.authToken?.let { auth = mapOf("token" to it) }
    }

    private suspend fun ensureConnected(): Socket {
        val s = obtainSocket()
        if (s.connected()) return s

        val result = withTimeoutOrNull(config.connectTimeoutMs) {
            suspendCancellableCoroutine<Result<Unit>> { cont ->
                lateinit var onConnect: Emitter.Listener
                lateinit var onError: Emitter.Listener

                fun cleanup() {
                    s.off(Socket.EVENT_CONNECT, onConnect)
                    s.off(Socket.EVENT_CONNECT_ERROR, onError)
                }

                onConnect = Emitter.Listener {
                    cleanup()
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
                onError = Emitter.Listener { args ->
                    cleanup()
                    if (cont.isActive) {
                        cont.resume(Result.failure(SyncException("Socket connect error: ${args.firstOrNull()}")))
                    }
                }

                s.on(Socket.EVENT_CONNECT, onConnect)
                s.on(Socket.EVENT_CONNECT_ERROR, onError)
                cont.invokeOnCancellation { cleanup() }

                if (s.connected()) {
                    cleanup()
                    cont.resume(Result.success(Unit))
                } else {
                    s.connect()
                }
            }
        } ?: throw SyncException("Socket connect timed out after ${config.connectTimeoutMs} ms")

        result.getOrThrow()
        return s
    }

    private suspend fun sendBatch(s: Socket, batch: List<QueuedItem>) {
        if (!s.connected()) throw SyncException("Socket not connected")

        val payload = JSONObject()
            .put("batchId", UUID.randomUUID().toString())
            .put("sentAt", System.currentTimeMillis())
            .put("items", JSONArray().apply {
                batch.forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.uuid)
                            .put("createdAt", item.createdAt)
                            .put("attempts", item.attempts)
                            .put("data", JSONTokener(item.payload).nextValue())
                    )
                }
            })

        val result = withTimeoutOrNull(config.ackTimeoutMs) {
            suspendCancellableCoroutine<Result<Array<out Any?>>> { cont ->
                // Fail fast if the connection drops instead of waiting for the ack timeout.
                val onDisconnect = Emitter.Listener { args ->
                    if (cont.isActive) {
                        cont.resume(Result.failure(SyncException("Disconnected while awaiting ack: ${args.firstOrNull()}")))
                    }
                }
                s.once(Socket.EVENT_DISCONNECT, onDisconnect)
                cont.invokeOnCancellation { s.off(Socket.EVENT_DISCONNECT, onDisconnect) }

                s.emit(config.syncEvent, arrayOf<Any>(payload), Ack { args ->
                    s.off(Socket.EVENT_DISCONNECT, onDisconnect)
                    if (cont.isActive) cont.resume(Result.success(args))
                })
            }
        } ?: throw SyncException("No ack from server within ${config.ackTimeoutMs} ms")

        val ack = result.getOrThrow()
        if (!isAckSuccessful(ack)) {
            throw ServerRejectedException("Server rejected batch: ${ack.joinToString()}")
        }
    }

    private fun isAckSuccessful(args: Array<out Any?>): Boolean =
        when (val first = args.firstOrNull()) {
            is Boolean -> first
            is String -> first.equals("ok", ignoreCase = true)
            is JSONObject -> first.optBoolean("success", false) ||
                first.optString("status").equals("ok", ignoreCase = true)
            else -> false
        }

    private fun disconnectSocket() {
        val s = synchronized(socketLock) { socket.also { socket = null } } ?: return
        s.off()
        s.disconnect()
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun validateJson(json: String): String {
        val trimmed = json.trim()
        require(trimmed.isNotEmpty()) { "JSON payload must not be empty" }
        val value = try {
            val tokener = JSONTokener(trimmed)
            val parsed = tokener.nextValue()
            // nextValue() silently ignores trailing input such as "{} garbage".
            if (tokener.nextClean() != '\u0000') throw JSONException("Trailing characters after JSON value")
            parsed
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid JSON payload: ${e.message}", e)
        }
        require(value is JSONObject || value is JSONArray) { "Payload must be a JSON object or array" }
        return value.toString()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------------------------------------------
    // SQLite queue
    // ---------------------------------------------------------------------------------------------

    private class SyncQueueDb(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            createQueueTable(db, TABLE)
            createQueueTable(db, DEAD_LETTER_TABLE)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Add ALTER TABLE migrations here. Never drop $TABLE: it holds unsent user data.
        }

        private fun createQueueTable(db: SQLiteDatabase, name: String) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $name (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_UUID TEXT NOT NULL UNIQUE,
                    $COL_PAYLOAD TEXT NOT NULL,
                    $COL_CREATED_AT INTEGER NOT NULL,
                    $COL_ATTEMPTS INTEGER NOT NULL DEFAULT 0,
                    $COL_LAST_ERROR TEXT
                )
                """.trimIndent()
            )
        }

        fun insert(uuid: String, payload: String, createdAt: Long): Long {
            val values = ContentValues().apply {
                put(COL_UUID, uuid)
                put(COL_PAYLOAD, payload)
                put(COL_CREATED_AT, createdAt)
            }
            return writableDatabase.insertOrThrow(TABLE, null, values)
        }

        fun fetchBatch(limit: Int): List<QueuedItem> {
            readableDatabase.query(
                TABLE,
                arrayOf(COL_ID, COL_UUID, COL_PAYLOAD, COL_CREATED_AT, COL_ATTEMPTS),
                null, null, null, null,
                "$COL_ID ASC",
                limit.toString()
            ).use { c ->
                val iId = c.getColumnIndexOrThrow(COL_ID)
                val iUuid = c.getColumnIndexOrThrow(COL_UUID)
                val iPayload = c.getColumnIndexOrThrow(COL_PAYLOAD)
                val iCreated = c.getColumnIndexOrThrow(COL_CREATED_AT)
                val iAttempts = c.getColumnIndexOrThrow(COL_ATTEMPTS)
                val items = ArrayList<QueuedItem>(c.count)
                while (c.moveToNext()) {
                    items += QueuedItem(
                        id = c.getLong(iId),
                        uuid = c.getString(iUuid),
                        payload = c.getString(iPayload),
                        createdAt = c.getLong(iCreated),
                        attempts = c.getInt(iAttempts)
                    )
                }
                return items
            }
        }

        fun delete(ids: List<Long>) {
            if (ids.isEmpty()) return
            writableDatabase.delete(
                TABLE,
                "$COL_ID IN (${placeholders(ids.size)})",
                ids.map { it.toString() }.toTypedArray()
            )
        }

        fun markRejected(ids: List<Long>, error: String?) {
            if (ids.isEmpty()) return
            val args: Array<Any?> = arrayOf(error?.take(500), *ids.toTypedArray())
            writableDatabase.execSQL(
                "UPDATE $TABLE SET $COL_ATTEMPTS = $COL_ATTEMPTS + 1, $COL_LAST_ERROR = ? " +
                    "WHERE $COL_ID IN (${placeholders(ids.size)})",
                args
            )
        }

        /** Moves items among [ids] rejected at least [maxRejections] times. Returns count moved. */
        fun moveToDeadLetter(ids: List<Long>, maxRejections: Int): Int {
            if (ids.isEmpty()) return 0
            val where = "$COL_ID IN (${placeholders(ids.size)}) AND $COL_ATTEMPTS >= ?"
            val whereArgs = ids.map { it.toString() } + maxRejections.toString()
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO $DEAD_LETTER_TABLE " +
                        "($COL_UUID, $COL_PAYLOAD, $COL_CREATED_AT, $COL_ATTEMPTS, $COL_LAST_ERROR) " +
                        "SELECT $COL_UUID, $COL_PAYLOAD, $COL_CREATED_AT, $COL_ATTEMPTS, $COL_LAST_ERROR " +
                        "FROM $TABLE WHERE $where",
                    whereArgs.toTypedArray<Any?>()
                )
                val moved = db.delete(TABLE, where, whereArgs.toTypedArray())
                db.setTransactionSuccessful()
                return moved
            } finally {
                db.endTransaction()
            }
        }

        fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, TABLE)

        fun deadLetterCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, DEAD_LETTER_TABLE)

        private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")
    }

    companion object {
        private const val TAG = "SyncManager"

        private const val DB_NAME = "sync_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE = "sync_queue"
        private const val DEAD_LETTER_TABLE = "sync_dead_letter"
        private const val COL_ID = "_id"
        private const val COL_UUID = "uuid"
        private const val COL_PAYLOAD = "payload"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_ATTEMPTS = "attempts"
        private const val COL_LAST_ERROR = "last_error"

        @Volatile private var INSTANCE: SyncManager? = null

        fun init(context: Context, config: Config): SyncManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext, config).also { INSTANCE = it }
            }

        fun get(): SyncManager =
            INSTANCE ?: error("SyncManager.init(context, config) must be called first")
    }
}
