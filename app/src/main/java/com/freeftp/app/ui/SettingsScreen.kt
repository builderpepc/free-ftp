package com.freeftp.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.freeftp.app.storage.DownloadLocationStore

/** Settings. Currently one thing that matters: where downloads go. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    downloads: DownloadLocationStore,
    onBack: () -> Unit,
) {
    // Recomposition key: bumped whenever the stored location changes.
    var revision by remember { mutableStateOf(0) }
    val description = remember(revision) { downloads.describe() }
    val browsable = remember(revision) { downloads.isBrowsableByUser() }
    val lostAccess = remember(revision) { downloads.hasLostAccess }

    // The folder picker *is* the permission prompt: the user grants access to exactly
    // one directory. Dismissing it returns null, which leaves the previous choice alone.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            downloads.useUserFolder(uri)
            revision++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { pickFolder.launch(null) }
                    .padding(16.dp)
                    .testTag("setting-download-folder"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Download folder", style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("download-folder-value"),
                )
                if (lostAccess) {
                    Text(
                        "The folder you chose is no longer available, so downloads are " +
                            "going to the default again. Pick it once more to restore it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("download-folder-lost"),
                    )
                } else if (!browsable) {
                    Text(
                        "Files here are not visible in the Files app — Android hides " +
                            "app folders. Choose a folder to make downloads easy to find.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("download-folder-hidden"),
                    )
                } else {
                    Text(
                        "Tap to choose a different folder. FreeFTP asks for no storage " +
                            "permission — you grant access to the one folder you pick.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            TextButton(
                onClick = {
                    downloads.useDefault()
                    revision++
                },
                modifier = Modifier.padding(horizontal = 8.dp).testTag("reset-download-folder"),
            ) { Text("Use the default folder") }
        }
    }
}
