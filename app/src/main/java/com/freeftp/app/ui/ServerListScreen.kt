package com.freeftp.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeftp.core.Protocol
import com.freeftp.core.ServerProfile

/** The home screen: the list of saved servers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditServer: (String) -> Unit,
    onConnected: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FreeFTP") },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                modifier = Modifier.testTag("add-server"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add server")
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.profiles.isEmpty() -> EmptyState()
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.profiles, key = { it.id }) { profile ->
                        ServerRow(
                            profile = profile,
                            onConnect = { viewModel.connect(profile, onConnected) },
                            onEdit = { onEditServer(profile.id) },
                            onDelete = { pendingDelete = profile },
                        )
                    }
                }
            }

            if (state.connecting) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("connecting"))
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Could not connect") },
            text = { Text(message, Modifier.testTag("connect-error")) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
        )
    }

    state.hostKeyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::rejectHostKey,
            title = { Text("Unrecognised server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${prompt.profile.host} presented a host key FreeFTP has not seen before.")
                    Text(prompt.fingerprint, fontWeight = FontWeight.Bold)
                    Text("Only continue if this matches the fingerprint your server reports.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.acceptHostKey(onConnected) },
                    modifier = Modifier.testTag("trust-host-key"),
                ) { Text("Trust and connect") }
            },
            dismissButton = { TextButton(onClick = viewModel::rejectHostKey) { Text("Cancel") } },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("This removes the saved connection from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(profile.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No servers yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tap + to add an FTP, FTPS or SFTP server.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).testTag("empty-hint"),
        )
    }
}

@Composable
private fun ServerRow(
    profile: ServerProfile,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onConnect).testTag("server-${profile.name}")) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (profile.protocol.encrypted) Icons.Filled.Lock else Icons.Filled.Public,
                contentDescription = if (profile.protocol.encrypted) "Encrypted" else "Unencrypted",
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${profile.protocol.label} · ${profile.host}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

val Protocol.label: String
    get() = when (this) {
        Protocol.FTP -> "FTP"
        Protocol.FTPS_EXPLICIT -> "FTPS"
        Protocol.FTPS_IMPLICIT -> "FTPS (implicit)"
        Protocol.SFTP -> "SFTP"
    }
