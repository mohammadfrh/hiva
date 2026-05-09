package org.linphone.incomingcall.bot

import android.content.Context

class BotPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) {
            val normalized = if (value.endsWith("/")) value else "$value/"
            prefs.edit().putString(KEY_BACKEND_BASE_URL, normalized).apply()
        }

    var localProfileId: String
        get() = prefs.getString(KEY_LOCAL_PROFILE_ID, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID
        set(value) {
            prefs.edit().putString(KEY_LOCAL_PROFILE_ID, value).apply()
        }

    companion object {
        private const val PREFS = "python_bot_prefs"
        private const val KEY_BACKEND_BASE_URL = "backend_base_url"
        private const val KEY_LOCAL_PROFILE_ID = "local_profile_id"
        const val DEFAULT_BASE_URL = "https://demo.hivagold.org/"
        const val DEFAULT_PROFILE_ID = "baseline"
    }
}
