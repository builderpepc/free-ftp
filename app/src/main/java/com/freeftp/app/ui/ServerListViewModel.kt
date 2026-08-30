package com.freeftp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.freeftp.app.AppContainer
import com.freeftp.core.ServerProfile
import com.freeftp.core.UnknownHostKeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HostKeyPrompt(val profile: ServerProfile, val fingerprint: String)

data class ServerListState(
    val profiles: List<ServerProfile> = emptyList(),
    val connecting: Boolean = false,
    val error: String? = null,
    val hostKeyPrompt: HostKeyPrompt? = null,
)

class ServerListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ServerListState())
    val state: StateFlow<ServerListState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profiles = withContext(Dispatchers.IO) { container.profiles.load() }
            _state.value = _state.value.copy(profiles = profiles)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.profiles.delete(id) }
            refresh()
        }
    }

    /**
     * Connects, refusing an SSH host key we have never seen so the user can check the
     * fingerprint first. Trusting silently would make the stored-key check pointless.
     */
    fun connect(profile: ServerProfile, onConnected: () -> Unit) {
        attempt(profile, trustNewHostKey = false, onConnected = onConnected)
    }

    fun acceptHostKey(onConnected: () -> Unit) {
        val prompt = _state.value.hostKeyPrompt ?: return
        _state.value = _state.value.copy(hostKeyPrompt = null)
        attempt(prompt.profile, trustNewHostKey = true, onConnected = onConnected)
    }

    fun rejectHostKey() {
        _state.value = _state.value.copy(hostKeyPrompt = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun attempt(profile: ServerProfile, trustNewHostKey: Boolean, onConnected: () -> Unit) {
        _state.value = _state.value.copy(connecting = true, error = null)
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { container.session.connect(profile, trustNewHostKey) }
            }
            _state.value = _state.value.copy(connecting = false)
            outcome
                .onSuccess { onConnected() }
                .onFailure { failure ->
                    when (failure) {
                        is UnknownHostKeyException -> _state.value = _state.value.copy(
                            hostKeyPrompt = HostKeyPrompt(profile, failure.fingerprint),
                        )

                        else -> _state.value = _state.value.copy(
                            error = failure.message ?: failure::class.simpleName ?: "Unknown error",
                        )
                    }
                }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ServerListViewModel(container) as T
    }
}
