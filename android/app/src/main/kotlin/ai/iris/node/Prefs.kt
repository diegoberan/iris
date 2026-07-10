package ai.iris.node

import android.content.Context

/** Node connection settings. Plain SharedPreferences — the gateway password
 *  is a demo-account credential; swap for EncryptedSharedPreferences before
 *  any store distribution. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("iris_node", Context.MODE_PRIVATE)

    var gatewayUrl: String
        get() = prefs.getString("gateway_url", "https://diego.dberan.dev") ?: ""
        set(value) = prefs.edit().putString("gateway_url", value.trim()).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value.trim()).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    // Pinned sessions are client-side (matches the Desktop, which keeps them
    // in localStorage -- there is no backend pin). Stored ids, not live ids.
    fun pinnedIds(): Set<String> = prefs.getStringSet("pinned_ids", emptySet()) ?: emptySet()

    fun togglePin(id: String) {
        val next = pinnedIds().toMutableSet()
        if (!next.remove(id)) next.add(id)
        prefs.edit().putStringSet("pinned_ids", next).apply()
    }
}
