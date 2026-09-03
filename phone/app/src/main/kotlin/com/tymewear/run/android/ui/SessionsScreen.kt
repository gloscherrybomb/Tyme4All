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
import androidx.compose.ui.unit.dp
import com.tymewear.run.android.Graph
import com.tymewear.run.domain.session.SessionMeta
import com.tymewear.run.domain.sync.IntervalsClient
import com.tymewear.run.domain.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SessionsScreen() {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var matchDialogFor by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        sessions = withContext(Dispatchers.IO) { Graph.sessionStore.list() }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionRow(
                session = session,
                onRetrySync = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val settings = Graph.settings.load()
                            val client = IntervalsClient(settings.intervalsApiKey ?: "")
                            SyncEngine(client, Graph.sessionStore).sync(session.id, settings, System.currentTimeMillis())
                        }
                        refresh()
                    }
                },
                onMatchById = { matchDialogFor = session.id },
            )
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
                        val client = IntervalsClient(settings.intervalsApiKey ?: "")
                        SyncEngine(client, Graph.sessionStore).syncTo(dialogSessionId, activityId, settings, System.currentTimeMillis())
                    }
                    refresh()
                }
            },
        )
    }
}

@Composable
private fun SessionRow(session: SessionMeta, onRetrySync: () -> Unit, onMatchById: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.id)
            Text("Duration: ${formatDuration(session)}")
            Text("Sync: ${session.syncState}")
            session.syncMessage?.let { Text(it) }
            Text("Activity id: ${session.activityId ?: "-"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetrySync) { Text("Retry sync") }
                OutlinedButton(onClick = onMatchById) { Text("Match by id") }
            }
        }
    }
}

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
