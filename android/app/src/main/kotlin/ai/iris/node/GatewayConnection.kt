package ai.iris.node

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The ONE WebSocket to the Brain, shared by every surface of the app: the
 * NodeService owns its lifecycle (connect/reconnect/announce), the chat UI
 * multiplexes RPCs and streaming events over it. One socket matters --
 * capabilities are announced per-connection, and prompt.submit re-binds the
 * session's event stream to whichever socket submitted, so chat streaming and
 * device act requests must share the same pipe.
 */
object GatewayConnection {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** disconnected | authenticating | connected | error: … */
    val status = MutableStateFlow("disconnected")

    /** Every gateway event frame's `params` ({type, payload, session_id?}). */
    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)

    private var session: DefaultClientWebSocketSession? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicLong(1)

    /** The authenticated HTTP side of the live connection (cookie jar shared
     *  with the socket). Set by NodeService while connected; REST consumers
     *  (media fetch) must tolerate null between reconnects. */
    @Volatile
    var rest: GatewayClient? = null

    val isConnected: Boolean get() = status.value == "connected"

    /** Send a JSON-RPC request and await its response. Throws on gateway error
     *  frames ({"error": {...}}), timeout, or no live connection. */
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 30_000): JsonObject {
        val ws = session ?: throw IllegalStateException("not connected")
        val id = "app-${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val frame = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            ws.send(Frame.Text(frame.toString()))
            val reply = withTimeout(timeoutMs) { deferred.await() }
            (reply["error"] as? JsonObject)?.let { error ->
                throw IllegalStateException(error.toString())
            }
            return (reply["result"] as? JsonObject) ?: JsonObject(emptyMap())
        } finally {
            pending.remove(id)
        }
    }

    /** Fire-and-forget frame (announce, act responses). */
    suspend fun send(frame: JsonObject) {
        session?.send(Frame.Text(frame.toString()))
    }

    /** Owned by NodeService: bind a fresh socket and pump frames until it
     *  drops. Routes responses to pending requests and events to the flow. */
    suspend fun pump(ws: DefaultClientWebSocketSession) {
        session = ws
        status.value = "connected"
        try {
            for (frame in ws.incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                for (line in text.lineSequence()) {
                    if (line.isBlank()) continue
                    val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    route(msg)
                }
            }
        } finally {
            session = null
            status.value = "disconnected"
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private suspend fun route(msg: JsonObject) {
        val id = (msg["id"] as? JsonPrimitive)?.content
        if (id != null && pending.containsKey(id)) {
            pending[id]?.complete(msg)
            return
        }
        val params = msg["params"] as? JsonObject ?: return
        events.emit(params)
    }
}
