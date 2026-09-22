package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.StrapPresence
import com.tymewear.run.domain.session.SessionController
import com.tymewear.run.domain.session.SessionStore
import java.io.File

object Graph {
    lateinit var settings: SettingsStore
    lateinit var live: LiveState
    lateinit var sessionStore: SessionStore
    lateinit var sessions: SessionController

    /** Last known presence of the paired strap, from Companion Device Manager
     *  callbacks in StrapPresenceService. UNKNOWN when unpaired or before the first
     *  callback fires. Process-wide so RecorderService's housekeeping loop can read it
     *  without a direct dependency on the CDM service. */
    @Volatile
    var strapPresence: StrapPresence = StrapPresence.UNKNOWN

    /** How many paired associations `CompanionAssociation.startObserving` last
     *  succeeded in starting presence observation for. Compared against the paired
     *  count in the UI so a platform-level failure (e.g. presence observation silently
     *  not engaging) surfaces instead of quietly degrading to always-on. */
    @Volatile
    var observingCount: Int = 0

    /** True between RecorderService.onCreate and onDestroy, so the UI can tell "service not
     *  running" apart from "running but no Wi-Fi address yet". */
    val recorderRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Overlay URL of the LAN relay while it is bound, else null. Written by LanRelayManager. */
    val lanOverlayUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun init(context: Context) {
        if (this::settings.isInitialized) return
        settings = PrefsSettingsStore(context)
        live = LiveState()
        sessionStore = SessionStore(File(context.filesDir, "sessions"))
        sessions = SessionController(sessionStore, idleStopMs = settings.load().idleStopMinutes * 60_000L)
        sessions.listener = { live.setSessionId(it) }
        live.setServiceEnabled(settings.load().serviceEnabled)
    }
}
