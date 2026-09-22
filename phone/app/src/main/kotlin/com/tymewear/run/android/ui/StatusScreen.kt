package com.tymewear.run.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tymewear.run.android.Graph
import com.tymewear.run.domain.StrapStatus
import com.tymewear.run.domain.LivePayload
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

@Composable
fun StatusScreen(
    onRequestPermissions: () -> Unit,
    onPairStrap: () -> Unit,
    onUnpairStrap: () -> Unit,
    pairedCount: Int,
    observingCount: Int,
) {
    val context = LocalContext.current
    var payload by remember { mutableStateOf<LivePayload?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val settings = Graph.settings.load()
            payload = Graph.live.payload(settings, System.currentTimeMillis())
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = payload
        Text("Status: ${p?.status ?: "unknown"}")
        Text("Battery: ${p?.batteryPct?.let { "$it%" } ?: "-"}")
        Text("VE: ${p?.ve?.format() ?: "-"} L/min")
        Text("BR: ${p?.br?.format() ?: "-"} brpm")
        Text("TV: ${p?.tv?.format() ?: "-"} L")
        Text("Zone: ${p?.zone ?: 0}")
        Text("Active session: ${p?.sessionId ?: "none"}")

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                Graph.sessions.start(System.currentTimeMillis(), "manual")
            }) { Text("Start session") }
            OutlinedButton(onClick = {
                Graph.sessions.stop(System.currentTimeMillis(), "manual")
            }) { Text("Stop session") }
        }

        HorizontalDivider()

        Text("Strap pairing")
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (!supported) {
            Text("Requires Android 12 or newer")
        } else if (pairedCount > 0 && observingCount == 0) {
            Text("Paired, but presence detection unavailable — the service will run all the time")
        } else if (pairedCount > 0) {
            Text("Paired: $pairedCount device(s)")
        } else {
            Text("Not paired — the service will run all the time")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPairStrap, enabled = supported) { Text("Pair strap") }
            if (pairedCount > 0) {
                OutlinedButton(onClick = onUnpairStrap, enabled = supported) { Text("Unpair") }
            }
        }

        HorizontalDivider()

        Text("Permissions")
        Button(onClick = onRequestPermissions) { Text("Request permissions") }

        HorizontalDivider()

        Text("Battery optimisation")
        Button(onClick = { openBatteryOptimizationSettings(context) }) { Text("Battery optimisation") }
        Text("Also exclude the Zepp app from battery optimisation, or the watch display can drop out mid-run.")

        HorizontalDivider()

        Text("PC overlay")
        val lanUrl by Graph.lanOverlayUrl.collectAsState()
        val recorderRunning by Graph.recorderRunning.collectAsState()
        val lanEnabled = Graph.settings.load().lanOverlayEnabled
        when {
            !lanEnabled -> Text("Off. Turn on \"LAN overlay\" on the Settings tab.")
            lanUrl == null && (!recorderRunning || Graph.live.status(System.currentTimeMillis()) == StrapStatus.OFF) ->
                Text("The strap service is not running. Put the strap on, or open the app with it in range, and the URL appears here.")
            lanUrl == null -> Text("Waiting for Wi-Fi. The URL appears once the phone has a Wi-Fi address.")
            else -> {
                Text(lanUrl!!)
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("overlay url", lanUrl))
                }) { Text("Copy URL") }
                QrImage(lanUrl!!, Modifier.size(220.dp))
                Text("Open this on the PC (pc/overlay.ps1) or in any browser on the same Wi-Fi.")
            }
        }
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

private fun Double.format(): String = "%.1f".format(this)
