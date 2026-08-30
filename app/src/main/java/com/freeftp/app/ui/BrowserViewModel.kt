package com.freeftp.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freeftp.app.AppContainer
import com.freeftp.core.RemoteFile
import com.freeftp.core.RemotePath
import com.freeftp.core.bulk.BulkTransferPolicy
import com.freeftp.core.bulk.RemoteTreeScan
import com.freeftp.core.bulk.ScannedFile
import com.freeftp.core.bulk.scanForDownload
import com.freeftp.core.transfer.FileTarget
import com.freeftp.core.transfer.TransferDirection
import com.freeftp.core.transfer.TransferRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A bulk action waiting for the user to confirm it. */
data class PendingBulkAction(
    val kind: Kind,
    val fileCount: Int,
    val totalBytes: Long,
    val truncated: Boolean,
    val scan: RemoteTreeScan? = null,
    val entries: List<RemoteFile> = emptyList(),
) {
    enum class Kind { DOWNLOAD, DELETE }
}

data class BrowserState(
    val path: String = RemotePath.ROOT,
    val entries: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val busyMessage: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val serverName: String = "",
    val startPath: String = RemotePath.ROOT,
    val sessionEnded: Boolean = false,
    /** Remote paths of the selected entries; non-empty means selection mode is on. */
    val selected: Set<String> = emptySet(),
    val pendingBulk: PendingBulkAction? = null,
) {
    val selectionMode: Boolean get() = selected.isNotEmpty()
    val selectedEntries: List<RemoteFile> get() = entries.filter { it.path in selected }
}

class BrowserViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    val transfers = container.session.transfers

    init {
        val profile = container.session.profile.value
        if (profile == null) {
            // Android restored the browser screen but the process (and with it the
            // connection) was killed in between; there is nothing to browse.
            _state.value = _state.value.copy(sessionEnded = true)
        } else {
            _state.value = _state.value.copy(
                serverName = profile.name,
                path = profile.initialPath,
                startPath = profile.initialPath,
            )
            refresh()
        }
    }

    fun refresh() = load(_state.value.path)

    fun open(entry: RemoteFile) {
        if (entry.isDirectory) load(entry.path)
    }

    fun navigateUp() {
        val current = _state.value.path
        if (current != RemotePath.ROOT) load(RemotePath.parent(current))
    }

    private fun load(path: String) {
        // Moving to another folder ends the selection: the selected items are no longer
        // on screen, and acting on files the user cannot see is exactly what surprises.
        _state.value = _state.value.copy(loading = true, error = null, selected = emptySet())
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { container.session.withClient { it.list(path) } } }
                .onSuccess {
                    _state.value = _state.value.copy(path = path, entries = it, loading = false)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message ?: "Could not open $path",
                    )
                }
        }
    }

    // ---------------------------------------------------------------- selection

    fun toggleSelection(entry: RemoteFile) {
        val selected = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (entry.path in selected) selected - entry.path else selected + entry.path,
        )
    }

    fun selectAll() {
        _state.value = _state.value.copy(selected = _state.value.entries.map { it.path }.toSet())
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    // ---------------------------------------------------------------- bulk actions

    /** Downloads the selection, folders included, asking first when it is a lot. */
    fun downloadSelected() = prepareDownload(_state.value.selectedEntries)

    /** Downloads everything in the folder on screen. */
    fun downloadAll() = prepareDownload(_state.value.entries)

    private fun prepareDownload(entries: List<RemoteFile>) {
        if (entries.isEmpty()) return
        val base = _state.value.path
        _state.value = _state.value.copy(busyMessage = "Working out what to download…", error = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.session.withClient { it.scanForDownload(base, entries) }
                }
            }
                .onSuccess { scan ->
                    _state.value = _state.value.copy(busyMessage = null)
                    when {
                        scan.fileCount == 0 -> _state.value =
                            _state.value.copy(notice = "Nothing to download — no files in there")

                        BulkTransferPolicy.needsConfirmation(scan) -> _state.value =
                            _state.value.copy(
                                pendingBulk = PendingBulkAction(
                                    kind = PendingBulkAction.Kind.DOWNLOAD,
                                    fileCount = scan.fileCount,
                                    totalBytes = scan.totalBytes,
                                    truncated = scan.truncated,
                                    scan = scan,
                                )
                            )

                        else -> enqueue(scan.files)
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busyMessage = null,
                        error = it.message ?: "Could not read that folder",
                    )
                }
        }
    }

    /** Deleting is always confirmed: there is no undo on a remote server. */
    fun deleteSelected() {
        val entries = _state.value.selectedEntries
        if (entries.isEmpty()) return
        _state.value = _state.value.copy(
            pendingBulk = PendingBulkAction(
                kind = PendingBulkAction.Kind.DELETE,
                fileCount = entries.size,
                totalBytes = entries.sumOf { it.size },
                truncated = false,
                entries = entries,
            )
        )
    }

    fun confirmBulk() {
        val pending = _state.value.pendingBulk ?: return
        _state.value = _state.value.copy(pendingBulk = null)
        when (pending.kind) {
            PendingBulkAction.Kind.DOWNLOAD -> pending.scan?.let { enqueue(it.files) }
            PendingBulkAction.Kind.DELETE -> deleteNow(pending.entries)
        }
    }

    fun dismissBulk() {
        _state.value = _state.value.copy(pendingBulk = null)
    }

    private fun enqueue(files: List<ScannedFile>) {
        container.session.transfers.enqueueAll(
            files.map { file ->
                TransferRequest(
                    id = UUID.randomUUID().toString(),
                    direction = TransferDirection.DOWNLOAD,
                    remotePath = file.remotePath,
                    // The relative path is what keeps the folder structure on the device.
                    target = container.downloads.targetFor(file.relativePath),
                )
            }
        )
        _state.value = _state.value.copy(
            selected = emptySet(),
            notice = if (files.size == 1) {
                "Downloading ${files.single().relativePath}"
            } else {
                "Downloading ${files.size} files"
            },
        )
    }

    private fun deleteNow(entries: List<RemoteFile>) {
        _state.value = _state.value.copy(busyMessage = "Deleting…", error = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.session.withClient { client ->
                        entries.forEach { client.deleteRecursively(it.path) }
                    }
                }
            }
                .onSuccess {
                    _state.value = _state.value.copy(
                        busyMessage = null,
                        selected = emptySet(),
                        notice = "Deleted ${entries.size} item${if (entries.size == 1) "" else "s"}",
                    )
                    refresh()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        busyMessage = null,
                        error = it.message ?: "Could not delete",
                    )
                }
        }
    }

    // ---------------------------------------------------------------- single-item actions

    fun createDirectory(name: String) = mutate("Created $name") {
        container.session.withClient { client ->
            client.makeDirectory(RemotePath.join(_state.value.path, name))
        }
    }

    fun rename(entry: RemoteFile, newName: String) = mutate("Renamed to $newName") {
        container.session.withClient { client ->
            client.rename(entry.path, RemotePath.join(RemotePath.parent(entry.path), newName))
        }
    }

    fun delete(entry: RemoteFile) = mutate("Deleted ${entry.name}") {
        container.session.withClient { it.deleteRecursively(entry.path) }
    }

    /** Downloads one entry; a folder goes through the same scan-and-confirm path. */
    fun download(entry: RemoteFile) {
        if (entry.isDirectory) {
            prepareDownload(listOf(entry))
            return
        }
        container.session.transfers.enqueue(
            TransferRequest(
                id = UUID.randomUUID().toString(),
                direction = TransferDirection.DOWNLOAD,
                remotePath = entry.path,
                target = container.downloads.targetFor(entry.name),
            )
        )
        _state.value = _state.value.copy(notice = "Downloading ${entry.name}")
    }

    /**
     * Queues an upload of a document the user picked.
     *
     * The content is copied into the cache first: a `content://` URI is not a file, and
     * the transfer may still be queued long after the picker's permission grant lapses.
     */
    fun upload(context: Context, uri: Uri) {
        viewModelScope.launch {
            val staged = runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(context, uri)
                    val target = File(context.cacheDir, "uploads/$name").apply { parentFile?.mkdirs() }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use(input::copyTo)
                    } ?: error("Could not read the selected file")
                    target
                }
            }
            staged
                .onSuccess { file ->
                    container.session.transfers.enqueue(
                        TransferRequest(
                            id = UUID.randomUUID().toString(),
                            direction = TransferDirection.UPLOAD,
                            remotePath = RemotePath.join(_state.value.path, file.name),
                            target = FileTarget(file),
                        )
                    )
                    _state.value = _state.value.copy(notice = "Uploading ${file.name}")
                }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    /**
     * Saves the folder currently on screen as this server's start folder, so the next
     * connection opens straight into it.
     */
    fun useCurrentFolderAsStart() {
        val profile = container.session.profile.value ?: return
        val path = _state.value.path
        viewModelScope.launch {
            runCatching {
                val updated = profile.copy(initialPath = path)
                withContext(Dispatchers.IO) { container.profiles.save(updated) }
                container.session.updateProfile(updated)
            }
                .onSuccess {
                    _state.value = _state.value.copy(
                        startPath = path,
                        notice = "${profile.name} will now open at $path",
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        error = it.message ?: "Could not save the start folder",
                    )
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch { container.session.disconnect() }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun mutate(success: String, action: suspend () -> Unit) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess {
                    _state.value = _state.value.copy(notice = success)
                    load(_state.value.path)
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed")
                }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowserViewModel(container) as T
    }
}
