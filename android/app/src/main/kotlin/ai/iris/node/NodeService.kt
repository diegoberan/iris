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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * The Node's lifecycle owner: keeps the shared GatewayConnection alive
 * (auth -> ws-ticket -> socket -> announce, reconnect with backoff) and
 * answers the device act requests (notification.send / location.current)
 * that arrive on it. The chat UI rides the same connection -- see
 * GatewayConnection for why there is exactly one socket.
 */
class NodeService : Service() {

    companion object {
        val status get() = GatewayConnection.status
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
    private var alertId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("Connecting to Brain…"))
        scope.launch { connectionLoop() }
        scope.launch { GatewayConnection.events.collect { handleEvent(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        GatewayConnection.status.value = "disconnected"
        super.onDestroy()
    }

    private suspend fun connectionLoop() {
        var backoffSeconds = 3L
        while (true) {
            val prefs = Prefs(this)
            val client = GatewayClient(prefs.gatewayUrl)
            try {
                GatewayConnection.status.value = "authenticating"
                check(client.login(prefs.username, prefs.password)) { "login rejected" }
                val ws = client.openWs(client.mintTicket())
                updateServiceNotification("Connected — body announced to the Brain")
                backoffSeconds = 3L
                GatewayConnection.rest = client
                scope.launch { announce() }
                GatewayConnection.pump(ws) // suspends until the socket drops
            } catch (error: Exception) {
                GatewayConnection.status.value = "error: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                GatewayConnection.rest = null
                client.close()
            }
            updateServiceNotification("Disconnected — retrying…")
            delay(backoffSeconds * 1000)
            backoffSeconds = (backoffSeconds * 2).coerceAtMost(30)
        }
    }

    private suspend fun announce() {
        // Give pump() a beat to mark the socket live before announcing.
        withTimeoutOrNull(3_000) {
            while (!GatewayConnection.isConnected) delay(50)
        }
        GatewayConnection.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "announce-${System.currentTimeMillis()}")
            put("method", "hermes.capabilities.announce")
            put("params", buildJsonObject {
                put("device", "android")
                put("notification", buildJsonObject { put("available", true) })
                put("location", buildJsonObject { put("available", hasLocationPermission()) })
            })
        })
    }

    private suspend fun handleEvent(params: JsonObject) {
        val type = params["type"]?.jsonPrimitive?.content ?: return
        val payload = params["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val requestId = payload["request_id"]?.jsonPrimitive?.content ?: ""

        when (type) {
            "notification.send.request" -> {
                val title = payload["title"]?.jsonPrimitive?.content ?: "Hermes"
                val body = payload["body"]?.jsonPrimitive?.content ?: ""
                postAlert(title, body)
                lastEvent.value = "notification: $title"
                respond(requestId) { put("success", true) }
            }

            "location.current.request" -> {
                val fix = currentLocation()
                lastEvent.value = if (fix != null) "location served" else "location unavailable"
                respond(requestId) {
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
        requestId: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        if (requestId.isEmpty()) return
        GatewayConnection.send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "resp-$requestId")
            put("method", "device.action.response")
            put("params", buildJsonObject {
                put("request_id", requestId)
                put("device", "android")
                extra()
            })
        })
    }

    // ── Capability handlers ───────────────────────────────────────────

    private fun postAlert(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sil)
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
            .setSmallIcon(R.drawable.ic_stat_sil)
            .setContentTitle("Iris Node")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateServiceNotification(text: String) {
        notificationManager().notify(SERVICE_NOTIFICATION_ID, serviceNotification(text))
    }
}
