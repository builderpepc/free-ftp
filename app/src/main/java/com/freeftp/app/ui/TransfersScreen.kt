package com.freeftp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeftp.core.transfer.TransferDirection
import com.freeftp.core.transfer.TransferManager
import com.freeftp.core.transfer.TransferState
import com.freeftp.core.transfer.TransferStatus

/** The transfer queue, with per-item progress and controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    manager: TransferManager,
    onBack: () -> Unit,
    onOpenFile: (TransferStatus) -> Unit = {},
) {
    val transfers by manager.transfers.collectAsStateWithLifecycle()
    val paused by manager.isPaused.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (transfers.any { it.state.isActive || it.state == TransferState.PAUSED }) {
                        IconButton(
                            onClick = { if (paused) manager.resume() else manager.pause() },
                            modifier = Modifier.testTag("pause-toggle"),
                        ) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (paused) "Resume transfers" else "Pause transfers",
                            )
                        }
                    }
                    TextButton(
                        onClick = manager::clearCompleted,
                        modifier = Modifier.testTag("clear-completed"),
                    ) { Text("Clear done") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (transfers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing queued", modifier = Modifier.testTag("no-transfers"))
                }
            } else {
                LazyColumn(Modifier.testTag("transfer-list")) {
                    items(transfers, key = { it.request.id }) { status ->
                        TransferRow(
                            status = status,
                            onCancel = { manager.cancel(status.request.id) },
                            onRetry = { manager.retry(status.request.id) },
                            onOpen = { onOpenFile(status) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    status: TransferStatus,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    // A finished download is worth a tap: that is the moment you want to read the thing.
    val openable = status.state == TransferState.COMPLETED &&
        status.request.direction == TransferDirection.DOWNLOAD
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(16.dp)
            .testTag("transfer-${status.request.displayName}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (status.request.direction == TransferDirection.UPLOAD) {
                    Icons.Filled.Upload
                } else {
                    Icons.Filled.Download
                },
                contentDescription = status.request.direction.name,
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(status.request.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(summarise(status), style = MaterialTheme.typography.bodySmall)
            }
            when (status.state) {
                TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED -> IconButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("cancel-${status.request.displayName}"),
                ) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }

                TransferState.FAILED, TransferState.CANCELLED -> IconButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("retry-${status.request.displayName}"),
                ) { Icon(Icons.Filled.Refresh, contentDescription = "Retry") }

                TransferState.COMPLETED -> if (openable) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open")
                }
            }
        }
        if (status.state == TransferState.RUNNING) {
            val fraction = if (status.total > 0) {
                (status.transferred.toFloat() / status.total).coerceIn(0f, 1f)
            } else {
                null
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun summarise(status: TransferStatus): String = when (status.state) {
    TransferState.QUEUED -> "Queued"
    TransferState.RUNNING -> if (status.total > 0) {
        "${formatSize(status.transferred)} of ${formatSize(status.total)}"
    } else {
        formatSize(status.transferred)
    }

    TransferState.PAUSED -> if (status.total > 0) {
        "Paused · ${formatSize(status.transferred)} of ${formatSize(status.total)}"
    } else {
        "Paused"
    }

    TransferState.COMPLETED -> "Completed · ${formatSize(status.transferred)} · tap to open"
    TransferState.CANCELLED -> "Cancelled"
    TransferState.FAILED -> status.error ?: "Failed"
}
