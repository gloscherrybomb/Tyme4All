package com.tymewear.run.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tymewear.run.android.Graph
import com.tymewear.run.android.TymewearAccess
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.sync.IntervalsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SessionsScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val snackbarHostState = remember { SnackbarHostState() }
    var sessions by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var matchDialogFor by remember { mutableStateOf<String?>(null) }

    var tymewearOffReason by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        sessions = withContext(Dispatchers.IO) { Graph.sessionStore.list() }
        tymewearOffReason = withContext(Dispatchers.IO) { Graph.settings.load().tymewearOffReason() }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scaffoldPadding ->
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(scaffoldPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionRow(
                session = session,
                tymewearOffReason = tymewearOffReason,
                onRetrySync = {
                    scope.launch {
                        val message = withContext(Dispatchers.IO) {
                            val settings = Graph.settings.load()
                            val engine = engineFor(context, settings)
                            val now = System.currentTimeMillis()
                            val current = Graph.sessionStore.meta(session.id) ?: session
                            val tymewearLeft = current.syncState == "synced" && current.tymewearState in setOf("pending", "failed")
                            val offReason = settings.tymewearOffReason()
                            if (tymewearLeft && offReason == null) {
                                // Synced to Intervals.icu with only the Tymewear step left: retry that step alone.
                                engine.syncTymewear(session.id, settings, now); null
                            } else if (tymewearLeft) {
                                // Tymewear is off: the engine has no Tymewear step, so this repeats only the Intervals.icu push.
                                engine.sync(session.id, settings, now); offReason
                            } else {
                                engine.sync(session.id, settings, now); null
                            }
                        }
                        refresh()
                        message?.let { snackbarHostState.showSnackbar(it) }
                    }
                },
                onMatchById = { matchDialogFor = session.id },
                onDiscard = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Graph.sessionStore.updateMeta(session.id) { it.copy(syncState = "discarded", syncMessage = "discarded by user", lastSyncAttemptMs = System.currentTimeMillis()) }
                        }
                        refresh()
                    }
                },
            )
        }
    }
    }

    val dialogSessionId = matchDialogFor
    if (dialogSessionId != null) {
        MatchByIdDialog(
            onDismiss = { matchDialogFor = null },
            onConfirm = { activityId ->
                matchDialogFor = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val settings = Graph.settings.load()
                        engineFor(context, settings).syncTo(dialogSessionId, activityId, settings, System.currentTimeMillis())
                    }
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun SessionRow(session: SessionMeta, tymewearOffReason: String?, onRetrySync: () -> Unit, onMatchById: () -> Unit, onDiscard: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.id)
            Text("Duration: ${formatDuration(session)}")
            Text("Sync: ${session.syncState}")
            session.syncMessage?.let { Text(it) }
            session.tymewearState?.let { state ->
                // A pending step waits on Tymewear being on; say why it is not.
                val reason = if (state == "pending") tymewearOffReason else null
                Text(listOfNotNull("Tymewear: $state", reason).joinToString(" · "))
            }
            session.tymewearMessage?.let { Text(it) }
            Text("Activity id: ${session.activityId ?: "-"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetrySync) { Text("Retry sync") }
                OutlinedButton(onClick = onMatchById) { Text("Match by id") }
                if (session.endMs != null && session.syncState in setOf("pending", "unmatched", "failed")) {
                    OutlinedButton(onClick = onDiscard) { Text("Discard") }
                }
            }
        }
    }
}

private fun engineFor(context: android.content.Context, settings: com.tymewear.run.domain.Settings) =
    TymewearAccess.engine(context, IntervalsClient(settings.intervalsApiKey ?: ""), settings)

@Composable
private fun MatchByIdDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Match by Intervals.icu activity id") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Activity id") })
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Match") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatDuration(session: SessionMeta): String {
    val end = session.endMs ?: return "in progress"
    val seconds = (end - session.startMs) / 1000
    val m = seconds / 60
    val s = seconds % 60
    return "%dm %02ds".format(m, s)
}
