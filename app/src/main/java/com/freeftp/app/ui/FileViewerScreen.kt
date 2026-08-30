package com.freeftp.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shows a remote text file streamed straight into memory — nothing is written to disk. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    viewModel: FileViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val horizontalScroll = rememberScrollState()

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!state.loading && !state.isBinary) {
                            Text(
                                "${state.lineCount} lines · ${formatSize(state.bytesShown)} · ${state.charsetName}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("viewer-subtitle"),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isBinary) {
                        IconButton(
                            onClick = viewModel::toggleWrap,
                            modifier = Modifier.testTag("wrap-toggle"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.WrapText,
                                contentDescription = if (state.wrapLines) {
                                    "Stop wrapping lines"
                                } else {
                                    "Wrap lines"
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = viewModel::download,
                        modifier = Modifier.testTag("viewer-download"),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = "Download")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("viewer-loading"))
                }

                state.error != null -> Notice(
                    title = "Could not open this file",
                    detail = state.error!!,
                    tag = "viewer-error",
                )

                state.isBinary -> Notice(
                    title = "Not a text file",
                    detail = "${state.name} looks like binary data, so there is nothing " +
                        "readable to show. Use the download button to save it instead.",
                    tag = "viewer-binary",
                )

                state.lines.isEmpty() -> Notice(
                    title = "This file is empty",
                    detail = "${state.name} contains no data.",
                    tag = "viewer-empty",
                )

                else -> Column(Modifier.fillMaxSize()) {
                    if (state.truncated) {
                        TruncationBanner(state.bytesShown)
                    }
                    SelectionContainer {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxSize().testTag("viewer-content"),
                        ) {
                            items(state.lines.size) { index ->
                                Text(
                                    text = state.lines[index],
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    softWrap = state.wrapLines,
                                    // Unwrapped lines share one scroll state so the whole
                                    // file pans sideways together, like a code editor.
                                    modifier = if (state.wrapLines) {
                                        Modifier.fillMaxWidth()
                                    } else {
                                        Modifier.horizontalScroll(horizontalScroll)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TruncationBanner(bytesShown: Long) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Showing the first ${formatSize(bytesShown)} of this file. " +
                "Download it to read the rest.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp).testTag("viewer-truncated"),
        )
    }
}

@Composable
private fun Notice(title: String, detail: String, tag: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp).testTag(tag),
        )
    }
}
