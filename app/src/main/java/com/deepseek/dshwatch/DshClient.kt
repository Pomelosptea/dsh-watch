package com.deepseek.dshwatch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Persisted connection settings. Kept deliberately small: host / port / token. */
class WatchConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dsh_watch", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", BuildConfig.DEFAULT_HOST) ?: BuildConfig.DEFAULT_HOST
        set(value) = prefs.edit().putString("host", value.trim()).apply()

    var port: Int
        get() = prefs.getInt("port", BuildConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value).apply()

    var token: String
        get() = prefs.getString("token", BuildConfig.DEFAULT_TOKEN) ?: BuildConfig.DEFAULT_TOKEN
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    val configured: Boolean
        get() = host.isNotBlank() && port in 1..65535

    val baseUrl: String
        get() {
            val raw = host.trim()
            val withScheme = when {
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> "http://$raw"
            }
            val noSlash = withScheme.trimEnd('/')
            return if (noSlash.contains(Regex(":[0-9]+$"))) noSlash else "$noSlash:$port"
        }
}

enum class EnqueueResult { OK, WITHDRAWN, FAILED }

data class EventBatch(val events: JSONArray, val lastSeq: Long)

sealed interface DshEvent {
    data class Accepted(val sessionId: String) : DshEvent
    data class Status(val text: String) : DshEvent
    data class Delta(val text: String) : DshEvent
    data class Done(val text: String, val reason: String) : DshEvent
    data class Failure(val message: String) : DshEvent
}

/**
 * Tiny raw-socket HTTP/1.0 client.
 *
 * The watch's Java HttpURLConnection stack is unreliable over the ZeroTier relay
 * (it often sits in connect/keep-alive until timeout even though raw TCP works).
 * Using a Socket with HTTP/1.0 + Connection: close gives us deterministic,
 * line-oriented NDJSON streaming and avoids the platform HTTP pool entirely.
 */
class DshClient(private val config: WatchConfig) {
    @Volatile private var active: Socket? = null
    private val cancelled = AtomicBoolean(false)

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        if (!config.configured) return@withContext false
        try {
            val uri = URI(config.baseUrl)
            val socket = Socket()
            socket.connect(InetSocketAddress(uri.host, uri.port), 15_000)
            socket.soTimeout = 15_000
            socket.use {
                val request = buildString {
                    append("GET /health HTTP/1.0\r\n")
                    append("Host: ").append(uri.host).append("\r\n")
                    if (config.token.isNotBlank()) {
                        append("Authorization: Bearer ").append(config.token).append("\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                it.getOutputStream().apply {
                    write(request.toByteArray(Charsets.UTF_8))
                    flush()
                }
                val statusLine = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    .readLine()
                    .orEmpty()
                statusLine.contains(" 200 ")
            }
        } catch (_: Exception) {
            false
        }
    }

    fun prompt(text: String): Flow<DshEvent> = flow {
        cancelled.set(false)
        val uri = URI(config.baseUrl)
        val body = JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
        val socket = Socket()
        active = socket
        try {
            socket.connect(InetSocketAddress(uri.host, uri.port), 30_000)
            socket.soTimeout = 15 * 60 * 1000

            val requestHead = buildString {
                append("POST /api/prompt HTTP/1.0\r\n")
                append("Host: ").append(uri.host).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                if (config.token.isNotBlank()) {
                    append("Authorization: Bearer ").append(config.token).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(requestHead.toByteArray(Charsets.UTF_8))
                write(body)
                flush()
            }

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine().orEmpty()
            var contentLength = -1
            var chunked = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: -1
                }
                if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                    chunked = true
                }
            }

            if (!statusLine.contains(" 200 ")) {
                val message = reader.readText().take(180)
                emit(DshEvent.Failure("主机返回 $statusLine${if (message.isBlank()) "" else "：$message"}"))
                return@flow
            }

            // HTTP/1.0 + Connection: close: read NDJSON until EOF. Chunked is
            // handled defensively in case a reverse proxy forces HTTP/1.1.
            if (chunked) {
                while (true) {
                    val sizeLine = reader.readLine() ?: break
                    val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
                    if (size == 0) break
                    val chars = CharArray(size)
                    var offset = 0
                    while (offset < size) {
                        val read = reader.read(chars, offset, size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    reader.readLine()
                    val payload = String(chars, 0, offset)
                    for (line in payload.lineSequence()) {
                        parseAndEmit(line)?.let { emit(it) }
                    }
                }
            } else {
                while (true) {
                    val line = reader.readLine() ?: break
                    parseAndEmit(line)?.let { emit(it) }
                    if (contentLength == 0) break
                }
            }
        } catch (error: Exception) {
            if (!cancelled.get()) {
                emit(DshEvent.Failure(error.message ?: "连接中断"))
            }
        } finally {
            runCatching { socket.close() }
            active = null
        }
    }.flowOn(Dispatchers.IO)

    /** Queue a message in the bridge mailbox. Returns quickly, even if DSH is busy. */
    suspend fun enqueue(id: String, text: String): EnqueueResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("id", id).put("text", text).toString()
        val response = request("POST", "/api/enqueue", body.toByteArray(Charsets.UTF_8), 15_000)
            ?: return@withContext EnqueueResult.FAILED
        when (response.first) {
            200 -> EnqueueResult.OK
            409 -> EnqueueResult.WITHDRAWN
            else -> EnqueueResult.FAILED
        }
    }

    /** Long-poll the mailbox. Null means a transient network failure. */
    suspend fun events(after: Long, timeoutSeconds: Int = 20): EventBatch? = withContext(Dispatchers.IO) {
        val response = request(
            "GET",
            "/api/events?after=$after&timeout=$timeoutSeconds",
            null,
            (timeoutSeconds + 12) * 1000,
        ) ?: return@withContext null
        if (response.first != 200) return@withContext null
        runCatching {
            val json = JSONObject(response.second)
            EventBatch(json.optJSONArray("events") ?: JSONArray(), json.optLong("lastSeq", after))
        }.getOrNull()
    }

    suspend fun withdraw(id: String): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("id", id).toString()
        val response = request("POST", "/api/withdraw", body.toByteArray(Charsets.UTF_8), 15_000)
        response?.first == 200
    }

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        timeoutMs: Int,
    ): Pair<Int, String>? {
        if (!config.configured) return null
        return try {
            val uri = URI(config.baseUrl)
            val socket = Socket()
            socket.connect(InetSocketAddress(uri.host, uri.port), 20_000)
            socket.soTimeout = timeoutMs + 10_000
            socket.use {
                val head = buildString {
                    append(method).append(' ').append(path).append(" HTTP/1.0\r\n")
                    append("Host: ").append(uri.host).append("\r\n")
                    append("Connection: close\r\n")
                    if (config.token.isNotBlank()) {
                        append("Authorization: Bearer ").append(config.token).append("\r\n")
                    }
                    if (body != null) {
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ").append(body.size).append("\r\n")
                    }
                    append("\r\n")
                }
                it.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    if (body != null) write(body)
                    flush()
                }
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                val statusLine = reader.readLine().orEmpty()
                val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                code to reader.readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { active?.close() }
        active = null
    }

    private fun parseAndEmit(line: String): DshEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val json = JSONObject(trimmed)
            when (json.optString("type")) {
                "accepted" -> DshEvent.Accepted(json.optString("sessionId"))
                "status" -> DshEvent.Status(json.optString("text"))
                "delta" -> DshEvent.Delta(json.optString("text"))
                "done" -> DshEvent.Done(json.optString("text"), json.optString("reason", "completed"))
                "error" -> DshEvent.Failure(json.optString("message", "未知错误"))
                else -> null
            }
        }.getOrNull()
    }
}
