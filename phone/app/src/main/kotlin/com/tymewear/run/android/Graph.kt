package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.StrapPresence
import com.tymewear.run.domain.session.SessionController
import com.tymewear.run.domain.session.SessionStore
import com.tymewear.run.domain.tymewear.CredentialStore
import java.io.File

object Graph {
    lateinit var settings: SettingsStore
    lateinit var live: LiveState
    lateinit var sessionStore: SessionStore
    lateinit var sessions: SessionController
    private lateinit var appContext: Context

    /** Opened on first use, off the app's start-up path; never left unset (see EncryptedCredentialStore.open). */
    val tymewearCredentials: CredentialStore by lazy { EncryptedCredentialStore.open(appContext) }

    /** Last known presence of the paired strap, from Companion Device Manager
     *  callbacks in StrapPresenceService. UNKNOWN when unpaired or before the first
     *  callback fires. Process-wide so RecorderService's housekeeping loop can read it
     *  without a direct dependency on the CDM service. */
    @Volatile
    var strapPresence: StrapPresence = StrapPresence.UNKNOWN

    /** How many paired associations `CompanionAssociation.startObserving` last
     *  succeeded in starting presence observation for; null until the service (or a
     *  pairing) has reported, and while the service is switched off. Observed by the UI
     *  and compared against the paired count, so a platform-level failure (e.g. presence
     *  observation silently not engaging) surfaces instead of quietly degrading to always-on. */
    val observingCount = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)

    /** Whether a Companion Device Manager association exists. Set when the service starts and on
     *  pair and unpair, so the housekeeping loop need not ask the system every tick. */
    @Volatile
    var strapPaired: Boolean = false

    /** True between RecorderService.onCreate and onDestroy, so the UI can tell "service not
     *  running" apart from "running but no Wi-Fi address yet". */
    val recorderRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Overlay URL of the LAN relay while it is bound, else null. Written by LanRelayManager. */
    val lanOverlayUrl = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun init(context: Context) {
        if (this::settings.isInitialized) return
        appContext = context.applicationContext
        settings = PrefsSettingsStore(context)
        live = LiveState()
        sessionStore = SessionStore(File(context.filesDir, "sessions"))
        sessions = SessionController(sessionStore, idleStopMs = settings.load().idleStopMinutes * 60_000L)
        sessions.listener = { live.setSessionId(it) }
        live.setServiceEnabled(settings.load().serviceEnabled)
    }
}
