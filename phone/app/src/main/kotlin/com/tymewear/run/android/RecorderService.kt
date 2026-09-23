package com.tymewear.run.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tymewear.run.BuildConfig
import com.tymewear.run.domain.Constants
import com.tymewear.run.domain.NotificationPolicy
import com.tymewear.run.domain.ServiceLifecycle
import com.tymewear.run.domain.relay.RelayServer
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
    private var recordingShownFor: String? = null
    private var recordingStartMs = 0L
    private var lastNotificationPostMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Graph.recorderRunning.value = true
        Notifications.ensureChannels(this)
        Graph.sessions.recoverOnStartup(System.currentTimeMillis())
        connector = StrapConnector(this, Graph.live, Graph.sessions, Graph.settings, scope)
        lanRelay = LanRelayManager(this) { host, token ->
            RelayServer(host, Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME, token = token)
        }
        if (CompanionAssociation.isSupported(this)) {
            Graph.observingCount = CompanionAssociation.startObserving(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.serviceNotification(this, "Starting")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(Notifications.ID_SERVICE, n)
        } catch (e: Exception) {
            Timber.w(e, "startForeground failed; continuing without foreground promotion")
        }

        if (relay == null) {
            try {
                relay = RelayServer("127.0.0.1", Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME)
                    .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            } catch (e: java.io.IOException) {
                Timber.e(e, "relay failed to bind")
                relay = null
            }
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
                        Graph.observingCount = 0
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
            if (relay == null) {
                try {
                    relay = RelayServer("127.0.0.1", Constants.RELAY_PORT, Graph.live, Graph.settings, Graph.sessions, version = BuildConfig.VERSION_NAME)
                        .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                } catch (e: java.io.IOException) {
                    Timber.e(e, "relay failed to bind")
                    relay = null
                }
            }
            val status = Graph.live.status(now)
            val session = Graph.sessions.activeSessionId
            // One persistent notification: strap state normally, recording state while a session is
            // open. The session start is read from disk once per session; the VE refreshes every 30 s.
            if (session != null && session != recordingShownFor) {
                recordingStartMs = withContext(Dispatchers.IO) { Graph.sessionStore.meta(session)?.startMs } ?: now
            }
            recordingShownFor = session
            val ve = if (session != null) Graph.live.payload(Graph.settings.load(), now).ve else null
            val metas = withContext(Dispatchers.IO) { Graph.sessionStore.list() }
            // Intervals.icu waits drive the text and the Discard action; a pending Tymewear upload also keeps the service alive.
            val syncPending = SyncScheduler.anyPending(metas, now)
            val keepAlive = SyncScheduler.anyPending(metas, now, tymewear = Graph.settings.load().tymewearActive)
            val text = NotificationPolicy.serviceText(status, if (session != null) recordingStartMs else null, ve, syncPending, relay == null, ZoneId.systemDefault())
            // While recording, only the VE figure changes tick to tick; hold those updates to one per 30 s.
            val veChurn = session != null && lastNotificationText?.startsWith("Recording") == true &&
                now - lastNotificationPostMs < NotificationPolicy.RECORDING_REFRESH_MS
            if (text != lastNotificationText && !veChurn) {
                lastNotificationText = text
                lastNotificationPostMs = now
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(Notifications.ID_SERVICE, Notifications.serviceNotification(this, text, discardable = session == null && syncPending))
            }

            if (now - lastSyncMs >= 60_000) { lastSyncMs = now; runSyncPass(now) }
            if (now - lastPruneMs >= 86_400_000) { lastPruneMs = now; Graph.sessionStore.prune(now, Graph.settings.load().retentionDays) }

            val settings = Graph.settings.load()
            if (ServiceLifecycle.shouldStop(Graph.strapPresence, session != null, settings.serviceEnabled, keepAlive)) {
                Timber.i("Stopping service: presence=${Graph.strapPresence}, session=$session, serviceEnabled=${settings.serviceEnabled}, syncPending=$keepAlive")
                stopSelf()
                return
            }

            delay(10_000)
        }
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
                        if (engine.syncTymewear(m.id, settings, now) == TymewearOutcome.Uploaded) {
                            Notifications.tymewearUploaded(this@RecorderService, m.id, "Intervals.icu activity ${m.activityId}")
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
        fun start(ctx: Context) {
            val i = Intent(ctx, RecorderService::class.java)
            try { ctx.startForegroundService(i) } catch (e: Exception) { Timber.w(e, "startForegroundService rejected") }
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RecorderService::class.java))
    }
}
