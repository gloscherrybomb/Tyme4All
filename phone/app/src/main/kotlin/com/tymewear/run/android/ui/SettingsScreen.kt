package com.tymewear.run.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tymewear.run.android.Graph
import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import com.tymewear.run.domain.sync.IntervalsClient
import com.tymewear.run.domain.sync.StreamCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NumField(val text: String, val error: Boolean = false)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember { Graph.settings.load() }

    var vt1 by remember { mutableStateOf(NumField(initial.thresholds.vt1.toString())) }
    var vt2 by remember { mutableStateOf(NumField(initial.thresholds.vt2.toString())) }
    var topZ4 by remember { mutableStateOf(NumField(initial.thresholds.topZ4.toString())) }
    var vo2max by remember { mutableStateOf(NumField(initial.thresholds.vo2max.toString())) }
    var restingBr by remember { mutableStateOf(NumField(initial.reserve.restingBr.toString())) }
    var maxBr by remember { mutableStateOf(NumField(initial.reserve.maxBr.toString())) }
    var restingHr by remember { mutableStateOf(NumField(initial.reserve.restingHr.toString())) }
    var maxHr by remember { mutableStateOf(NumField(initial.reserve.maxHr.toString())) }
    var sensorId by remember { mutableStateOf(initial.sensorId ?: "") }
    var serviceEnabled by remember { mutableStateOf(initial.serviceEnabled) }
    var apiKey by remember { mutableStateOf(initial.intervalsApiKey ?: "") }
    var fallbackStopMinutes by remember { mutableStateOf(NumField(initial.fallbackStopMinutes.toString())) }
    var retentionDays by remember { mutableStateOf(NumField(initial.retentionDays.toString())) }

    var keyTestResult by remember { mutableStateOf<String?>(null) }
    var keyTesting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scaffoldPadding ->
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(scaffoldPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Zone thresholds")
        DoubleField("VT1", vt1) { vt1 = it }
        DoubleField("VT2", vt2) { vt2 = it }
        DoubleField("Top Z4", topZ4) { topZ4 = it }
        DoubleField("VO2max", vo2max) { vo2max = it }

        HorizontalDivider()

        Text("Reserve")
        DoubleField("Resting BR", restingBr) { restingBr = it }
        DoubleField("Max BR", maxBr) { maxBr = it }
        DoubleField("Resting HR", restingHr) { restingHr = it }
        DoubleField("Max HR", maxHr) { maxHr = it }

        HorizontalDivider()

        OutlinedTextField(value = sensorId, onValueChange = { sensorId = it }, label = { Text("Sensor id") })

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Service enabled")
            Switch(checked = serviceEnabled, onCheckedChange = { serviceEnabled = it })
        }

        HorizontalDivider()

        Text("Intervals.icu")
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it; keyTestResult = null }, label = { Text("API key") })
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !keyTesting && apiKey.isNotBlank(),
                onClick = {
                    keyTesting = true
                    keyTestResult = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { IntervalsClient(apiKey).verifyKey() }
                        keyTestResult = if (ok) "Key OK" else "Key rejected"
                        keyTesting = false
                    }
                },
            ) { Text("Test") }
            keyTestResult?.let { Text(it) }
        }

        HorizontalDivider()

        IntField("Fallback stop minutes", fallbackStopMinutes) { fallbackStopMinutes = it }
        IntField("Retention days", retentionDays) { retentionDays = it }

        HorizontalDivider()

        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Custom stream codes")
                Text("${StreamCodes.VE}  (L/min)")
                Text("${StreamCodes.BR}  (brpm)")
                Text("${StreamCodes.TV}  (L)")
                Text("${StreamCodes.IE}  (ratio)")
                Text("${StreamCodes.ZONE}  (zone)")
                Text("${StreamCodes.BRR}  (%)")
                Text("${StreamCodes.MI}  (%)")
                OutlinedButton(onClick = { copyStreamCodes(context) }) { Text("Copy codes") }
            }
        }

        HorizontalDivider()

        Button(onClick = {
            val vt1d = vt1.text.toDoubleOrNull()
            val vt2d = vt2.text.toDoubleOrNull()
            val topZ4d = topZ4.text.toDoubleOrNull()
            val vo2maxd = vo2max.text.toDoubleOrNull()
            val restingBrd = restingBr.text.toDoubleOrNull()
            val maxBrd = maxBr.text.toDoubleOrNull()
            val restingHrd = restingHr.text.toDoubleOrNull()
            val maxHrd = maxHr.text.toDoubleOrNull()
            val fallbackI = fallbackStopMinutes.text.toIntOrNull()
            val retentionI = retentionDays.text.toIntOrNull()

            vt1 = vt1.copy(error = vt1d == null)
            vt2 = vt2.copy(error = vt2d == null)
            topZ4 = topZ4.copy(error = topZ4d == null)
            vo2max = vo2max.copy(error = vo2maxd == null)
            restingBr = restingBr.copy(error = restingBrd == null)
            maxBr = maxBr.copy(error = maxBrd == null)
            restingHr = restingHr.copy(error = restingHrd == null)
            maxHr = maxHr.copy(error = maxHrd == null)
            fallbackStopMinutes = fallbackStopMinutes.copy(error = fallbackI == null)
            retentionDays = retentionDays.copy(error = retentionI == null)

            if (vt1d != null && vt2d != null && topZ4d != null && vo2maxd != null &&
                restingBrd != null && maxBrd != null && restingHrd != null && maxHrd != null &&
                fallbackI != null && retentionI != null
            ) {
                Graph.settings.save(
                    Settings(
                        thresholds = ZoneThresholds(vt1d, vt2d, topZ4d, vo2maxd),
                        reserve = ReserveSettings(restingBrd, maxBrd, restingHrd, maxHrd),
                        sensorId = sensorId.ifBlank { null },
                        serviceEnabled = serviceEnabled,
                        intervalsApiKey = apiKey.ifBlank { null },
                        fallbackStopMinutes = fallbackI,
                        retentionDays = retentionI,
                    ),
                )
            } else {
                scope.launch { snackbarHostState.showSnackbar("Fix the highlighted fields") }
            }
        }) { Text("Save") }
    }
    }
}

@Composable
private fun DoubleField(label: String, field: NumField, onChange: (NumField) -> Unit) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { onChange(NumField(it, error = false)) },
        label = { Text(label) },
        isError = field.error,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntField(label: String, field: NumField, onChange: (NumField) -> Unit) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { onChange(NumField(it, error = false)) },
        label = { Text(label) },
        isError = field.error,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun copyStreamCodes(context: Context) {
    val text = StreamCodes.ALL.joinToString("\n")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("stream codes", text))
}
