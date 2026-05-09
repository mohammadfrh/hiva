package org.linphone.incomingcall.hiva

import android.content.Context

class SessionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS, value).apply()
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH, value).apply()
        }

    var csrfToken: String?
        get() = prefs.getString(KEY_CSRF, null)
        set(value) {
            prefs.edit().putString(KEY_CSRF, value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = !accessToken.isNullOrBlank()

    companion object {
        private const val PREFS = "hiva_gold_session"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_CSRF = "csrf"
    }
}
