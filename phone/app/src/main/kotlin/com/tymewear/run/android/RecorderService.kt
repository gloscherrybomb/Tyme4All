package com.tymewear.run.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tymewear.run.BuildConfig
import com.tymewear.run.domain.AppOpenAction
import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.NotificationPolicy
import com.tymewear.run.domain.PresenceWatch
import com.tymewear.run.domain.ServiceLifecycle
import com.tymewear.run.domain.StrapStatus
import com.tymewear.run.domain.relay.RelayServer
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.sync.IntervalsClient
import com.tymewear.run.domain.sync.SyncEngine
import com.tymewear.run.domain.sync.SyncOutcome
import com.tymewear.run.domain.sync.SyncScheduler
import com.tymewear.run.domain.tymewear.TymewearOutcome
import fi.iki.elonen.NanoHTTPD
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var connector: StrapConnector
    private lateinit var lanRelay: LanRelayManager
    private var relay: RelayServer? = null
    private var lastPruneMs = 0L
    private var settingsJob: Job? = null
    private var housekeepingJob: Job? = null
    private var lastNotificationText: String? = null
    private var lastNotificationDiscardable = false
    private var recordingShownFor: String? = null
    private var recordingStartMs = 0L
    private var lastNotificationPostMs = 0L
    /** When the strap was last seen connected, or the service started if it has not been since. */
    private var strapSeenMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.recorderRunning.value = true
        strapSeenMs = System.currentTimeMillis()
        Notifications.ensureChannels(this)
        Graph.sessions.recoverOnStartup(System.currentTimeMillis())
        connector = StrapConnector(this, Graph.live, Graph.sessions, Graph.settings, scope)
        lanRelay = LanRelayManager(this) { host, token ->
            RelayServer(host, Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME, token = token)
        }
        Graph.strapPaired = CompanionAssociation.isSupported(this) && CompanionAssociation.associations(this).isNotEmpty()
        if (CompanionAssociation.isSupported(this)) {
            Graph.observingCount.value = CompanionAssociation.startObserving(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start (app resume, sign-in, strap appeared, sticky restart) restarts the presence grace period.
        strapSeenMs = System.currentTimeMillis()
        startRelayIfNeeded()
        // Post the notification the housekeeping loop would post now, and remember it, so the two
        // never disagree: the loop only re-posts when its text changes. On this service's first
        // start that is computed here (a few small files); after that it is the loop's last text.
        val now = System.currentTimeMillis()
        if (lastNotificationText == null) {
            val session = Graph.sessions.activeSessionId
            if (session != null) {
                recordingStartMs = Graph.sessionStore.meta(session)?.startMs ?: now
                recordingShownFor = session
            }
            val metas = try { Graph.sessionStore.list() } catch (e: Exception) { emptyList() }
            val (text, discardable) = serviceState(now, session, metas)
            lastNotificationText = text
            lastNotificationDiscardable = discardable
            lastNotificationPostMs = now
        }
        val n = Notifications.serviceNotification(this, lastNotificationText!!, discardable = lastNotificationDiscardable)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(Notifications.ID_SERVICE, n)
        } catch (e: Exception) {
            Timber.w(e, "startForeground failed; continuing without foreground promotion")
        }

        if (settingsJob?.isActive != true) {
            settingsJob = scope.launch {
                Graph.settings.changes.collect { s ->
                    Graph.live.setServiceEnabled(s.serviceEnabled)
                    Graph.sessions.idleStopMs = s.idleStopMinutes * 60_000L
                    lanRelay.apply(s.lanOverlayEnabled, s.lanToken)
                    if (s.serviceEnabled) {
                        connector.start()
                    } else {
                        connector.stop()
                        CompanionAssociation.stopObserving(this@RecorderService)
                        Graph.observingCount.value = null
                        stopSelf()
                    }
                }
            }
        }
        if (housekeepingJob?.isActive != true) {
            housekeepingJob = scope.launch { housekeeping() }
        }
        return START_STICKY
    }

    private suspend fun housekeeping() {
        var lastSyncMs = 0L
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            Graph.sessions.tick(now)?.let { Timber.i("Session closed: $it") }
            startRelayIfNeeded()
            val status = Graph.live.status(now)
            val strapConnected = status == StrapStatus.CONNECTED || status == StrapStatus.STALE
            if (strapConnected) strapSeenMs = now
            val session = Graph.sessions.activeSessionId
            // One persistent notification: strap state normally, recording state while a session is
            // open. The session start is read from disk once per session; the VE refreshes every 30 s.
            if (session != null && session != recordingShownFor) {
                recordingStartMs = withContext(Dispatchers.IO) { Graph.sessionStore.meta(session)?.startMs } ?: now
            }
            recordingShownFor = session
            val metas = withContext(Dispatchers.IO) { Graph.sessionStore.list() }
            // A pending Tymewear upload also keeps the service alive; only Intervals.icu waits show in the notification.
            val keepAlive = SyncScheduler.anyPending(metas, now, tymewear = Graph.settings.load().tymewearActive)
            val (text, discardable) = serviceState(now, session, metas)
            // While recording, only the VE figure changes tick to tick; hold those updates to one per 30 s.
            val veChurn = session != null && lastNotificationText?.startsWith("Recording") == true &&
                now - lastNotificationPostMs < NotificationPolicy.RECORDING_REFRESH_MS
            if (text != lastNotificationText && !veChurn) {
                lastNotificationText = text
                lastNotificationDiscardable = discardable
                lastNotificationPostMs = now
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, text, discardable = discardable))
            }

            if (now - lastSyncMs >= 60_000) { lastSyncMs = now; runSyncPass(now) }
            if (now - lastPruneMs >= 86_400_000) { lastPruneMs = now; Graph.sessionStore.prune(now, Graph.settings.load().retentionDays) }

            val settings = Graph.settings.load()
            // Paired and observed, the service may stop once the strap has been gone a while; CDM starts it again when the strap appears.
            val watch = PresenceWatch(
                paired = Graph.strapPaired,
                observingCount = Graph.observingCount.value ?: 0,
                strapConnected = strapConnected,
                msSinceStrapSeen = now - strapSeenMs,
            )
            if (ServiceLifecycle.shouldStop(Graph.strapPresence, session != null, settings.serviceEnabled, keepAlive, watch)) {
                Timber.i("Stopping service: presence=${Graph.strapPresence}, session=$session, serviceEnabled=${settings.serviceEnabled}, syncPending=$keepAlive, watch=$watch")
                stopSelf()
                return
            }

            delay(10_000)
        }
    }

    private fun startRelayIfNeeded() {
        if (relay != null) return
        try {
            relay = RelayServer("127.0.0.1", Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME)
                .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        } catch (e: java.io.IOException) {
            Timber.e(e, "relay failed to bind")
            relay = null
        }
    }

    /**
     * The service notification's text and whether it carries the Discard action. Intervals.icu waits
     * drive both. [recordingStartMs] must already be read for an open [session].
     */
    private fun serviceState(now: Long, session: String?, metas: List<SessionMeta>): Pair<String, Boolean> {
        val ve = if (session != null) Graph.live.payload(Graph.settings.load(), now).ve else null
        val syncPending = SyncScheduler.anyPending(metas, now)
        val text = NotificationPolicy.serviceText(Graph.live.status(now), if (session != null) recordingStartMs else null, ve, syncPending, relay == null, ZoneId.systemDefault())
        return text to (session == null && syncPending)
    }

    private suspend fun runSyncPass(now: Long) = withContext(Dispatchers.IO) {
        try {
            val settings = Graph.settings.load()
            val metas = Graph.sessionStore.list()
            for (m in SyncScheduler.expired(metas, now)) {
                Graph.sessionStore.updateMeta(m.id) { it.copy(syncState = "unmatched", syncMessage = "no Intervals.icu activity overlapped this session within 6 hours") }
                if (NotificationPolicy.notifyUnmatched(m)) Notifications.unmatched(this@RecorderService, m.id)
            }
            // Runs whether or not Tymewear is on (or a key is set), so a session does not stay pending after a sign-out or refusal.
            for (m in SyncScheduler.expiredTymewear(metas, now)) {
                Graph.sessionStore.updateMeta(m.id) { it.copy(tymewearState = "failed", tymewearMessage = "Tymewear never showed the activity", tymewearAttemptMs = now) }
            }
            val key = settings.intervalsApiKey ?: return@withContext
            // The Tymewear step is present only while signed in, switched on and not refused; a refusal stops it (TymewearAccess).
            val engine = TymewearAccess.engine(this@RecorderService, IntervalsClient(key), settings)
            for (m in SyncScheduler.due(metas, now)) {
                try {
                    when (val out = engine.sync(m.id, settings, now)) {
                        is SyncOutcome.Synced -> Notifications.synced(this@RecorderService, m.id, out.activityId, out.activityLabel, out.tymewear)
                        is SyncOutcome.Failed -> Notifications.syncFailed(this@RecorderService, m.id, out.message)
                        else -> {}
                    }
                } catch (e: Exception) {
                    Timber.w(e, "sync pass failed")
                }
            }
            if (settings.tymewearActive) {
                // Re-read: the loop above may have just synced sessions and set their Tymewear state.
                val after = Graph.sessionStore.list()
                for (m in SyncScheduler.dueTymewear(after, now)) {
                    if (!Graph.settings.load().tymewearActive) break
                    try {
                        val retry = engine.syncTymewear(m.id, settings, now)
                        if (retry?.outcome == TymewearOutcome.Uploaded) {
                            Notifications.tymewearUploaded(this@RecorderService, m.id, retry.activityLabel)
                        }
                    } catch (e: Exception) {
                        Timber.w("Tymewear retry failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "sync pass failed")
        }
    }

    override fun onDestroy() {
        connector.stop()
        lanRelay.stop()
        relay?.stop(); relay = null
        scope.cancel()
        Graph.recorderRunning.value = false
        super.onDestroy()
    }

    companion object {
        /**
         * The permission the connected-device foreground service needs: without it startForeground
         * throws and the app is killed for not calling it. BLUETOOTH_CONNECT from Android 12,
         * ACCESS_FINE_LOCATION before.
         */
        fun hasConnectPermission(ctx: Context): Boolean {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.Manifest.permission.BLUETOOTH_CONNECT
            else android.Manifest.permission.ACCESS_FINE_LOCATION
            return androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        /** Starts the service, only when [hasConnectPermission] holds. Every caller goes through here. */
        fun start(ctx: Context) {
            if (!hasConnectPermission(ctx)) {
                Timber.w("Not starting the service: Bluetooth permission not granted")
                return
            }
            val i = Intent(ctx, RecorderService::class.java)
            try { ctx.startForegroundService(i) } catch (e: Exception) { Timber.w(e, "startForegroundService rejected") }
        }
        /**
         * What opening the app (or signing in) should do, from ServiceLifecycle.onAppOpen. Starts
         * presence observation first (a no-op when already observing) and reports the count.
         * [canScan]: the caller may run the short foreground scan. Reads the session files: call
         * off the main thread.
         */
        fun appOpenAction(ctx: Context, canScan: Boolean, scanRunning: Boolean): AppOpenAction {
            val supported = CompanionAssociation.isSupported(ctx)
            val paired = supported && CompanionAssociation.associations(ctx).isNotEmpty()
            Graph.strapPaired = paired
            val observing = if (paired) CompanionAssociation.startObserving(ctx).also { Graph.observingCount.value = it } > 0 else false
            val now = System.currentTimeMillis()
            val metas = try {
                Graph.sessionStore.list()
            } catch (e: Exception) {
                Timber.w("Could not read sessions: ${e.javaClass.simpleName}")
                null
            }
            // Unreadable sessions: start, rather than risk missing a sync.
            val pending = metas == null || SyncScheduler.anyPending(metas, now, tymewear = Graph.settings.load().tymewearActive)
            val sessionOpen = ServiceLifecycle.sessionOpen(Graph.sessions.activeSessionId, metas.orEmpty())
            return ServiceLifecycle.onAppOpen(paired, observing, Graph.strapPresence, sessionOpen, pending, canScan, scanRunning)
        }

        /** Starts the service when it has work (see [appOpenAction]); never scans. Call off the main thread. */
        fun startIfNeeded(ctx: Context) {
            if (appOpenAction(ctx, canScan = false, scanRunning = false) == AppOpenAction.START) start(ctx)
            else Timber.i("Not starting the service: strap not in range and nothing pending")
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RecorderService::class.java))
    }
}
