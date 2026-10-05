// SecurityAuditTool.kt
package com.example.utility.security

/*
 * Requires minSdk 26 and kotlinx-coroutines-android.
 *
 * Audits only the calling app's private data directory.
 * Does not request root, bypass the sandbox, or access other packages.
 * Findings are heuristic and require review.
 * Reports redact secrets by default; raw values require explicit opt-in.
 * No findings or credentials are transmitted.
 */

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque

class SecurityAuditTool(
    context: Context,
    private val includeRawSecrets: Boolean = false,
    private val limits: Limits = Limits()
) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName

    data class Limits(
        val maxEntries: Int = 5_000,
        val maxDepth: Int = 20,
        val maxFileBytes: Int = 512 * 1024,
        val maxTotalBytes: Long = 32L * 1024 * 1024,
        val maxTables: Int = 50,
        val maxRowsPerTable: Int = 200,
        val maxFindings: Int = 1_000
    ) {
        init {
            require(maxEntries > 0 && maxDepth > 0)
            require(maxFileBytes in 16..(4 * 1024 * 1024))
            require(maxTotalBytes > 0)
            require(maxTables > 0 && maxRowsPerTable > 0 && maxFindings > 0)
        }
    }

    data class ExtractedCredential(
        val issueType: String,
        val value: String,
        val offset: Int
    )

    private data class Rule(
        val issueType: String,
        val regex: Regex,
        val valueGroup: Int = 0
    )

    private class ScanState {
        val findings = JSONArray()
        val errors = JSONArray()
        val fingerprints = HashSet<String>()
        var entries = 0
        var files = 0
        var bytes = 0L
        var incomplete = false
    }

    private val sensitiveName = Regex(
        """(?i)^(?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|password|passwd|pwd|client[_-]?secret|secret[_-]?key|private[_-]?key)$"""
    )

    private val rules = listOf(
        Rule(
            "JWT_TOKEN",
            Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\b""")
        ),
        Rule(
            "BEARER_TOKEN",
            Regex("""(?i)\bBearer[ \t]+([A-Za-z0-9._~+/-]{8,}={0,2})"""),
            1
        ),
        Rule(
            "AWS_ACCESS_KEY_ID",
            Regex("""\b(?:AKIA|ASIA)[A-Z0-9]{16}\b""")
        ),
        Rule(
            "GITHUB_TOKEN",
            Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{20,255}|github_pat_[A-Za-z0-9_]{20,255})\b""")
        ),
        Rule(
            "PRIVATE_KEY",
            Regex(
                """-----BEGIN ((?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY)-----[\s\S]{1,16384}?-----END \1-----"""
            )
        )
    )

    private val assignment = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|password|passwd|pwd|client[_-]?secret|secret[_-]?key|private[_-]?key)\b["']?\s*[:=]\s*["']?([^\s"'<>;,}\]]{1,4096})"""
    )

    /**
     * Extracts candidate values from caller-supplied text in memory.
     * Does not read files, access other apps, log, or transmit values.
     */
    fun identifyAndExtract(text: String): List<ExtractedCredential> {
        require(text.length <= limits.maxFileBytes) {
            "Text exceeds the configured inspection limit."
        }

        val results = ArrayList<ExtractedCredential>()

        for (rule in rules) {
            for (match in rule.regex.findAll(text)) {
                if (results.size >= limits.maxFindings) return results
                val group = match.groups[rule.valueGroup] ?: continue
                results += ExtractedCredential(
                    rule.issueType,
                    group.value,
                    group.range.first
                )
            }
        }

        for (match in assignment.findAll(text)) {
            if (results.size >= limits.maxFindings) break
            val name = match.groupValues[1]
            val value = match.groupValues[2]

            if (isPlaceholder(value)) continue

            results += ExtractedCredential(
                issueTypeFor(name),
                value,
                match.groups[2]!!.range.first
            )
        }

        return results.distinctBy { Triple(it.issueType, it.value, it.offset) }
    }

    /**
     * Traverses the current app's private storage only.
     * Scans bounded text files, SharedPreferences XML, and SQLite TEXT columns.
     */
    suspend fun scanAppDirectories(): JSONObject = withContext(Dispatchers.IO) {
        val state = ScanState()
        val startedAt = System.currentTimeMillis()

        try {
            val root = File(appContext.applicationInfo.dataDir).canonicalFile
            val pending = ArrayDeque<Pair<File, Int>>()
            val visitedDirectories = HashSet<String>()
            pending.add(root to 0)

            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()

                if (
                    state.entries >= limits.maxEntries ||
                    state.bytes >= limits.maxTotalBytes ||
                    state.findings.length() >= limits.maxFindings
                ) {
                    state.incomplete = true
                    addError(state, ".", "SCAN_LIMIT_REACHED")
                    break
                }

                val (entry, depth) = pending.removeFirst()
                state.entries++

                try {
                    if (Files.isSymbolicLink(entry.toPath())) {
                        state.incomplete = true
                        addError(state, relativePath(root, entry), "SYMLINK_SKIPPED")
                        continue
                    }

                    val canonical = entry.canonicalFile

                    if (!withinBoundary(root, canonical)) {
                        state.incomplete = true
                        addError(state, ".", "APP_BOUNDARY_VIOLATION")
                        continue
                    }

                    if (canonical.isDirectory) {
                        if (!visitedDirectories.add(canonical.path)) continue

                        if (depth >= limits.maxDepth) {
                            state.incomplete = true
                            addError(
                                state,
                                relativePath(root, canonical),
                                "DEPTH_LIMIT_REACHED"
                            )
                            continue
                        }

                        // Files.list avoids allocating an unbounded listFiles array.
                        Files.list(canonical.toPath()).use { stream ->
                            val iterator = stream.iterator()
                            while (iterator.hasNext()) {
                                if (
                                    state.entries + pending.size >= limits.maxEntries
                                ) {
                                    state.incomplete = true
                                    addError(
                                        state,
                                        relativePath(root, canonical),
                                        "ENTRY_LIMIT_REACHED"
                                    )
                                    break
                                }
                                pending.add(iterator.next().toFile() to depth + 1)
                            }
                        }
                    } else if (canonical.isFile) {
                        inspectFile(root, canonical, state)
                    }
                } catch (_: SecurityException) {
                    state.incomplete = true
                    addError(state, relativePath(root, entry), "ACCESS_DENIED")
                } catch (_: IOException) {
                    state.incomplete = true
                    addError(state, relativePath(root, entry), "FILE_IO_ERROR")
                }
            }
        } catch (_: SecurityException) {
            state.incomplete = true
            addError(state, ".", "APP_DIRECTORY_ACCESS_DENIED")
        } catch (_: IOException) {
            state.incomplete = true
            addError(state, ".", "APP_DIRECTORY_UNAVAILABLE")
        }

        JSONObject().apply {
            put("package_name", packageName)
            put("scope", "calling_app_private_storage")
            put("started_at_ms", startedAt)
            put("finished_at_ms", System.currentTimeMillis())
            put("redacted", !includeRawSecrets)
            put("incomplete", state.incomplete)
            put("files_inspected", state.files)
            put("bytes_inspected", state.bytes)
            put("findings", state.findings)
            put("errors", state.errors)
        }
    }

    private suspend fun inspectFile(
        root: File,
        file: File,
        state: ScanState
    ) {
        val location = relativePath(root, file)

        // SQLite owns these binary sidecars; inspect the main database instead.
        if (file.name.endsWith("-wal") ||
            file.name.endsWith("-shm") ||
            file.name.endsWith("-journal")
        ) return

        val header = file.inputStream().use { it.readBounded(16) }
        state.bytes += header.size

        if (header.contentEquals("SQLite format 3\u0000".toByteArray())) {
            inspectDatabase(root, file, state)
            state.files++
            return
        }

        val remaining = limits.maxTotalBytes - state.bytes
        if (remaining <= 0) {
            state.incomplete = true
            return
        }

        val cap = minOf(limits.maxFileBytes.toLong(), remaining).toInt()
        val bytes = file.inputStream().use { it.readBounded(cap) }
        state.bytes += bytes.size
        state.files++

        if (file.length() > bytes.size) {
            state.incomplete = true
            addError(state, location, "FILE_CONTENT_TRUNCATED")
        }

        // Skip binary content instead of interpreting arbitrary bytes as secrets.
        if (bytes.any { it == 0.toByte() }) return

        val text = bytes.toString(Charsets.UTF_8)
        inspectText(text, location, state)

        if (file.extension.equals("xml", ignoreCase = true)) {
            inspectPreferencesXml(text, location, state)
        }
    }

    // Compatible with Android API 26; InputStream.readNBytes requires newer APIs.
    private fun InputStream.readBounded(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val count = read(buffer, offset, limit - offset)
            if (count < 0) break
            if (count == 0) {
                val next = read()
                if (next < 0) break
                buffer[offset++] = next.toByte()
            } else {
                offset += count
            }
        }
        return if (offset == limit) buffer else buffer.copyOf(offset)
    }

    private fun inspectText(
        text: String,
        location: String,
        state: ScanState
    ) {
        for (candidate in identifyAndExtract(text)) {
            addFinding(
                state,
                candidate.issueType,
                candidate.value,
                "$location:offset=${candidate.offset}"
            )
        }
    }

    private fun inspectPreferencesXml(
        text: String,
        location: String,
        state: ScanState
    ) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(text.reader())

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.DOCDECL) {
                    addError(state, location, "XML_DOCTYPE_SKIPPED")
                    state.incomplete = true
                    return
                }

                if (event == XmlPullParser.START_TAG && parser.name == "string") {
                    val name = parser.getAttributeValue(null, "name")
                    val value = parser.nextText()

                    if (
                        name != null &&
                        sensitiveName.matches(name) &&
                        !isPlaceholder(value)
                    ) {
                        addFinding(
                            state,
                            issueTypeFor(name),
                            value,
                            "$location:preference=$name"
                        )
                    }
                }

                event = parser.nextToken()
            }
        } catch (_: org.xmlpull.v1.XmlPullParserException) {
            state.incomplete = true
            addError(state, location, "XML_PARSE_ERROR")
        } catch (_: IOException) {
            state.incomplete = true
            addError(state, location, "XML_IO_ERROR")
        }
    }

    private suspend fun inspectDatabase(
        root: File,
        file: File,
        state: ScanState
    ) {
        val location = relativePath(root, file)

        // Refuse sidecar symlinks as well as symlinks to the main database.
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(file.path + suffix)
            if (Files.isSymbolicLink(sidecar.toPath()) ||
                !withinBoundary(root, sidecar.canonicalFile)
            ) {
                state.incomplete = true
                addError(state, location, "DATABASE_BOUNDARY_VIOLATION")
                return
            }
        }

        try {
            // An empty corruption handler prevents Android's default handler
            // from deleting the audited database when corruption is detected.
            SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READONLY or
                    SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { }
            ).use { db ->
                val tables = ArrayList<String>()

                db.rawQuery(
                    """
                    SELECT name FROM sqlite_master
                    WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                    ORDER BY name
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf((limits.maxTables + 1).toString())
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        tables += cursor.getString(0)
                    }
                }

                if (tables.size > limits.maxTables) {
                    state.incomplete = true
                    addError(state, location, "DATABASE_TABLE_LIMIT_REACHED")
                }

                for (table in tables.take(limits.maxTables)) {
                    currentCoroutineContext().ensureActive()

                    if (state.bytes >= limits.maxTotalBytes ||
                        state.findings.length() >= limits.maxFindings
                    ) {
                        state.incomplete = true
                        return
                    }

                    try {
                        val quotedTable = "\"" + table.replace("\"", "\"\"") + "\""

                        db.rawQuery(
                            "SELECT * FROM $quotedTable LIMIT ?",
                            arrayOf((limits.maxRowsPerTable + 1).toString())
                        ).use { cursor ->
                            var row = 0
                            while (cursor.moveToNext()) {
                                currentCoroutineContext().ensureActive()

                                if (row >= limits.maxRowsPerTable) {
                                    state.incomplete = true
                                    addError(
                                        state,
                                        "$location:table=$table",
                                        "DATABASE_ROW_LIMIT_REACHED"
                                    )
                                    break
                                }

                                for (column in 0 until cursor.columnCount) {
                                    if (cursor.getType(column) != Cursor.FIELD_TYPE_STRING) {
                                        continue
                                    }

                                    val value = cursor.getString(column) ?: continue
                                    val remaining = limits.maxTotalBytes - state.bytes
                                    if (remaining <= 0) {
                                        state.incomplete = true
                                        return
                                    }

                                    val charLimit = minOf(
                                        limits.maxFileBytes.toLong(),
                                        remaining / 4
                                    ).toInt()

                                    if (charLimit <= 0) {
                                        state.incomplete = true
                                        return
                                    }

                                    val inspected = value.take(charLimit)
                                    state.bytes += inspected.toByteArray(Charsets.UTF_8).size

                                    val name = cursor.getColumnName(column)
                                    val cell = "$location:table=$table:row=$row:column=$name"

                                    if (inspected.length != value.length) {
                                        state.incomplete = true
                                        addError(state, cell, "DATABASE_CELL_TRUNCATED")
                                    }

                                    if (
                                        sensitiveName.matches(name) &&
                                        !isPlaceholder(inspected)
                                    ) {
                                        addFinding(
                                            state,
                                            issueTypeFor(name),
                                            inspected,
                                            cell
                                        )
                                    }

                                    inspectText(inspected, cell, state)
                                }
                                row++
                            }
                        }
                    } catch (_: android.database.SQLException) {
                        state.incomplete = true
                        addError(
                            state,
                            "$location:table=$table",
                            "DATABASE_TABLE_READ_ERROR"
                        )
                    }
                }
            }
        } catch (_: android.database.SQLException) {
            state.incomplete = true
            addError(state, location, "DATABASE_READ_ERROR")
        }
    }

    private fun addFinding(
        state: ScanState,
        issueType: String,
        value: String,
        location: String
    ) {
        if (value.isBlank()) return

        if (state.findings.length() >= limits.maxFindings) {
            state.incomplete = true
            return
        }

        // Used only for in-memory deduplication; never included in reports.
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("$issueType\u0000$location\u0000$value".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        if (!state.fingerprints.add(fingerprint)) return

        state.findings.put(JSONObject().apply {
            put("package_name", packageName)
            put("issue_type", issueType)
            put("location", location)
            put("confidence", "potential")
            put("extracted_data", JSONObject().apply {
                put("value", if (includeRawSecrets) value else "[REDACTED]")
                put("length", value.length)
                put("redacted", !includeRawSecrets)
            })
        })
    }

    private fun addError(
        state: ScanState,
        location: String,
        code: String
    ) {
        // Exception messages may contain sensitive SQL or file content.
        if (state.errors.length() >= 200) return

        state.errors.put(JSONObject().apply {
            put("package_name", packageName)
            put("location", location)
            put("code", code)
        })
    }

    private fun issueTypeFor(name: String): String {
        val normalized = name.lowercase().replace("_", "").replace("-", "")
        return when {
            normalized in setOf("password", "passwd", "pwd") ->
                "PLAINTEXT_PASSWORD"
            normalized == "apikey" ->
                "API_KEY"
            normalized == "privatekey" ->
                "PRIVATE_KEY"
            normalized.contains("secret") ->
                "CLIENT_OR_APPLICATION_SECRET"
            else ->
                "AUTHENTICATION_TOKEN"
        }
    }

    private fun isPlaceholder(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.isEmpty() ||
            normalized in setOf(
                "null", "none", "undefined", "redacted",
                "[redacted]", "changeme", "your_api_key", "your_token"
            ) ||
            normalized.all { it == '*' } ||
            normalized.startsWith("\${")
    }

    private fun withinBoundary(root: File, file: File): Boolean =
        file.path == root.path ||
            file.path.startsWith(root.path + File.separator)

    private fun relativePath(root: File, file: File): String =
        if (withinBoundary(root, file)) {
            file.path.removePrefix(root.path).trimStart(File.separatorChar)
                .ifEmpty { "." }
        } else {
            "[outside-app-boundary]"
        }
}
