package com.tymewear.run.android.ui

import android.Manifest
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.tymewear.run.android.BleManager
import com.tymewear.run.android.CompanionAssociation
import com.tymewear.run.domain.AppOpenAction
import com.tymewear.run.domain.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.tymewear.run.android.Graph
import com.tymewear.run.android.RecorderService
import com.tymewear.run.android.TymewearAccess
import com.tymewear.run.domain.StrapPresence
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) startRecorderServiceIfAllowed()
        }

    private val associationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) onAssociationCreated()
        }

    var pairedCount = mutableStateOf(0)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRecorderServiceIfAllowed()
        refreshPairedCount()
        // Tymewear's thresholds are read once per launch, not on rotation (and after each sign-in), never by the sync pass.
        if (savedInstanceState == null) lifecycleScope.launch(Dispatchers.IO) { TymewearAccess.refreshProfile(applicationContext) }

        setContent {
            Tyme4AllTheme {
                Surface {
                    MainScreen(
                        onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                        onPairStrap = { pairStrap() },
                        onUnpairStrap = { unpairStrap() },
                        pairedCount = pairedCount.value,
                    )
                }
            }
        }
    }

    private fun pairStrap() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = getSystemService(CompanionDeviceManager::class.java) ?: return
        mgr.associate(
            CompanionAssociation.associationRequest(),
            mainExecutor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
                override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                    onAssociationCreated()
                }
                override fun onFailure(error: CharSequence?) {
                    Timber.w("companion association failed: $error")
                }
            },
        )
    }

    private fun onAssociationCreated() {
        Graph.observingCount.value = CompanionAssociation.startObserving(this)
        RecorderService.start(this)
        refreshPairedCount()
    }

    private fun unpairStrap() {
        CompanionAssociation.stopObserving(this)
        CompanionAssociation.disassociateAll(this)
        Graph.observingCount.value = 0
        // Unpairing must not leave a stale AWAY reading behind: with no association left
        // to report presence, the housekeeping loop should treat the strap the same as
        // "never paired" (UNKNOWN), not stop the service on an answer that can never update.
        Graph.strapPresence = StrapPresence.UNKNOWN
        refreshPairedCount()
    }

    private fun refreshPairedCount() {
        pairedCount.value = if (CompanionAssociation.isSupported(this)) CompanionAssociation.associations(this).size else 0
        Graph.strapPaired = pairedCount.value > 0
    }

    override fun onResume() {
        super.onResume()
        startRecorderServiceIfAllowed()
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    /**
     * On app open and after the permission dialog: start the service when it has work, else look
     * for the strap with a short scan while the app is in the foreground (RecorderService.appOpenAction).
     */
    private fun startRecorderServiceIfAllowed() {
        val app = applicationContext
        lifecycleScope.launch {
            val action = withContext(Dispatchers.IO) {
                // A running service is already looking for the strap itself.
                RecorderService.appOpenAction(
                    app,
                    canScan = hasScanPermission() && RecorderService.hasConnectPermission(app),
                    scanRunning = strapScan?.isActive == true || Graph.recorderRunning.value,
                )
            }
            when (action) {
                // Decided off the main thread: the app may have left the foreground since (onResume decides again).
                AppOpenAction.START -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) RecorderService.start(app)
                AppOpenAction.SCAN -> if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) scanForStrap()
                AppOpenAction.NOTHING -> {}
            }
        }
    }

    /** The foreground strap scan, while it runs. Cancelled in onPause, so it never runs in the background. */
    private var strapScan: Job? = null

    /**
     * Scans for up to Constants.APP_OPEN_SCAN_MS for the paired strap (its MAC when known, else the
     * service's name and sensor id match) and starts the service if it is found. No notification.
     */
    private fun scanForStrap() {
        if (strapScan?.isActive == true) return
        val app = applicationContext
        strapScan = lifecycleScope.launch {
            // The service started by other means (the strap appeared, a sign-in): it looks for the strap itself.
            val scan = coroutineContext[Job]
            val watcher = launch { Graph.recorderRunning.first { it }; scan?.cancel() }
            val macs = withContext(Dispatchers.IO) { CompanionAssociation.associations(app).mapNotNull { it.address } }
            val sensorId = Graph.settings.load().sensorId
            val found = try {
                withTimeoutOrNull(Constants.APP_OPEN_SCAN_MS) {
                    BleManager(app).scan(sensorId).first { d -> macs.isEmpty() || macs.any { it.equals(d.address, ignoreCase = true) } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Strap scan failed: ${e.javaClass.simpleName}")
                null
            }
            if (found != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Timber.i("Strap found by the app-open scan; starting the service")
                RecorderService.start(app)
            }
            watcher.cancel()
        }
    }

    private fun stopStrapScan() {
        strapScan?.cancel()
        strapScan = null
    }

    override fun onPause() {
        stopStrapScan()
        super.onPause()
    }

    override fun onStop() {
        stopStrapScan()
        super.onStop()
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }
}

private val TAB_LABELS = listOf("Status", "Settings", "Sessions")

@Composable
fun MainScreen(
    onRequestPermissions: () -> Unit,
    onPairStrap: () -> Unit,
    onUnpairStrap: () -> Unit,
    pairedCount: Int,
) {
    // Set by the service after it starts observing, so collected rather than read once.
    val observingCount by Graph.observingCount.collectAsState()
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TAB_LABELS.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(modifier = androidx.compose.ui.Modifier.padding(padding)) {
            when (selected) {
                0 -> StatusScreen(
                    onRequestPermissions = onRequestPermissions,
                    onPairStrap = onPairStrap,
                    onUnpairStrap = onUnpairStrap,
                    pairedCount = pairedCount,
                    observingCount = observingCount,
                )
                1 -> SettingsScreen()
                else -> SessionsScreen()
            }
        }
    }
}
