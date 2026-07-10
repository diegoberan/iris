package ai.iris.node

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class PasswordLogin(
    val provider: String = "basic",
    val username: String,
    val password: String,
    val next: String = "",
)

@Serializable
private data class LoginReply(val ok: Boolean = false)

@Serializable
private data class TicketReply(val ticket: String = "")

@Serializable
private data class MediaReply(@kotlinx.serialization.SerialName("data_url") val dataUrl: String = "")

@Serializable
private data class TranscribeRequest(
    @kotlinx.serialization.SerialName("data_url") val dataUrl: String,
    @kotlinx.serialization.SerialName("mime_type") val mimeType: String,
)

@Serializable
private data class TranscribeReply(val transcript: String = "")

/**
 * Auth + WebSocket plumbing against a gated Hermes gateway. Mirrors what the
 * Desktop app does: password-login sets the session cookies, ws-ticket mints a
 * single-use WS credential, and /api/ws?ticket= upgrades the connection. The
 * cookie jar lives in the HttpClient, so one client instance must be reused
 * across the three calls.
 */
class GatewayClient(private val baseUrl: String) {
    val http = HttpClient(CIO) {
        install(HttpCookies)
        install(WebSockets)
        // encodeDefaults: kotlinx.serialization omits default-valued fields
        // otherwise, and the login endpoint REQUIRES provider="basic" -- its
        // absence 422s, which the app can only surface as "login rejected".
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    }

    private val base = baseUrl.trimEnd('/')

    suspend fun login(username: String, password: String): Boolean {
        val reply: LoginReply = http.post("$base/auth/password-login") {
            contentType(ContentType.Application.Json)
            setBody(PasswordLogin(username = username, password = password))
        }.body()
        return reply.ok
    }

    suspend fun mintTicket(): String {
        val reply: TicketReply = http.post("$base/api/auth/ws-ticket") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.body()
        return reply.ticket
    }

    suspend fun openWs(ticket: String): DefaultClientWebSocketSession {
        val wsBase = base.replaceFirst("http", "ws") // http->ws, https->wss
        return http.webSocketSession("$wsBase/api/ws?ticket=$ticket")
    }

    /** Fetch a gateway-local media file (TTS audio, images) as a data URL.
     *  The file lives on the Brain's disk; /api/media is the authenticated
     *  bridge remote clients use to read it (same one the Desktop uses). */
    suspend fun fetchMediaDataUrl(path: String): String? {
        val reply: MediaReply = http.get("$base/api/media") {
            url { parameters.append("path", path) }
        }.body()
        return reply.dataUrl.ifBlank { null }
    }

    /** Send a recorded clip to the Brain's Whisper endpoint; returns the
     *  transcript. The same authenticated cookie the WS uses gates this. */
    suspend fun transcribe(dataUrl: String, mimeType: String): String? {
        val reply: TranscribeReply = http.post("$base/api/audio/transcribe") {
            contentType(ContentType.Application.Json)
            setBody(TranscribeRequest(dataUrl, mimeType))
        }.body()
        return reply.transcript.ifBlank { null }
    }

    fun close() = http.close()
}
