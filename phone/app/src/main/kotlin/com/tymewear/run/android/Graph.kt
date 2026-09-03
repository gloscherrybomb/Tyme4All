package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.session.SessionController
import com.tymewear.run.domain.session.SessionStore
import java.io.File

object Graph {
    lateinit var settings: SettingsStore
    lateinit var live: LiveState
    lateinit var sessionStore: SessionStore
    lateinit var sessions: SessionController

    fun init(context: Context) {
        if (this::settings.isInitialized) return
        settings = PrefsSettingsStore(context)
        live = LiveState()
        sessionStore = SessionStore(File(context.filesDir, "sessions"))
        sessions = SessionController(sessionStore, fallbackDisconnectedMs = settings.load().fallbackStopMinutes * 60_000L)
        sessions.listener = { live.setSessionId(it) }
        live.setServiceEnabled(settings.load().serviceEnabled)
    }
}
