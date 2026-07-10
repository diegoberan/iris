package ai.iris.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ChatMessage(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    /** Gateway-local path of an attached audio file (TTS voice reply),
     *  extracted from the message's MEDIA: line. Fetched via /api/media. */
    val mediaPath: String? = null,
)

data class SessionRow(val id: String, val title: String, val preview: String)

data class ProviderModels(val slug: String, val name: String, val models: List<String>)

/**
 * Chat state over the shared GatewayConnection. Protocol mirrors what the
 * Desktop renderer drives: session.list/create/resume/history, prompt.submit,
 * message.delta / message.complete stream events, session.info for the live
 * model pair, config.set for switching, iris.tier.used for the tier toast.
 */
class ChatViewModel : ViewModel() {
    val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val activeSessionId = MutableStateFlow<String?>(null)
    val activeTitle = MutableStateFlow("New chat")
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val busy = MutableStateFlow(false)
    val currentModel = MutableStateFlow("")
    val currentProvider = MutableStateFlow("")
    val tierNotice = MutableStateFlow("")
    val errorNotice = MutableStateFlow("")
    val providers = MutableStateFlow<List<ProviderModels>>(emptyList())

    init {
        viewModelScope.launch { GatewayConnection.events.collect(::onEvent) }
        viewModelScope.launch {
            GatewayConnection.status.collect { if (it == "connected") bootstrap() }
        }
    }

    private suspend fun bootstrap() {
        refreshSessions()
        if (activeSessionId.value == null) {
            sessions.value.firstOrNull()?.let { selectSession(it.id) }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            runCatching {
                val result = GatewayConnection.request("session.list", buildJsonObject { put("limit", 60) })
                sessions.value = (result["sessions"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { row ->
                    val obj = row as? JsonObject ?: return@mapNotNull null
                    SessionRow(
                        id = obj.str("id") ?: return@mapNotNull null,
                        title = obj.str("title").orEmpty().ifBlank { "Untitled" },
                        preview = obj.str("preview").orEmpty()
                    )
                }
            }.onFailure { errorNotice.value = "sessions: ${it.message}" }
        }
    }

    fun selectSession(id: String) {
        viewModelScope.launch {
            errorNotice.value = ""
            runCatching {
                activeTitle.value = sessions.value.firstOrNull { it.id == id }?.title ?: "Chat"
                messages.value = emptyList()
                // session.list hands out STORED ids (state.db keys); resume maps
                // one to a LIVE session id (short hex) -- with a fast path that
                // returns the existing live id when it's already resumed. Every
                // follow-up RPC must use the live id, not the stored one.
                val resumed = GatewayConnection.request(
                    "session.resume",
                    buildJsonObject { put("session_id", id) },
                    60_000
                )
                val liveId = resumed.str("session_id") ?: id
                activeSessionId.value = liveId
                val history = GatewayConnection.request("session.history", buildJsonObject { put("session_id", liveId) })
                messages.value = (history["messages"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { row ->
                    val obj = row as? JsonObject ?: return@mapNotNull null
                    val role = obj.str("role") ?: return@mapNotNull null
                    if (role != "user" && role != "assistant") return@mapNotNull null
                    val text = obj.textContent() ?: return@mapNotNull null
                    if (text.isBlank()) return@mapNotNull null
                    withMedia(role, text)
                }.filter { it.text.isNotBlank() || it.mediaPath != null }
            }.onFailure { errorNotice.value = "resume: ${it.message}" }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            errorNotice.value = ""
            runCatching {
                val result = GatewayConnection.request(
                    "session.create",
                    buildJsonObject { put("source", "iris-android") },
                    60_000
                )
                val id = result.str("session_id") ?: result.str("id") ?: return@runCatching
                activeSessionId.value = id
                activeTitle.value = "New chat"
                messages.value = emptyList()
                refreshSessions()
            }.onFailure { errorNotice.value = "new chat: ${it.message}" }
        }
    }

    fun sendPrompt(text: String) {
        val sid = activeSessionId.value ?: run { newSession(); return }
        viewModelScope.launch {
            messages.value = messages.value + ChatMessage("user", text)
            busy.value = true
            runCatching {
                GatewayConnection.request("prompt.submit", buildJsonObject {
                    put("session_id", sid)
                    put("text", text)
                }, 60_000)
            }.onFailure {
                busy.value = false
                errorNotice.value = "send: ${it.message}"
            }
        }
    }

    fun interrupt() {
        val sid = activeSessionId.value ?: return
        viewModelScope.launch {
            runCatching { GatewayConnection.request("session.interrupt", buildJsonObject { put("session_id", sid) }) }
        }
    }

    fun loadModelOptions() {
        viewModelScope.launch {
            runCatching {
                val result = GatewayConnection.request("model.options", buildJsonObject {
                    activeSessionId.value?.let { put("session_id", it) }
                }, 60_000)
                providers.value = (result["providers"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { row ->
                    val obj = row as? JsonObject ?: return@mapNotNull null
                    val models = (obj["models"] as? JsonArray ?: JsonArray(emptyList()))
                        .mapNotNull { (it as? JsonPrimitive)?.content }
                    if (models.isEmpty()) return@mapNotNull null
                    ProviderModels(
                        slug = obj.str("slug") ?: return@mapNotNull null,
                        name = obj.str("name") ?: obj.str("slug").orEmpty(),
                        models = models
                    )
                }
            }.onFailure { errorNotice.value = "models: ${it.message}" }
        }
    }

    fun switchModel(provider: String, model: String) {
        val sid = activeSessionId.value ?: return
        viewModelScope.launch {
            runCatching {
                GatewayConnection.request("config.set", buildJsonObject {
                    put("session_id", sid)
                    put("key", "model")
                    put("value", "$model --provider $provider")
                }, 60_000)
                currentModel.value = model
                currentProvider.value = provider
            }.onFailure { errorNotice.value = "switch: ${it.message}" }
        }
    }

    // ── Gateway events ────────────────────────────────────────────────

    private fun onEvent(params: JsonObject) {
        val type = params.str("type") ?: return
        val eventSid = params.str("session_id").orEmpty()
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val mine = eventSid.isEmpty() || eventSid == activeSessionId.value

        when (type) {
            "message.delta" -> if (mine) {
                val delta = payload.textContent() ?: payload.str("text") ?: return
                val current = messages.value
                val last = current.lastOrNull()
                messages.value = if (last != null && last.streaming) {
                    current.dropLast(1) + last.copy(text = last.text + delta)
                } else {
                    current + ChatMessage("assistant", delta, streaming = true)
                }
            }

            "message.complete" -> if (mine) {
                busy.value = false
                val current = messages.value
                val last = current.lastOrNull()
                val full = payload.textContent() ?: payload.str("text")
                messages.value = when {
                    last != null && last.streaming ->
                        current.dropLast(1) + withMedia("assistant", full ?: last.text)
                    !full.isNullOrBlank() -> current + withMedia("assistant", full)
                    else -> current
                }
                refreshSessions() // titles update after first turns
            }

            "session.info" -> if (mine) {
                payload.str("model")?.let { currentModel.value = it }
                payload.str("provider")?.let { currentProvider.value = it }
                payload["running"]?.let { busy.value = (it as? JsonPrimitive)?.content == "true" }
            }

            "iris.tier.used" -> {
                val tier = payload.str("tier").orEmpty()
                val model = payload.str("model").orEmpty()
                if (tier.isNotEmpty()) tierNotice.value = "answered by $tier${if (model.isNotEmpty()) " — $model" else ""}"
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    // Same convention the Desktop renders: a "MEDIA:<gateway-local path>"
    // line inside the message text marks an attachment (TTS voice replies).
    private val mediaLineRe = Regex("(?m)^[\\t ]*[`\"']?MEDIA:\\s*([^`\"'\\n]+?)[`\"']?[\\t ]*$")

    private fun withMedia(role: String, text: String, streaming: Boolean = false): ChatMessage {
        val match = mediaLineRe.find(text)
        return ChatMessage(
            role = role,
            text = mediaLineRe.replace(text, "").trim(),
            streaming = streaming,
            mediaPath = match?.groupValues?.get(1)?.trim()
        )
    }

    /** Message text may be a plain string or a parts array [{type:text,text}]. */
    private fun JsonObject.textContent(): String? {
        (this["content"] as? JsonPrimitive)?.let { return it.content }
        (this["text"] as? JsonPrimitive)?.let { return it.content }
        val parts = (this["content"] as? JsonArray) ?: return null
        return parts.joinToString("") { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
        }.ifBlank { null }
    }
}
