package org.linphone.incomingcall

import android.app.Application
import org.linphone.incomingcall.bot.BotClient
import org.linphone.incomingcall.bot.BotPrefs
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.SessionStore

class IncomingCallApp : Application() {
    companion object {
        lateinit var instance: IncomingCallApp
            private set
    }

    lateinit var sessionStore: SessionStore
        private set
    lateinit var botPrefs: BotPrefs
        private set
    lateinit var botClient: BotClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        sessionStore = SessionStore(this)
        HivaGoldClient.initSession(sessionStore)
        botPrefs = BotPrefs(this)
        botClient = BotClient(botPrefs)
    }
}
