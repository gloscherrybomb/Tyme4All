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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tymewear.run.android.Graph
import com.tymewear.run.android.TymewearAccess
import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.ZoneThresholds
import com.tymewear.run.domain.relay.LanToken
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

    var endurance by remember { mutableStateOf(NumField(initial.thresholds.endurance.toString())) }
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
    var idleStopMinutes by remember { mutableStateOf(NumField(initial.idleStopMinutes.toString())) }
    var retentionDays by remember { mutableStateOf(NumField(initial.retentionDays.toString())) }
    var lanOverlay by remember { mutableStateOf(initial.lanOverlayEnabled) }
    var lanToken by remember { mutableStateOf(initial.lanToken) }

    var keyTestResult by remember { mutableStateOf<String?>(null) }
    var keyTesting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // The stored settings as they change (sign-in, sign-out, a refused sign-in), for the Tymewear card.
    val stored by Graph.settings.changes.collectAsState(initial)
    // A field is locked only when Tymewear's value replaces it for every sport.
    val thresholdsLocked = !stored.manualThresholdsInUse()
    val reserveLocked = !stored.manualReserveInUse()

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
        thresholdsCaption(stored)?.let { Text(it) }
        DoubleField("Endurance", endurance, enabled = !thresholdsLocked) { endurance = it }
        DoubleField("VT1", vt1, enabled = !thresholdsLocked) { vt1 = it }
        DoubleField("VT2", vt2, enabled = !thresholdsLocked) { vt2 = it }
        DoubleField("Top Z4", topZ4, enabled = !thresholdsLocked) { topZ4 = it }
        DoubleField("VO2max", vo2max, enabled = !thresholdsLocked) { vo2max = it }

        HorizontalDivider()

        Text("Reserve")
        reserveCaption(stored)?.let { Text(it) }
        DoubleField("Resting BR", restingBr, enabled = !reserveLocked) { restingBr = it }
        DoubleField("Max BR", maxBr, enabled = !reserveLocked) { maxBr = it }
        DoubleField("Resting HR", restingHr, enabled = !reserveLocked) { restingHr = it }
        DoubleField("Max HR", maxHr, enabled = !reserveLocked) { maxHr = it }

        HorizontalDivider()

        OutlinedTextField(value = sensorId, onValueChange = { sensorId = it }, label = { Text("Sensor id") })

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Service enabled")
            Switch(checked = serviceEnabled, onCheckedChange = { serviceEnabled = it })
        }

        HorizontalDivider()

        TymewearCard(stored) { message -> scope.launch { snackbarHostState.showSnackbar(message) } }

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
                        try {
                            val ok = withContext(Dispatchers.IO) { IntervalsClient(apiKey).verifyKey() }
                            keyTestResult = if (ok) "Key OK" else "Key rejected"
                        } catch (e: Exception) {
                            keyTestResult = "Test failed: ${e.message}"
                        } finally {
                            keyTesting = false
                        }
                    }
                },
            ) { Text("Test") }
            keyTestResult?.let { Text(it) }
        }

        HorizontalDivider()

        IntField("Stop session after no breathing data for (minutes)", idleStopMinutes) { idleStopMinutes = it }
        IntField("Retention days", retentionDays) { retentionDays = it }

        HorizontalDivider()

        Text("LAN overlay")
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Serve live values on Wi-Fi for the PC overlay", modifier = Modifier.weight(1f))
            Switch(checked = lanOverlay, onCheckedChange = {
                lanOverlay = it
                if (it && lanToken == null) lanToken = LanToken.generate()
            })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { lanToken = LanToken.generate() }) { Text("Regenerate token") }
            Text(if (lanToken == null) "No token yet" else "Token set; save to apply")
        }
        Text("The overlay URL appears on the Status tab once saved and Wi-Fi is connected.")

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
            val enduranced = endurance.text.toDoubleOrNull()
            val vt1d = vt1.text.toDoubleOrNull()
            val vt2d = vt2.text.toDoubleOrNull()
            val topZ4d = topZ4.text.toDoubleOrNull()
            val vo2maxd = vo2max.text.toDoubleOrNull()
            val restingBrd = restingBr.text.toDoubleOrNull()
            val maxBrd = maxBr.text.toDoubleOrNull()
            val restingHrd = restingHr.text.toDoubleOrNull()
            val maxHrd = maxHr.text.toDoubleOrNull()
            val idleI = idleStopMinutes.text.toIntOrNull()
            val retentionI = retentionDays.text.toIntOrNull()

            val order = ZoneThresholds.outOfOrder(listOf(enduranced, vt1d, vt2d, topZ4d, vo2maxd))
            endurance = endurance.copy(error = order[0])
            vt1 = vt1.copy(error = order[1])
            vt2 = vt2.copy(error = order[2])
            topZ4 = topZ4.copy(error = order[3])
            vo2max = vo2max.copy(error = order[4])
            val thresholdsOk = order.none { it }
            restingBr = restingBr.copy(error = restingBrd == null)
            maxBr = maxBr.copy(error = maxBrd == null)
            restingHr = restingHr.copy(error = restingHrd == null)
            maxHr = maxHr.copy(error = maxHrd == null)
            idleStopMinutes = idleStopMinutes.copy(error = idleI == null || idleI !in 1..480)
            retentionDays = retentionDays.copy(error = retentionI == null)

            if (thresholdsOk && enduranced != null && vt1d != null && vt2d != null && topZ4d != null && vo2maxd != null &&
                restingBrd != null && maxBrd != null && restingHrd != null && maxHrd != null &&
                idleI != null && idleI in 1..480 && retentionI != null
            ) {
                // Start from what is stored now, not a fresh value or the screen's first snapshot,
                // so Save keeps the Tymewear sign-in and its thresholds.
                Graph.settings.save(
                    Graph.settings.load().copy(
                        thresholds = ZoneThresholds(enduranced, vt1d, vt2d, topZ4d, vo2maxd),
                        reserve = ReserveSettings(restingBrd, maxBrd, restingHrd, maxHrd),
                        sensorId = sensorId.ifBlank { null },
                        serviceEnabled = serviceEnabled,
                        intervalsApiKey = apiKey.ifBlank { null },
                        idleStopMinutes = idleI,
                        retentionDays = retentionI,
                        lanOverlayEnabled = lanOverlay,
                        lanToken = lanToken,
                    ),
                )
            } else {
                scope.launch { snackbarHostState.showSnackbar("Fix the highlighted fields") }
            }
        }) { Text("Save") }
    }
    }
}

private const val FROM_TYMEWEAR = "From Tymewear. Switch off Use thresholds from Tymewear to edit."

/** Null when Tymewear's thresholds are not in use. */
private fun thresholdsCaption(s: Settings): String? = when {
    !(s.tymewearSignedIn && s.useTymewearThresholds) -> null
    !s.manualThresholdsInUse() -> FROM_TYMEWEAR
    s.bikeThresholds == null && s.runThresholds == null -> "Tymewear has no thresholds yet, so these apply."
    s.runThresholds == null -> "Tymewear has no Run thresholds, so these apply to runs."
    else -> "Tymewear has no Bike thresholds, so these apply to rides, other sports and the live view."
}

private fun reserveCaption(s: Settings): String? = when {
    !(s.tymewearSignedIn && s.useTymewearThresholds) -> null
    !s.manualReserveInUse() -> FROM_TYMEWEAR
    else -> "Tymewear has no resting and max values yet, so these apply."
}

/**
 * Sign-in, the thresholds Tymewear holds, and the two Tymewear switches. Every save starts from
 * what is stored at that moment, so nothing else in the settings is lost.
 */
@Composable
private fun TymewearCard(stored: Settings, showMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tymewear")
            if (!stored.tymewearSignedIn || stored.tymewearSignInRefused) {
                Text(
                    if (stored.tymewearSignInRefused) "Tymewear stopped accepting your sign-in. Sign in again."
                    else "Send your breathing to Tymewear and use your Tymewear thresholds. Your password is kept encrypted on this phone.",
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                        onClick = {
                            busy = true
                            val e = email.trim()
                            val p = password
                            password = ""
                            scope.launch {
                                val message = withContext(Dispatchers.IO) { TymewearAccess.signIn(context, e, p) }
                                busy = false
                                showMessage(message)
                            }
                        },
                    ) { Text("Sign in") }
                    if (stored.tymewearSignedIn) SignOutButton(scope)
                }
            } else {
                Text("Signed in to Tymewear")
                val lines = listOfNotNull(
                    stored.bikeThresholds?.let { thresholdsLine("Bike", it) },
                    stored.runThresholds?.let { thresholdsLine("Run", it) },
                )
                if (lines.isEmpty()) Text("No thresholds in Tymewear yet") else lines.forEach { Text(it) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Also send breathing to Tymewear", modifier = Modifier.weight(1f))
                    Switch(checked = stored.tymewearUpload, onCheckedChange = { on ->
                        Graph.settings.save(Graph.settings.load().copy(tymewearUpload = on))
                    })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Use thresholds from Tymewear", modifier = Modifier.weight(1f))
                    Switch(checked = stored.useTymewearThresholds, onCheckedChange = { on ->
                        Graph.settings.save(Graph.settings.load().copy(useTymewearThresholds = on))
                    })
                }
                SignOutButton(scope)
            }
        }
    }
}

@Composable
private fun SignOutButton(scope: kotlinx.coroutines.CoroutineScope) {
    OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { TymewearAccess.signOut() } } }) { Text("Sign out") }
}

/** "Bike: Endurance 73.2 · VT1 96 · VT2 112 · Top Z4 129.6 · VO2max 182.3" */
private fun thresholdsLine(sport: String, t: ZoneThresholds): String {
    fun n(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    return "$sport: Endurance ${n(t.endurance)} · VT1 ${n(t.vt1)} · VT2 ${n(t.vt2)} · Top Z4 ${n(t.topZ4)} · VO2max ${n(t.vo2max)}"
}

@Composable
private fun DoubleField(label: String, field: NumField, enabled: Boolean = true, onChange: (NumField) -> Unit) {
    OutlinedTextField(
        value = field.text,
        onValueChange = { onChange(NumField(it, error = false)) },
        label = { Text(label) },
        isError = field.error,
        enabled = enabled,
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
