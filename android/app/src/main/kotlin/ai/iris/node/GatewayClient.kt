package ai.iris.node

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
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
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
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

    fun close() = http.close()
}
