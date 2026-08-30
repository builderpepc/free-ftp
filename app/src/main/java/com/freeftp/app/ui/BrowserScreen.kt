package com.freeftp.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeftp.core.RemoteFile
import com.freeftp.core.RemotePath
import com.freeftp.core.transfer.TransferState
import java.text.DateFormat
import java.util.Date

/** The remote file browser. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onDisconnected: () -> Unit,
    onOpenTransfers: () -> Unit,
    onViewFile: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.transfers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }

    var showCreateFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var showActions by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.upload(context, it) }
    }

    // Back walks up the remote tree and only leaves the server once there is nowhere
    // left to go up to. Dropping the connection on the first back press — which is what
    // navigation would do by default — throws away the session for a mis-tap, and
    // leaves the sockets open behind it.
    val goBack: () -> Unit = {
        if (state.path != RemotePath.ROOT) {
            viewModel.navigateUp()
        } else {
            viewModel.disconnect()
            onDisconnected()
        }
    }

    BackHandler(onBack = goBack)

    LaunchedEffect(state.sessionEnded) {
        if (state.sessionEnded) onDisconnected()
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    val active = transfers.count { it.state == TransferState.RUNNING || it.state == TransferState.QUEUED }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            if (state.selectionMode) {
                SelectionBar(
                    count = state.selected.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onDownload = viewModel::downloadSelected,
                    onDelete = viewModel::deleteSelected,
                )
                return@Scaffold
            }
            TopAppBar(
                title = {
                    Column {
                        Text(state.serverName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.path,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("current-path"),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = goBack, modifier = Modifier.testTag("up")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (state.path == RemotePath.ROOT) {
                                "Leave this server"
                            } else {
                                "Up"
                            },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, modifier = Modifier.testTag("refresh")) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenTransfers, modifier = Modifier.testTag("transfers")) {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "Transfers")
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showOverflow = true },
                            modifier = Modifier.testTag("overflow"),
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                        ) {
                            val isStartFolder = state.path == state.startPath
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isStartFolder) {
                                            "Opens here already"
                                        } else {
                                            "Open here next time"
                                        }
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                trailingIcon = {
                                    if (isStartFolder) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                                enabled = !isStartFolder,
                                onClick = {
                                    showOverflow = false
                                    viewModel.useCurrentFolderAsStart()
                                },
                                modifier = Modifier.testTag("menu-start-folder"),
                            )
                            DropdownMenuItem(
                                text = { Text("Download all in this folder") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                enabled = state.entries.isNotEmpty(),
                                onClick = {
                                    showOverflow = false
                                    viewModel.downloadAll()
                                },
                                modifier = Modifier.testTag("menu-download-all"),
                            )
                            DropdownMenuItem(
                                text = { Text("Select items") },
                                leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                enabled = state.entries.isNotEmpty(),
                                onClick = {
                                    showOverflow = false
                                    viewModel.selectAll()
                                },
                                modifier = Modifier.testTag("menu-select"),
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showOverflow = false
                                    viewModel.disconnect()
                                    onDisconnected()
                                },
                                modifier = Modifier.testTag("menu-disconnect"),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showActions = true },
                    modifier = Modifier.testTag("browser-actions"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Actions")
                }
                DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                    DropdownMenuItem(
                        text = { Text("Upload file") },
                        leadingIcon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                        onClick = {
                            showActions = false
                            picker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.testTag("action-upload"),
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showActions = false
                            showCreateFolder = true
                        },
                        modifier = Modifier.testTag("action-new-folder"),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("loading"))
            if (state.entries.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty", modifier = Modifier.testTag("empty-folder"))
                }
            } else {
                LazyColumn(
                    // Leave room under the last row: without it the floating button sits
                    // on top of that row's menu and the entry cannot be acted on at all.
                    contentPadding = PaddingValues(bottom = 88.dp),
                    modifier = Modifier.testTag("file-list"),
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            selectionMode = state.selectionMode,
                            selected = entry.path in state.selected,
                            onOpen = { viewModel.open(entry) },
                            onView = { onViewFile(entry.path) },
                            onToggleSelected = { viewModel.toggleSelection(entry) },
                            onDownload = { viewModel.download(entry) },
                            onRename = { renaming = entry },
                            onDelete = { deleting = entry },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.busyMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Please wait") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(message, Modifier.padding(start = 16.dp).testTag("busy"))
                }
            },
            confirmButton = {},
        )
    }

    state.pendingBulk?.let { pending -> BulkConfirmDialog(pending, viewModel) }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("Something went wrong") },
            text = { Text(message, Modifier.testTag("browser-error")) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
        )
    }

    if (showCreateFolder) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            tag = "new-folder-name",
            onDismiss = { showCreateFolder = false },
            onConfirm = {
                viewModel.createDirectory(it)
                showCreateFolder = false
            },
        )
    }

    renaming?.let { entry ->
        TextPromptDialog(
            title = "Rename ${entry.name}",
            label = "New name",
            initial = entry.name,
            tag = "rename-name",
            onDismiss = { renaming = null },
            onConfirm = {
                viewModel.rename(entry, it)
                renaming = null
            },
        )
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${entry.name}?") },
            text = {
                Text(
                    if (entry.isDirectory) {
                        "This deletes the folder and everything inside it on the server."
                    } else {
                        "This deletes the file on the server."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry)
                        deleting = null
                    },
                    modifier = Modifier.testTag("confirm-delete"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: RemoteFile,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onView: () -> Unit,
    onToggleSelected: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            // Long-press starts a selection, and once one is running a plain tap adds to
            // it rather than opening — the behaviour every file manager on the phone has.
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelected()
                        entry.isDirectory -> onOpen()
                        else -> onView()
                    }
                },
                onLongClick = onToggleSelected,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("entry-${entry.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelected() },
                modifier = Modifier.testTag("check-${entry.name}"),
            )
        }
        Icon(
            imageVector = when {
                entry.isSymlink -> Icons.Filled.Link
                entry.isDirectory -> Icons.Filled.Folder
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    if (!entry.isDirectory) append(formatSize(entry.size))
                    entry.modifiedEpochMillis?.let {
                        if (isNotEmpty()) append(" · ")
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !selectionMode) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${entry.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("View") },
                        onClick = {
                            menuOpen = false
                            onView()
                        },
                        modifier = Modifier.testTag("menu-view"),
                    )
                }
                // Folders download too, recursively — the structure is rebuilt locally.
                DropdownMenuItem(
                    text = { Text(if (entry.isDirectory) "Download folder" else "Download") },
                    onClick = {
                        menuOpen = false
                        onDownload()
                    },
                    modifier = Modifier.testTag("menu-download"),
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                    modifier = Modifier.testTag("menu-rename"),
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("menu-delete"),
                )
            }
        }
    }
}

/** The top bar while items are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected", modifier = Modifier.testTag("selection-count")) },
        navigationIcon = {
            IconButton(onClick = onClose, modifier = Modifier.testTag("close-selection")) {
                Icon(Icons.Filled.Close, contentDescription = "Clear selection")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll, modifier = Modifier.testTag("select-all")) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
            }
            IconButton(onClick = onDownload, modifier = Modifier.testTag("download-selected")) {
                Icon(Icons.Filled.Download, contentDescription = "Download selected")
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete-selected")) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
            }
        },
    )
}

/**
 * The guard rail for bulk actions.
 *
 * Downloading only asks when the job is large; deleting always asks, because a remote
 * server has no recycle bin to retrieve it from.
 */
@Composable
private fun BulkConfirmDialog(pending: PendingBulkAction, viewModel: BrowserViewModel) {
    val downloading = pending.kind == PendingBulkAction.Kind.DOWNLOAD
    val items = "${pending.fileCount} " + if (pending.fileCount == 1) "item" else "items"
    AlertDialog(
        onDismissRequest = viewModel::dismissBulk,
        title = { Text(if (downloading) "Download $items?" else "Delete $items?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (downloading) {
                        "This will transfer $items totalling ${formatSize(pending.totalBytes)}."
                    } else {
                        "This permanently deletes $items on the server, including everything " +
                            "inside any folders. It cannot be undone."
                    },
                    modifier = Modifier.testTag("bulk-detail"),
                )
                if (pending.truncated) {
                    Text(
                        "The folder is larger than FreeFTP scanned, so the real total is " +
                            "higher than this.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("bulk-truncated"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::confirmBulk,
                modifier = Modifier.testTag("bulk-confirm"),
            ) { Text(if (downloading) "Download" else "Delete") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissBulk) { Text("Cancel") } },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    tag: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("field-$tag"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                modifier = Modifier.testTag("confirm-$tag"),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Human-readable byte counts, in the 1024-based units file managers use. */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}
