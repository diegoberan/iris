package ai.iris.node

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/** Pushes this phone's gateway connection to any paired Wear OS watch over
 *  the Data Layer (Bluetooth), so the watch app doesn't need its own manual
 *  credential entry when a paired phone is already connected. */
object WearSync {
    private const val PATH = "/iris/connection"

    fun push(context: Context, gatewayUrl: String, username: String, password: String) {
        if (gatewayUrl.isBlank() || username.isBlank() || password.isBlank()) return
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString("gatewayUrl", gatewayUrl)
            dataMap.putString("username", username)
            dataMap.putString("password", password)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}
