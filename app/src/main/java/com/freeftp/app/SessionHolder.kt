package com.freeftp.app

import com.freeftp.core.Protocol
import com.freeftp.core.RemoteClient
import com.freeftp.core.ServerProfile
import com.freeftp.core.ftp.FtpRemoteClient
import com.freeftp.core.sftp.HostKeyPolicy
import com.freeftp.core.sftp.HostKeyStore
import com.freeftp.core.sftp.SftpRemoteClient
import com.freeftp.core.transfer.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The live connection to a server, plus the transfer queue that runs alongside it.
 *
 * Browsing and transferring get **separate** connections. A single FTP control channel
 * can carry only one operation at a time, so sharing one would mean the file list froze
 * for the length of every download; two connections let the user keep browsing while
 * bytes move, which is what every desktop client does.
 */
class SessionHolder(private val hostKeys: HostKeyStore) {

    private val _profile = MutableStateFlow<ServerProfile?>(null)
    val profile: StateFlow<ServerProfile?> = _profile.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val browseMutex = Mutex()
    private val transferMutex = Mutex()

    private var browseClient: RemoteClient? = null
    private var transferClient: RemoteClient? = null

    val transfers: TransferManager = TransferManager(
        scope = scope,
        clientProvider = { transferClient() },
    )

    /**
     * Opens a connection for [profile]. When [trustNewHostKey] is false an unknown SSH
     * host key aborts the connection, so the user can be shown the fingerprint first.
     */
    suspend fun connect(profile: ServerProfile, trustNewHostKey: Boolean): Unit = browseMutex.withLock {
        closeAll()
        val fresh = newClient(profile, trustNewHostKey)
        fresh.connect()
        browseClient = fresh
        _profile.value = profile
    }

    /** Runs [block] against the browsing connection, one caller at a time. */
    suspend fun <T> withClient(block: (RemoteClient) -> T): T = browseMutex.withLock {
        val active = browseClient ?: error("Not connected")
        if (!active.isConnected) active.connect()
        block(active)
    }

    /**
     * Records an edit to the profile this session is running, so a change made while
     * connected (a new start folder, say) is reflected without reconnecting.
     */
    fun updateProfile(profile: ServerProfile) {
        if (_profile.value?.id == profile.id) _profile.value = profile
    }

    suspend fun disconnect() = browseMutex.withLock { closeAll() }

    private suspend fun transferClient(): RemoteClient = transferMutex.withLock {
        val profile = _profile.value ?: error("Not connected")
        transferClient?.takeIf { it.isConnected }?.let { return it }
        // A transfer connection reuses the host key already accepted for this session.
        newClient(profile, trustNewHostKey = true).also {
            it.connect()
            transferClient = it
        }
    }

    private fun newClient(profile: ServerProfile, trustNewHostKey: Boolean): RemoteClient =
        when (profile.protocol) {
            Protocol.SFTP -> SftpRemoteClient(
                profile = profile,
                hostKeyStore = hostKeys,
                hostKeyPolicy = if (trustNewHostKey) HostKeyPolicy.TRUST_ON_FIRST_USE else HostKeyPolicy.STRICT,
            )

            else -> FtpRemoteClient(profile)
        }

    private fun closeAll() {
        runCatching { browseClient?.disconnect() }
        runCatching { transferClient?.disconnect() }
        browseClient = null
        transferClient = null
        _profile.value = null
    }
}
