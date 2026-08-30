package com.freeftp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.freeftp.app.AppContainer
import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemotePath
import com.freeftp.core.ServerProfile
import com.freeftp.core.ValidationError
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthMode(val label: String) {
    PASSWORD("Password"),
    KEY("Key file"),
    ANONYMOUS("Anonymous"),
    ;

    /** SFTP has no anonymous login, and FTP cannot use an SSH key. */
    fun isAvailableFor(protocol: Protocol): Boolean = when (this) {
        PASSWORD -> true
        KEY -> protocol == Protocol.SFTP
        ANONYMOUS -> protocol != Protocol.SFTP
    }
}

data class ServerForm(
    val id: String = UUID.randomUUID().toString(),
    val isNew: Boolean = true,
    val name: String = "",
    val protocol: Protocol = Protocol.FTP,
    val host: String = "",
    val port: String = Protocol.FTP.defaultPort.toString(),
    val authMode: AuthMode = AuthMode.PASSWORD,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val initialPath: String = RemotePath.ROOT,
    val passiveMode: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val trustAllCertificates: Boolean = false,
    val errors: List<ValidationError> = emptyList(),
) {
    fun errorFor(field: String): String? = errors.firstOrNull { it.field == field }?.message
}

class ServerEditViewModel(
    private val container: AppContainer,
    profileId: String?,
) : ViewModel() {

    private val _form = MutableStateFlow(load(profileId))
    val form: StateFlow<ServerForm> = _form.asStateFlow()

    private fun load(profileId: String?): ServerForm {
        val existing = profileId?.let { container.profiles.find(it) } ?: return ServerForm()
        return ServerForm(
            id = existing.id,
            isNew = false,
            name = existing.name,
            protocol = existing.protocol,
            host = existing.host,
            port = existing.port.toString(),
            authMode = when (existing.credentials) {
                is Credentials.Anonymous -> AuthMode.ANONYMOUS
                is Credentials.Password -> AuthMode.PASSWORD
                is Credentials.PrivateKey -> AuthMode.KEY
            },
            username = existing.credentials.username,
            password = (existing.credentials as? Credentials.Password)?.password
                ?: (existing.credentials as? Credentials.PrivateKey)?.password.orEmpty(),
            privateKey = (existing.credentials as? Credentials.PrivateKey)?.privateKey.orEmpty(),
            passphrase = (existing.credentials as? Credentials.PrivateKey)?.passphrase.orEmpty(),
            initialPath = existing.initialPath,
            passiveMode = existing.passiveMode,
            showHiddenFiles = existing.showHiddenFiles,
            trustAllCertificates = existing.trustAllCertificates,
        )
    }

    fun setName(value: String) = update { it.copy(name = value) }
    fun setHost(value: String) = update { it.copy(host = value) }
    fun setPort(value: String) = update { it.copy(port = value.filter(Char::isDigit)) }
    fun setUsername(value: String) = update { it.copy(username = value) }
    fun setPassword(value: String) = update { it.copy(password = value) }
    fun setPrivateKey(value: String) = update { it.copy(privateKey = value) }
    fun setPassphrase(value: String) = update { it.copy(passphrase = value) }
    fun setInitialPath(value: String) = update { it.copy(initialPath = value) }
    fun setPassiveMode(value: Boolean) = update { it.copy(passiveMode = value) }
    fun setShowHiddenFiles(value: Boolean) = update { it.copy(showHiddenFiles = value) }
    fun setTrustAllCertificates(value: Boolean) = update { it.copy(trustAllCertificates = value) }

    /** Switching protocol moves the port to the new default, unless it was customised. */
    fun setProtocol(protocol: Protocol) = update { current ->
        val portWasDefault = current.port == current.protocol.defaultPort.toString()
        current.copy(
            protocol = protocol,
            port = if (portWasDefault) protocol.defaultPort.toString() else current.port,
            authMode = if (current.authMode.isAvailableFor(protocol)) current.authMode else AuthMode.PASSWORD,
        )
    }

    fun setAuthMode(mode: AuthMode) = update { it.copy(authMode = mode) }

    /** Validates and persists. Returns false and surfaces field errors when invalid. */
    fun save(): Boolean {
        val profile = toProfile()
        val errors = profile.validate() + extraErrors()
        if (errors.isNotEmpty()) {
            // Set directly: update() clears errors, which is what should happen on the
            // next keystroke but not when they have just been produced.
            _form.value = _form.value.copy(errors = errors)
            return false
        }
        container.profiles.save(profile)
        return true
    }

    private fun extraErrors(): List<ValidationError> = buildList {
        val form = _form.value
        if (form.port.toIntOrNull() == null) add(ValidationError("port", "Port must be a number"))
        if (form.authMode == AuthMode.KEY && form.privateKey.isBlank()) {
            add(ValidationError("privateKey", "Paste the private key"))
        }
    }

    private fun toProfile(): ServerProfile {
        val form = _form.value
        val credentials = when (form.authMode) {
            AuthMode.ANONYMOUS -> Credentials.Anonymous
            AuthMode.PASSWORD -> Credentials.Password(form.username, form.password)
            AuthMode.KEY -> Credentials.PrivateKey(
                username = form.username,
                privateKey = form.privateKey,
                passphrase = form.passphrase.ifBlank { null },
                password = form.password.ifBlank { null },
            )
        }
        return ServerProfile(
            id = form.id,
            name = form.name.trim(),
            protocol = form.protocol,
            host = form.host.trim(),
            port = form.port.toIntOrNull() ?: form.protocol.defaultPort,
            credentials = credentials,
            initialPath = RemotePath.normalize(form.initialPath),
            passiveMode = form.passiveMode,
            showHiddenFiles = form.showHiddenFiles,
            trustAllCertificates = form.trustAllCertificates,
        )
    }

    private fun update(transform: (ServerForm) -> ServerForm) {
        _form.value = transform(_form.value).copy(errors = emptyList())
    }

    class Factory(
        private val container: AppContainer,
        private val profileId: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ServerEditViewModel(container, profileId) as T
    }
}
