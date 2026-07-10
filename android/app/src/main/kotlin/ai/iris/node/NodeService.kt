package ai.iris.node

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * The Node itself: a foreground service that keeps one WebSocket to the Brain,
 * announces this phone's capabilities (notification.send, location.current)
 * and answers act requests over the same socket. Reconnects with backoff --
 * the announce is re-sent on every (re)connection, so the Brain's routing view
 * follows this Node's real presence.
 */
class NodeService : Service() {

    companion object {
        val status = MutableStateFlow("disconnected")
        val lastEvent = MutableStateFlow("")

        private const val SERVICE_CHANNEL = "iris_node"
        private const val ALERTS_CHANNEL = "iris_alerts"
        private const val SERVICE_NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NodeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var alertId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("Connecting to Brain…"))
        scope.launch { connectionLoop() }
    }

    override fun onDestroy() {
        scope.cancel()
        status.value = "disconnected"
        super.onDestroy()
    }

    private suspend fun connectionLoop() {
        var backoffSeconds = 3L
        while (true) {
            val prefs = Prefs(this)
            val client = GatewayClient(prefs.gatewayUrl)
            try {
                status.value = "authenticating"
                check(client.login(prefs.username, prefs.password)) { "login rejected" }
                val ws = client.openWs(client.mintTicket())
                status.value = "connected"
                updateServiceNotification("Connected — body announced to the Brain")
                backoffSeconds = 3L
                announce(ws)
                consume(ws)
            } catch (error: Exception) {
                status.value = "error: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                client.close()
            }
            updateServiceNotification("Disconnected — retrying…")
            delay(backoffSeconds * 1000)
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(30)
        }
    }

    private suspend fun announce(ws: DefaultClientWebSocketSession) {
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "announce-${System.currentTimeMillis()}")
            put("method", "hermes.capabilities.announce")
            put("params", buildJsonObject {
                put("device", "android")
                put("notification", buildJsonObject { put("available", true) })
                put("location", buildJsonObject { put("available", hasLocationPermission()) })
            })
        }
        ws.send(Frame.Text(frame.toString()))
    }

    private suspend fun consume(ws: DefaultClientWebSocketSession) {
        for (frame in ws.incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            // Wire protocol is newline-delimited JSON-RPC; one WS frame may
            // carry several lines.
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val msg = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                handleEvent(ws, msg)
            }
        }
    }

    private suspend fun handleEvent(ws: DefaultClientWebSocketSession, msg: JsonObject) {
        val params = msg["params"] as? JsonObject ?: return
        val type = params["type"]?.jsonPrimitive?.content ?: return
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val requestId = payload["request_id"]?.jsonPrimitive?.content ?: ""

        when (type) {
            "notification.send.request" -> {
                val title = payload["title"]?.jsonPrimitive?.content ?: "Hermes"
                val body = payload["body"]?.jsonPrimitive?.content ?: ""
                postAlert(title, body)
                lastEvent.value = "notification: $title"
                respond(ws, requestId) { put("success", true) }
            }

            "location.current.request" -> {
                val fix = currentLocation()
                lastEvent.value = if (fix != null) "location served" else "location unavailable"
                respond(ws, requestId) {
                    if (fix != null) {
                        put("success", true)
                        put("latitude", fix.latitude)
                        put("longitude", fix.longitude)
                        put("accuracy", fix.accuracy.toDouble())
                    } else {
                        put("success", false)
                    }
                }
            }
        }
    }

    private suspend fun respond(
        ws: DefaultClientWebSocketSession,
        requestId: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        if (requestId.isEmpty()) return
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "resp-$requestId")
            put("method", "device.action.response")
            put("params", buildJsonObject {
                put("request_id", requestId)
                put("device", "android")
                extra()
            })
        }
        ws.send(Frame.Text(frame.toString()))
    }

    // ── Capability handlers ───────────────────────────────────────────

    private fun postAlert(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager().notify(alertId++, notification)
    }

    @SuppressLint("MissingPermission") // checked via hasLocationPermission()
    private suspend fun currentLocation(): android.location.Location? {
        if (!hasLocationPermission()) return null
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, mainExecutor) { location ->
                    continuation.resume(location)
                }
            }
        } ?: manager.getLastKnownLocation(provider)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // ── Notifications plumbing ────────────────────────────────────────

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "Iris Node service", NotificationManager.IMPORTANCE_MIN)
        )
        notificationManager().createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL, "Messages from the Brain", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Iris Node")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateServiceNotification(text: String) {
        notificationManager().notify(SERVICE_NOTIFICATION_ID, serviceNotification(text))
    }
}
