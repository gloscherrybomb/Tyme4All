package com.tymewear.run.android.ui

import android.Manifest
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tymewear.run.android.CompanionAssociation
import com.tymewear.run.android.Graph
import com.tymewear.run.android.RecorderService
import com.tymewear.run.domain.StrapPresence
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
    var observingCount = mutableStateOf(0)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startRecorderServiceIfAllowed()
        refreshPairedCount()

        setContent {
            KBreatheTheme {
                Surface {
                    MainScreen(
                        onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                        onPairStrap = { pairStrap() },
                        onUnpairStrap = { unpairStrap() },
                        pairedCount = pairedCount.value,
                        observingCount = observingCount.value,
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
        Graph.observingCount = CompanionAssociation.startObserving(this)
        observingCount.value = Graph.observingCount
        RecorderService.start(this)
        refreshPairedCount()
    }

    private fun unpairStrap() {
        CompanionAssociation.stopObserving(this)
        CompanionAssociation.disassociateAll(this)
        Graph.observingCount = 0
        observingCount.value = 0
        // Unpairing must not leave a stale AWAY reading behind: with no association left
        // to report presence, the housekeeping loop should treat the strap the same as
        // "never paired" (UNKNOWN), not stop the service on an answer that can never update.
        Graph.strapPresence = StrapPresence.UNKNOWN
        refreshPairedCount()
    }

    private fun refreshPairedCount() {
        pairedCount.value = if (CompanionAssociation.isSupported(this)) CompanionAssociation.associations(this).size else 0
        observingCount.value = Graph.observingCount
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

    private fun hasConnectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun startRecorderServiceIfAllowed() {
        if (hasConnectPermission()) RecorderService.start(this)
    }
}

private val TAB_LABELS = listOf("Status", "Settings", "Sessions")

@Composable
fun MainScreen(
    onRequestPermissions: () -> Unit,
    onPairStrap: () -> Unit,
    onUnpairStrap: () -> Unit,
    pairedCount: Int,
    observingCount: Int,
) {
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
