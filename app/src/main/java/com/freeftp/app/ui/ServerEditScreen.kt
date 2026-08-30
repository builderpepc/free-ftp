package com.freeftp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeftp.core.Protocol

/** Create or edit a saved connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(
    viewModel: ServerEditViewModel,
    onDone: () -> Unit,
) {
    val form by viewModel.form.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "New server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { if (viewModel.save()) onDone() },
                        modifier = Modifier.testTag("save-server"),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Protocol", style = MaterialTheme.typography.labelLarge)
            // Four chips do not fit a phone's width; let the row scroll rather than
            // wrapping a label onto two lines.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Protocol.entries.forEach { protocol ->
                    FilterChip(
                        selected = form.protocol == protocol,
                        onClick = { viewModel.setProtocol(protocol) },
                        label = { Text(protocol.label) },
                        modifier = Modifier.testTag("protocol-${protocol.name}"),
                    )
                }
            }

            Field("Name", form.name, viewModel::setName, "name", error = form.errorFor("name"))
            Field("Host", form.host, viewModel::setHost, "host", error = form.errorFor("host"))
            Field(
                label = "Port",
                value = form.port,
                onChange = viewModel::setPort,
                tag = "port",
                keyboardType = KeyboardType.Number,
                error = form.errorFor("port"),
            )

            Text("Authentication", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthMode.entries.forEach { mode ->
                    FilterChip(
                        selected = form.authMode == mode,
                        onClick = { viewModel.setAuthMode(mode) },
                        label = { Text(mode.label) },
                        enabled = mode.isAvailableFor(form.protocol),
                        modifier = Modifier.testTag("auth-${mode.name}"),
                    )
                }
            }

            if (form.authMode != AuthMode.ANONYMOUS) {
                Field(
                    "Username",
                    form.username,
                    viewModel::setUsername,
                    "username",
                    error = form.errorFor("username"),
                )
            }
            if (form.authMode == AuthMode.PASSWORD || form.authMode == AuthMode.KEY) {
                Field(
                    label = if (form.authMode == AuthMode.KEY) "Password (optional fallback)" else "Password",
                    value = form.password,
                    onChange = viewModel::setPassword,
                    tag = "password",
                    secret = true,
                )
            }
            if (form.authMode == AuthMode.KEY) {
                Field(
                    "Private key",
                    form.privateKey,
                    viewModel::setPrivateKey,
                    "private-key",
                    singleLine = false,
                )
                Field("Key passphrase", form.passphrase, viewModel::setPassphrase, "passphrase", secret = true)
            }

            Text("Options", style = MaterialTheme.typography.labelLarge)
            Field("Initial folder", form.initialPath, viewModel::setInitialPath, "initial-path")

            if (form.protocol.isFtpFamily) {
                Toggle("Passive mode (PASV)", form.passiveMode, viewModel::setPassiveMode, "passive")
                Toggle(
                    "Show hidden files (LIST -a)",
                    form.showHiddenFiles,
                    viewModel::setShowHiddenFiles,
                    "show-hidden",
                )
            }
            if (form.protocol.encrypted && form.protocol.isFtpFamily) {
                Toggle(
                    "Accept self-signed certificate",
                    form.trustAllCertificates,
                    viewModel::setTrustAllCertificates,
                    "trust-cert",
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
    error: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = singleLine,
            isError = error != null,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().testTag("field-$tag"),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    // The whole row toggles, not just the box: a checkbox on a phone is a small
    // target, and tapping its label doing nothing reads as the app being broken.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .testTag("toggle-$tag"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
