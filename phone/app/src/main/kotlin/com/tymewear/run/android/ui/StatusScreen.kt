package com.tymewear.run.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tymewear.run.android.Graph
import com.tymewear.run.domain.LivePayload
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect

@Composable
fun StatusScreen(onRequestPermissions: () -> Unit) {
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

        Divider()

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                Graph.sessions.start(System.currentTimeMillis(), "manual")
            }) { Text("Start session") }
            OutlinedButton(onClick = {
                Graph.sessions.stop(System.currentTimeMillis(), "manual")
            }) { Text("Stop session") }
        }

        Divider()

        Text("Permissions")
        Button(onClick = onRequestPermissions) { Text("Request permissions") }

        Divider()

        Text("Battery optimisation")
        Button(onClick = { openBatteryOptimizationSettings(context) }) { Text("Battery optimisation") }
        Text("Also exclude the Zepp app from battery optimisation, or the watch display can drop out mid-run.")
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}

private fun Double.format(): String = "%.1f".format(this)
