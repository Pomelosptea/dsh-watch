package com.deepseek.dshwatch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One user message plus its assistant reply. The watch is the source of truth
 * for history; the bridge mailbox only carries status/reply updates.
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val status: String = STATUS_QUEUED,
    val reply: String = "",
    val phase: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val seq: Long = 0,
) {
    val pending: Boolean get() = status == STATUS_QUEUED
    val running: Boolean get() = status == STATUS_RUNNING
    val finished: Boolean get() = status == STATUS_DONE
    val withdrawn: Boolean get() = status == STATUS_WITHDRAWN

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_SENT = "sent"
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_ERROR = "error"
        const val STATUS_WITHDRAWN = "withdrawn"
    }
}

class ChatStore(context: Context) {
    private val file = File(context.filesDir, "dsh_chat.json")
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    @Volatile var lastSeq: Long = 0
        private set

    init {
        load()
    }

    fun enqueue(text: String): ChatMessage {
        val message = ChatMessage(
            id = "w-" + UUID.randomUUID().toString().replace("-", ""),
            text = text.trim(),
            status = ChatMessage.STATUS_QUEUED,
        )
        _messages.value = (_messages.value + message).sortedBy { it.createdAt }
        save()
        return message
    }

    fun pending(): List<ChatMessage> =
        _messages.value.filter { it.status == ChatMessage.STATUS_QUEUED }

    fun latest(): ChatMessage? = _messages.value.lastOrNull()

    fun withdraw(id: String) {
        update(id) {
            it.copy(
                status = ChatMessage.STATUS_WITHDRAWN,
                phase = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun markQueued(id: String) {
        update(id) { it.copy(status = ChatMessage.STATUS_QUEUED, error = null) }
    }

    fun markSent(id: String) {
        update(id) {
            if (it.status == ChatMessage.STATUS_QUEUED) {
                it.copy(status = ChatMessage.STATUS_SENT, updatedAt = System.currentTimeMillis())
            } else {
                it
            }
        }
    }

    fun applyServerEvents(events: JSONArray, newLastSeq: Long) {
        var changed = false
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val id = event.optString("id")
            if (id.isBlank()) continue
            val status = event.optString("status", ChatMessage.STATUS_QUEUED)
            // Only update messages this watch created. The bridge mailbox can
            // contain old/test entries; they must not appear as local history.
            val current = _messages.value.firstOrNull { it.id == id } ?: continue
            val updated = ChatMessage(
                id = id,
                text = event.optString("text", current?.text.orEmpty()),
                status = status,
                reply = event.optString("reply", current?.reply.orEmpty()),
                phase = if (event.isNull("phase")) null else event.optString("phase").ifBlank { null },
                error = if (event.isNull("error")) null else event.optString("error").ifBlank { null },
                createdAt = current?.createdAt ?: event.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = event.optLong("updatedAt", System.currentTimeMillis()),
                seq = event.optLong("seq", 0),
            )
            _messages.value = _messages.value.map { if (it.id == id) updated else it }
            changed = true
        }
        if (newLastSeq > lastSeq) lastSeq = newLastSeq
        if (changed) save()
    }

    private fun update(id: String, transform: (ChatMessage) -> ChatMessage) {
        var changed = false
        _messages.value = _messages.value.map {
            if (it.id == id) {
                changed = true
                transform(it)
            } else {
                it
            }
        }
        if (changed) save()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText(Charsets.UTF_8))
            lastSeq = root.optLong("lastSeq", 0)
            val array = root.optJSONArray("messages") ?: return
            val list = ArrayList<ChatMessage>()
            for (i in 0 until array.length()) {
                val j = array.optJSONObject(i) ?: continue
                list += ChatMessage(
                    id = j.optString("id"),
                    text = j.optString("text"),
                    status = j.optString("status", ChatMessage.STATUS_QUEUED),
                    reply = j.optString("reply"),
                    phase = if (j.isNull("phase")) null else j.optString("phase").ifBlank { null },
                    error = if (j.isNull("error")) null else j.optString("error").ifBlank { null },
                    createdAt = j.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = j.optLong("updatedAt", System.currentTimeMillis()),
                    seq = j.optLong("seq", 0),
                )
            }
            _messages.value = list.sortedBy { it.createdAt }
        } catch (_: Exception) {
            // A corrupt cache must never prevent the app from starting.
            _messages.value = emptyList()
        }
    }

    private fun save() {
        try {
            val array = JSONArray()
            for (message in _messages.value.takeLast(200)) {
                array.put(JSONObject().apply {
                    put("id", message.id)
                    put("text", message.text)
                    put("status", message.status)
                    put("reply", message.reply)
                    put("phase", message.phase ?: JSONObject.NULL)
                    put("error", message.error ?: JSONObject.NULL)
                    put("createdAt", message.createdAt)
                    put("updatedAt", message.updatedAt)
                    put("seq", message.seq)
                })
            }
            val root = JSONObject().apply {
                put("lastSeq", lastSeq)
                put("messages", array)
            }
            file.writeText(root.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}

/** Process-wide singletons so the UI and the foreground sync service share state. */
object AppGraph {
    lateinit var config: WatchConfig
    lateinit var client: DshClient
    lateinit var store: ChatStore

    fun init(context: Context) {
        if (::store.isInitialized) return
        config = WatchConfig(context)
        client = DshClient(config)
        store = ChatStore(context)
    }
}
