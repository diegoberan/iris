package ai.iris.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A rendered line in the conversation: a chat message OR an agent-activity
 *  row (tool call / thinking), interleaved in the order they streamed. */
sealed interface TranscriptItem {
    val key: String
}

data class ChatMessage(
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    /** Gateway-local path of an attached audio file (TTS voice reply),
     *  extracted from the message's MEDIA: line. Fetched via /api/media. */
    val mediaPath: String? = null,
    override val key: String = "msg-${System.nanoTime()}",
) : TranscriptItem

/** Compact "Thinking" / "Session Search · 3.5s" row in the transcript.
 *  Detail fields are optional so pre-existing call sites keep compiling and
 *  rows without detail render exactly as before; when present they feed the
 *  tap-to-expand detail view (Desktop ToolEntry parity). */
data class ActivityRow(
    val id: String,
    val label: String,
    val running: Boolean,
    val durationS: Double? = null,
    /** <=80-char human label the gateway ships on tool.start (query/path/command). */
    val context: String? = null,
    val summary: String? = null,
    val argsJson: String? = null,
    val resultJson: String? = null,
    val inlineDiff: String? = null,
    val isError: Boolean = false,
    override val key: String = "act-$id",
) : TranscriptItem

data class SessionRow(val id: String, val title: String, val preview: String)

data class ProjectRow(val id: String, val label: String, val sessionCount: Int, val sessions: List<SessionRow>)

data class ProviderModels(val slug: String, val name: String, val models: List<String>)

data class ProfileRow(val name: String, val isDefault: Boolean, val description: String)

/**
 * Chat state over the shared GatewayConnection. Protocol mirrors what the
 * Desktop renderer drives: session.list/create/resume/history, prompt.submit,
 * message.delta / message.complete, tool.start / tool.complete and
 * reasoning.* for the activity rows, session.info for the live model pair,
 * config.set for switching, iris.tier.used for the tier notice.
 */
class ChatViewModel : ViewModel() {
    val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val activeSessionId = MutableStateFlow<String?>(null)
    val activeTitle = MutableStateFlow("New chat")
    val transcript = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val busy = MutableStateFlow(false)
    val currentModel = MutableStateFlow("")
    val currentProvider = MutableStateFlow("")
    /** Model that ACTUALLY answered the last turn when the Iris Orchestrator
     *  routed it to a different tier than the session's configured model
     *  (kill-switch failover). Display-only overlay for the composer pill;
     *  cleared by a manual pick or a session switch. */
    val tierOverride = MutableStateFlow<String?>(null)
    val tierNotice = MutableStateFlow("")
    val errorNotice = MutableStateFlow("")
    val providers = MutableStateFlow<List<ProviderModels>>(emptyList())

    /** Projects (project -> its sessions) and the session ids they claim (so
     *  the flat session list can drop them, matching the Desktop). */
    val projects = MutableStateFlow<List<ProjectRow>>(emptyList())
    val scopedSessionIds = MutableStateFlow<Set<String>>(emptySet())

    /** Profiles are separate agent identities on the gateway host; the active
     *  one scopes the session list and every session RPC (resume/prompt). */
    val profiles = MutableStateFlow<List<ProfileRow>>(emptyList())
    val activeProfile = MutableStateFlow("default")

    /** Filenames of images attached to (and shipped with) the next prompt. */
    val pendingAttachments = MutableStateFlow<List<String>>(emptyList())
    /** Transcribed dictation text pushed to the composer draft. */
    val dictated = MutableStateFlow("")
    val dictating = MutableStateFlow(false)

    /** STORED id (state.db key) of the selected session. Live ids die with the
     *  connection, so a reconnect must re-resume from this one. */
    private var storedSessionId: String? = null

    /** The user explicitly asked for a fresh chat. Must survive a reconnect:
     *  bootstrap() otherwise re-picks the most recent stored session and
     *  silently steamrolls the just-opened "New chat" back to the old one
     *  (the tap looks like a no-op when the socket was down at that moment). */
    private var wantsFreshSession = false

    init {
        viewModelScope.launch { GatewayConnection.events.collect(::onEvent) }
        viewModelScope.launch {
            GatewayConnection.status.collect { st ->
                when (st) {
                    "connected" -> bootstrap()
                    // Socket died mid-answer: message.complete will never
                    // arrive, so release the Stop/busy state and close any
                    // streaming bubble instead of leaving them stuck forever.
                    "disconnected" -> {
                        busy.value = false
                        clearThinking()
                        transcript.value = transcript.value.map {
                            if (it is ChatMessage && it.streaming) it.copy(streaming = false) else it
                        }
                    }
                }
            }
        }
    }

    private suspend fun bootstrap() {
        loadProfiles()
        refreshSessions()
        loadProjects()
        val stored = storedSessionId
        if (wantsFreshSession) {
            // A "New chat" is pending from before this (re)connect. Any live id
            // is stale on the fresh connection; create the new session here
            // instead of re-picking the old one below.
            activeSessionId.value = null
            ensureSession()
        } else if (stored != null) {
            // Reconnect: the old LIVE id is stale on a fresh gateway
            // connection -- re-resume from the stored id so the next
            // prompt.submit targets a session this connection knows.
            selectSession(stored)
        } else if (activeSessionId.value == null) {
            sessions.value.firstOrNull()?.let { selectSession(it.id) }
        }
        // Seed the composer's model pill with the real current model so it
        // shows e.g. "deepseek-v4-flash" up front instead of "Select model".
        loadModelOptions()
    }

    private fun profileParam(): String? = activeProfile.value.takeIf { it != "default" }

    fun loadProfiles() {
        viewModelScope.launch {
            runCatching {
                val rest = GatewayConnection.rest ?: return@launch
                val body = GatewayConnection.json.parseToJsonElement(rest.getJsonText("/api/profiles")).jsonObject
                profiles.value = (body["profiles"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { p ->
                    val obj = p as? JsonObject ?: return@mapNotNull null
                    ProfileRow(
                        name = obj.str("name") ?: return@mapNotNull null,
                        isDefault = (obj["is_default"] as? JsonPrimitive)?.content == "true",
                        description = obj.str("description").orEmpty()
                    )
                }
            }.onFailure { /* single-profile gateways may not expose this */ }
        }
    }

    fun switchProfile(name: String) {
        if (name == activeProfile.value) return
        activeProfile.value = name
        activeSessionId.value = null
        storedSessionId = null
        transcript.value = emptyList()
        projects.value = emptyList()
        refreshSessions()
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            runCatching {
                // preview_limit high enough to render a project's sessions inline
                // without a second project_sessions round-trip.
                val result = GatewayConnection.request(
                    "projects.tree", buildJsonObject { put("preview_limit", 25) }, 60_000
                )
                val rows = (result["projects"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { p ->
                    val obj = p as? JsonObject ?: return@mapNotNull null
                    ProjectRow(
                        id = obj.str("id") ?: return@mapNotNull null,
                        label = obj.str("label") ?: "Project",
                        sessionCount = (obj["sessionCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                        sessions = (obj["previewSessions"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { s ->
                            val so = s as? JsonObject ?: return@mapNotNull null
                            SessionRow(
                                id = so.str("id") ?: return@mapNotNull null,
                                title = so.str("title").orEmpty().ifBlank { "Untitled" },
                                preview = so.str("preview").orEmpty()
                            )
                        }
                    )
                }.filter { it.sessions.isNotEmpty() }
                projects.value = rows
                scopedSessionIds.value = (result["scoped_session_ids"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            }.onFailure { /* projects are optional; a bare gateway has none */ }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            runCatching {
                val profile = profileParam()
                val rowsJson: JsonArray = if (profile == null) {
                    val result = GatewayConnection.request("session.list", buildJsonObject { put("limit", 60) })
                    result["sessions"] as? JsonArray ?: JsonArray(emptyList())
                } else {
                    // Non-default profile: the RPC list is bound to the gateway's
                    // own tenant, so read the cross-profile REST list scoped to it.
                    val rest = GatewayConnection.rest ?: error("not connected")
                    val body = GatewayConnection.json
                        .parseToJsonElement(rest.getJsonText("/api/profiles/sessions?profile=$profile&limit=60"))
                        .jsonObject
                    body["sessions"] as? JsonArray ?: JsonArray(emptyList())
                }
                sessions.value = rowsJson.mapNotNull { row ->
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
            // Picking an existing session overrides any pending fresh-chat intent.
            wantsFreshSession = false
            storedSessionId = id
            runCatching {
                activeTitle.value = sessions.value.firstOrNull { it.id == id }?.title ?: "Chat"
                transcript.value = emptyList()
                // session.list hands out STORED ids (state.db keys); resume maps
                // one to a LIVE session id (short hex) -- with a fast path that
                // returns the existing live id when it's already resumed. Every
                // follow-up RPC must use the live id, not the stored one.
                val resumed = GatewayConnection.request(
                    "session.resume",
                    buildJsonObject { put("session_id", id); profileParam()?.let { put("profile", it) } },
                    60_000
                )
                val liveId = resumed.str("session_id") ?: id
                activeSessionId.value = liveId
                tierOverride.value = null
                val history = GatewayConnection.request("session.history", buildJsonObject { put("session_id", liveId) })
                transcript.value = (history["messages"] as? JsonArray ?: JsonArray(emptyList())).mapIndexedNotNull { idx, row ->
                    val obj = row as? JsonObject ?: return@mapIndexedNotNull null
                    val role = obj.str("role") ?: return@mapIndexedNotNull null
                    // Stored tool rows come back as {role:"tool", name, context}
                    // -- render them as (finished) activity rows so a reloaded
                    // session keeps its tool trail instead of dropping it.
                    if (role == "tool") {
                        val name = obj.str("name") ?: obj.str("tool_name") ?: return@mapIndexedNotNull null
                        return@mapIndexedNotNull ActivityRow(
                            id = "hist-$idx-$name",
                            label = friendlyTool(name),
                            running = false,
                            context = obj.str("context")?.takeIf { it.isNotBlank() },
                        ) as TranscriptItem
                    }
                    if (role != "user" && role != "assistant") return@mapIndexedNotNull null
                    val text = obj.textContent() ?: return@mapIndexedNotNull null
                    if (text.isBlank()) return@mapIndexedNotNull null
                    withMedia(role, text) as TranscriptItem
                }.filter { (it as? ChatMessage)?.let { m -> m.text.isNotBlank() || m.mediaPath != null } ?: true }
            }.onFailure { errorNotice.value = "resume: ${it.message}" }
        }
    }

    fun newSession() {
        viewModelScope.launch {
            // ensureSession() short-circuits on an active id, so "New session"
            // from the drawer was a no-op mid-chat. Drop the current session
            // first -- this action must always open a fresh chat.
            wantsFreshSession = true
            activeSessionId.value = null
            storedSessionId = null
            tierOverride.value = null
            activeTitle.value = "New chat"
            transcript.value = emptyList()
            ensureSession()
        }
    }

    /** Return the active session id, creating one first if none exists.
     *  null only if session.create failed. */
    private suspend fun ensureSession(): String? {
        activeSessionId.value?.let { return it }
        errorNotice.value = ""
        return runCatching {
            val result = GatewayConnection.request(
                "session.create",
                buildJsonObject { put("source", "iris-android"); profileParam()?.let { put("profile", it) } },
                60_000
            )
            val id = result.str("session_id") ?: result.str("id") ?: return@runCatching null
            activeSessionId.value = id
            wantsFreshSession = false
            // Fresh sessions only have a live id; a reconnect re-picks the
            // most recent stored session from the list instead.
            storedSessionId = null
            activeTitle.value = "New chat"
            transcript.value = emptyList()
            refreshSessions()
            id
        }.onFailure { errorNotice.value = "new chat: ${it.message}" }.getOrNull()
    }

    fun sendPrompt(text: String) {
        viewModelScope.launch {
            // Create a session on the fly when sending from the empty state,
            // then send THIS text -- don't drop it (the old code returned
            // after newSession(), so the first message vanished).
            val sid = ensureSession() ?: run { errorNotice.value = "Could not start a chat"; return@launch }
            // A fresh turn clears leftovers from the previous one -- a stale
            // red error line or an old "answered by ..." tier notice would
            // otherwise sit under the composer indefinitely.
            errorNotice.value = ""
            tierNotice.value = ""
            val attachSuffix = pendingAttachments.value.takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "  📎 ") ?: ""
            transcript.value = transcript.value + ChatMessage("user", text + attachSuffix)
            pendingAttachments.value = emptyList()
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
                // Some interrupts are acked without a trailing message.complete
                // -- release the Stop button instead of leaving it stuck.
                .onSuccess { busy.value = false }
        }
    }

    /** Attach an image (base64) to the session; it ships with the next prompt.
     *  Same RPC the remote Desktop uses (client has no gateway-local path). */
    fun attachImage(base64: String, filename: String) {
        val sid = activeSessionId.value ?: run { newSession(); return }
        viewModelScope.launch {
            runCatching {
                GatewayConnection.request("image.attach_bytes", buildJsonObject {
                    put("session_id", sid)
                    put("content_base64", base64)
                    put("filename", filename)
                }, 60_000)
                pendingAttachments.value = pendingAttachments.value + filename
            }.onFailure { errorNotice.value = "attach: ${it.message}" }
        }
    }

    /** Record → transcribe: push the Whisper transcript to the composer draft
     *  (dictation, like the ChatGPT mic). The clip goes to the Brain's STT. */
    fun transcribe(dataUrl: String, mimeType: String) {
        viewModelScope.launch {
            dictating.value = true
            runCatching {
                val rest = GatewayConnection.rest ?: error("not connected")
                rest.transcribe(dataUrl, mimeType)
            }.onSuccess { text -> if (!text.isNullOrBlank()) dictated.value = text }
                .onFailure { errorNotice.value = "voice: ${it.message}" }
            dictating.value = false
        }
    }

    fun consumeDictation() { dictated.value = "" }

    fun loadModelOptions(refresh: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                val result = GatewayConnection.request("model.options", buildJsonObject {
                    activeSessionId.value?.let { put("session_id", it) }
                    if (refresh) put("refresh", true)
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
                // model.options carries the current {model, provider} -- seed the
                // pill so it shows the real model even before session.info lands.
                result.str("model")?.takeIf { it.isNotBlank() }?.let { if (currentModel.value.isBlank()) currentModel.value = it }
                result.str("provider")?.takeIf { it.isNotBlank() }?.let { if (currentProvider.value.isBlank()) currentProvider.value = it }
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
                // A manual pick supersedes any live tier overlay on the pill.
                tierOverride.value = null
            }.onFailure { errorNotice.value = "switch: ${it.message}" }
        }
    }

    // ── Gateway events ────────────────────────────────────────────────

    private fun onEvent(params: JsonObject) {
        val type = params.str("type") ?: return
        val eventSid = params.str("session_id").orEmpty()
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val mine = eventSid.isEmpty() || eventSid == activeSessionId.value
        // Message text is stricter than the cosmetic events: with several
        // bodies live on the same Brain (Desktop streaming its own session
        // while the phone chat is open), an empty-sid pass-through would
        // bleed another surface's deltas into this transcript.
        val strictMine = eventSid.isNotEmpty() && eventSid == activeSessionId.value

        when (type) {
            "reasoning.delta", "reasoning.available" -> if (mine) markThinking()

            "tool.start", "tool.progress", "tool.generating" -> if (mine) {
                clearThinking()
                val id = payload.str("tool_id") ?: payload.str("name") ?: return
                upsertActivity(ActivityRow(
                    id, friendlyTool(payload.str("name")), running = true,
                    context = payload.str("context") ?: payload.str("preview"),
                ))
            }

            "tool.complete" -> if (mine) {
                val id = payload.str("tool_id") ?: payload.str("name") ?: return
                val dur = (payload["duration_s"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                val error = payload["error"]
                val isError = error != null && (error as? JsonPrimitive)?.content !in listOf(null, "", "false")
                upsertActivity(ActivityRow(
                    id, friendlyTool(payload.str("name")), running = false, durationS = dur,
                    context = payload.str("context") ?: payload.str("preview"),
                    summary = payload.str("summary"),
                    argsJson = detailText(payload["args"]),
                    resultJson = detailText(payload["result"]),
                    inlineDiff = payload.str("inline_diff"),
                    isError = isError,
                ))
            }

            "message.delta" -> if (strictMine) {
                clearThinking()
                val delta = payload.textContent() ?: payload.str("text") ?: return
                val current = transcript.value
                // The streaming message is not necessarily the LAST item: a
                // tool row appended mid-stream used to strand the fragment
                // and start a duplicate bubble on the next delta.
                val idx = current.indexOfLast { it is ChatMessage && it.streaming }
                transcript.value = if (idx >= 0) {
                    val msg = current[idx] as ChatMessage
                    current.toMutableList().also { it[idx] = msg.copy(text = msg.text + delta) }
                } else {
                    current + ChatMessage("assistant", delta, streaming = true)
                }
            }

            "message.complete" -> if (strictMine) {
                busy.value = false
                errorNotice.value = ""
                clearThinking()
                val current = transcript.value
                val full = payload.textContent() ?: payload.str("text")
                // Drop EVERY streaming fragment (not just a trailing one) so a
                // mid-stream tool row can't leave a duplicate behind, then
                // append the authoritative full text.
                val streamed = current.filterIsInstance<ChatMessage>()
                    .filter { it.streaming }.joinToString("") { it.text }
                val base = current.filterNot { it is ChatMessage && it.streaming }
                val finalText = full ?: streamed
                transcript.value =
                    if (finalText.isBlank()) base else base + withMedia("assistant", finalText)
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
                android.util.Log.i("IrisNode", "iris.tier.used tier=$tier model=$model busy=${busy.value}")
                // The broadcast is GLOBAL (every connected device gets it, no
                // session id): only honor it while THIS device is waiting for
                // an answer -- otherwise another surface's turn (or a plain
                // API test against the orchestrator) stomps this session's
                // notice and pill.
                if (tier.isNotEmpty() && busy.value) {
                    val tierLabel = when (tier) {
                        "desktop-local" -> "Iris Local (your GPU)"
                        "amd-cloud" -> "Iris AMD Cloud"
                        "cloud-fallback" -> "cloud fallback"
                        else -> tier
                    }
                    tierNotice.value = "answered by $tierLabel${if (model.isNotEmpty()) " — $model" else ""}"
                    // Display-only overlay for the composer pill (Desktop
                    // parity): the session config still points at the bridge;
                    // the next tier broadcast (local answering again) flips it
                    // back, and a manual model pick clears it.
                    if (model.isNotEmpty()) tierOverride.value = model
                }
            }
        }
    }

    // ── Activity-row helpers ──────────────────────────────────────────

    private val thinkingKey = "thinking-live"

    private fun markThinking() {
        val current = transcript.value
        if (current.any { it is ActivityRow && it.id == thinkingKey && it.running }) return
        upsertActivity(ActivityRow(thinkingKey, "Thinking", running = true))
    }

    private fun clearThinking() {
        transcript.value = transcript.value.filterNot { it is ActivityRow && it.id == thinkingKey }
    }

    private fun upsertActivity(row: ActivityRow) {
        val current = transcript.value
        val idx = current.indexOfLast { it is ActivityRow && (it as ActivityRow).id == row.id }
        if (idx < 0) {
            transcript.value = current + row
            return
        }
        // Merge: sparse progress/complete payloads must not wipe detail an
        // earlier event already carried (e.g. tool.start's context label).
        val prev = current[idx] as ActivityRow
        val merged = row.copy(
            context = row.context ?: prev.context,
            summary = row.summary ?: prev.summary,
            argsJson = row.argsJson ?: prev.argsJson,
            resultJson = row.resultJson ?: prev.resultJson,
            inlineDiff = row.inlineDiff ?: prev.inlineDiff,
            durationS = row.durationS ?: prev.durationS,
        )
        transcript.value = current.toMutableList().also { it[idx] = merged }
    }

    private fun friendlyTool(name: String?): String {
        val raw = name?.takeIf { it.isNotBlank() } ?: "Tool"
        return raw.split('_', '-', ' ').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    // ── JSON helpers ──────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** Render a tool args/result payload for the detail view: strings as-is,
     *  anything else as compact JSON; capped so a huge result can't bloat the
     *  transcript state. Empty-ish payloads collapse to null (no section). */
    private fun detailText(el: kotlinx.serialization.json.JsonElement?): String? {
        if (el == null) return null
        val s = (if (el is JsonPrimitive) el.content else el.toString()).trim()
        if (s.isEmpty() || s == "{}" || s == "null") return null
        return if (s.length > 4000) s.take(4000) + "…" else s
    }

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
