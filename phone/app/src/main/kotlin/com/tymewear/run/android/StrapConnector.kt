package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.LiveState
import com.tymewear.run.domain.Protocol
import com.tymewear.run.domain.SettingsStore
import com.tymewear.run.domain.session.SessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Keeps the strap connected whenever it is in range, feeding LiveState and the session log. */
class StrapConnector(
    context: Context,
    private val live: LiveState,
    private val sessions: SessionController,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val ble = BleManager(context)
    private var job: Job? = null
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> get() = _deviceName

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    val found = ble.scan(settings.load().sensorId).first()
                    _deviceName.value = found.name
                    Timber.i("Strap found: ${found.name} ${found.address}")
                    ble.connect(found.address)
                        .catch { e -> Timber.w(e, "connect flow failed") }
                        .collect { ev ->
                            val now = System.currentTimeMillis()
                            when (ev) {
                                is BleManager.ConnectionEvent.Connected -> { live.onConnected(); sessions.onStrap(true, now) }
                                is BleManager.ConnectionEvent.Disconnected -> { live.onDisconnected(); sessions.onStrap(false, now) }
                                is BleManager.ConnectionEvent.Subscribed -> {}
                                is BleManager.ConnectionEvent.BatteryLevel -> { live.onBattery(ev.percent); sessions.onBattery(ev.percent, now) }
                                is BleManager.ConnectionEvent.Data -> Protocol.parseNotification(ev.bytes)?.let { live.onBreath(it, now); sessions.onBreath(it, now) }
                            }
                        }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "strap loop error")
                }
                live.onDisconnected()
                sessions.onStrap(false, System.currentTimeMillis())
                delay(5_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        live.onDisconnected()
        sessions.onStrap(false, System.currentTimeMillis())
        Protocol.resetState()
    }
}
