package com.freeftp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freeftp.app.AppContainer
import com.freeftp.core.RemotePath
import com.freeftp.core.preview.FilePreview
import com.freeftp.core.preview.TextPreview
import com.freeftp.core.preview.previewText
import com.freeftp.core.transfer.TransferDirection
import com.freeftp.core.transfer.TransferRequest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileViewerState(
    val path: String,
    val loading: Boolean = true,
    val lines: List<String> = emptyList(),
    val charsetName: String = "",
    val lineCount: Int = 0,
    val bytesShown: Long = 0L,
    val truncated: Boolean = false,
    val isBinary: Boolean = false,
    val wrapLines: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
) {
    val name: String get() = RemotePath.name(path)
}

class FileViewerViewModel(
    private val container: AppContainer,
    path: String,
) : ViewModel() {

    private val _state = MutableStateFlow(FileViewerState(path = path))
    val state: StateFlow<FileViewerState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        val path = _state.value.path
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.session.withClient { it.previewText(path, TextPreview.DEFAULT_LIMIT_BYTES) }
                }
            }
                .onSuccess { preview -> _state.value = apply(preview) }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = it.message ?: "Could not open ${RemotePath.name(path)}",
                    )
                }
        }
    }

    private fun apply(preview: FilePreview): FileViewerState = when (preview) {
        is FilePreview.Text -> _state.value.copy(
            loading = false,
            lines = TextPreview.displayLines(preview.content),
            charsetName = preview.charsetName,
            lineCount = preview.lineCount,
            bytesShown = preview.bytesShown,
            truncated = preview.truncated,
            isBinary = false,
        )

        is FilePreview.Binary -> _state.value.copy(
            loading = false,
            lines = emptyList(),
            bytesShown = preview.bytesInspected,
            isBinary = true,
        )
    }

    fun toggleWrap() {
        _state.value = _state.value.copy(wrapLines = !_state.value.wrapLines)
    }

    /** Falls back to a normal download, for binaries or when the whole file is wanted. */
    fun download() {
        val path = _state.value.path
        container.session.transfers.enqueue(
            TransferRequest(
                id = UUID.randomUUID().toString(),
                direction = TransferDirection.DOWNLOAD,
                remotePath = path,
                target = container.downloads.targetFor(RemotePath.name(path)),
            )
        )
        _state.value = _state.value.copy(notice = "Downloading ${RemotePath.name(path)}")
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    class Factory(
        private val container: AppContainer,
        private val path: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FileViewerViewModel(container, path) as T
    }
}
