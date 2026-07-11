package ai.iris.node

import android.content.Context

/** Node connection settings. Plain SharedPreferences — the gateway password
 *  is a demo-account credential; swap for EncryptedSharedPreferences before
 *  any store distribution. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("iris_node", Context.MODE_PRIVATE)

    var gatewayUrl: String
        // No baked-in default: the gateway URL is per-user (first-run Settings
        // screen collects it) and a personal hostname must not ship in the
        // public repo/APK.
        get() = prefs.getString("gateway_url", "") ?: ""
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

    // Hidden models (client-side visibility, like the Desktop's Edit Models).
    // Keys are "slug:model". Empty set = show everything.
    fun hiddenModels(): Set<String> = prefs.getStringSet("hidden_models", emptySet()) ?: emptySet()

    fun toggleModelHidden(key: String) {
        val next = hiddenModels().toMutableSet()
        if (!next.remove(key)) next.add(key)
        prefs.edit().putStringSet("hidden_models", next).apply()
    }

    // Active profile (default = the gateway's own tenant).
    var activeProfile: String
        get() = prefs.getString("active_profile", "default") ?: "default"
        set(value) = prefs.edit().putString("active_profile", value).apply()
}
