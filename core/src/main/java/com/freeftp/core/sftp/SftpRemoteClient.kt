package com.freeftp.core.sftp

import com.freeftp.core.AuthenticationException
import com.freeftp.core.CancellationSignal
import com.freeftp.core.Credentials
import com.freeftp.core.DirectoryNotEmptyException
import com.freeftp.core.InvalidFileNameException
import com.freeftp.core.NotADirectoryException
import com.freeftp.core.NotConnectedException
import com.freeftp.core.PermissionDeniedException
import com.freeftp.core.ProgressListener
import com.freeftp.core.Protocol
import com.freeftp.core.ProtocolFailureException
import com.freeftp.core.QuotaExceededException
import com.freeftp.core.RemoteClient
import com.freeftp.core.RemoteException
import com.freeftp.core.RemoteFile
import com.freeftp.core.RemoteFileAlreadyExistsException
import com.freeftp.core.RemoteFileNotFoundException
import com.freeftp.core.RemotePath
import com.freeftp.core.ServerProfile
import com.freeftp.core.TransferCancelledException
import com.freeftp.core.Transfers
import com.freeftp.core.TransportException
import com.freeftp.core.UnsupportedFeatureException
import com.freeftp.core.sortedForDisplay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException as SshTransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.sftp.RemoteFile as SshRemoteFile

/**
 * [RemoteClient] over SFTP (the SSH-2 file transfer subsystem), backed by SSHJ.
 *
 * Despite the name, SFTP shares nothing with FTP: it is a single multiplexed channel
 * inside an SSH connection, with structured status codes instead of reply strings and
 * no separate data connection.
 */
class SftpRemoteClient(
    private val profile: ServerProfile,
    private val hostKeyStore: HostKeyStore = InMemoryHostKeyStore(),
    private val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.TRUST_ON_FIRST_USE,
) : RemoteClient {

    init {
        require(profile.protocol == Protocol.SFTP) { "SftpRemoteClient does not speak ${profile.protocol}" }
    }

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    override val protocol: Protocol get() = Protocol.SFTP

    override val isConnected: Boolean get() = ssh?.isConnected == true && sftp != null

    private fun sftp(): SFTPClient = sftp ?: throw NotConnectedException()

    // ---------------------------------------------------------------- lifecycle

    override fun connect() {
        if (isConnected) return
        val verifier = TofuHostKeyVerifier(hostKeyStore, hostKeyPolicy)
        val client = SSHClient()
        client.connectTimeout = profile.connectTimeoutMillis
        client.timeout = profile.dataTimeoutMillis
        client.addHostKeyVerifier(verifier)
        try {
            client.connect(profile.host, profile.port)
            authenticate(client)
            sftp = client.newSFTPClient()
            ssh = client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            // A host-key rejection surfaces from SSHJ as a generic transport error; the
            // verifier recorded what actually went wrong, and that is what the user needs.
            verifier.failure?.let { throw it }
            throw when (e) {
                is RemoteException -> e
                else -> mapConnectFailure(e)
            }
        }
    }

    private fun authenticate(client: SSHClient) {
        val credentials = profile.credentials
        val methods = mutableListOf<AuthMethod>()
        var keyProvider: KeyProvider? = null

        if (credentials is Credentials.PrivateKey) {
            keyProvider = loadKey(client, credentials)
            methods += AuthPublickey(keyProvider)
        }
        val password = when (credentials) {
            is Credentials.Password -> credentials.password
            is Credentials.PrivateKey -> credentials.password
            is Credentials.Anonymous -> null
        }
        if (password != null) {
            methods += AuthPassword(reusablePasswordFinder(password))
            // Many OpenSSH deployments answer with keyboard-interactive rather than
            // "password"; the prompt is the same one, so reuse the same secret.
            // Each method needs its own finder: SSHJ's one-off finders blank their
            // buffer after a single read, which would send an empty second attempt.
            methods += AuthKeyboardInteractive(
                PasswordResponseProvider(reusablePasswordFinder(password))
            )
        }
        if (methods.isEmpty()) {
            throw AuthenticationException("No credentials configured for ${credentials.username}")
        }
        try {
            client.auth(credentials.username, methods)
        } catch (e: UserAuthException) {
            throw AuthenticationException(
                "The server rejected the credentials for ${credentials.username}",
                e,
            )
        }
    }

    /** A finder that hands out a fresh copy of the secret each time it is asked. */
    private fun reusablePasswordFinder(password: String): PasswordFinder =
        object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = password.toCharArray()

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }

    private fun loadKey(client: SSHClient, credentials: Credentials.PrivateKey): KeyProvider =
        try {
            if (credentials.passphrase.isNullOrEmpty()) {
                client.loadKeys(credentials.privateKey, null, null)
            } else {
                client.loadKeys(
                    credentials.privateKey,
                    null,
                    PasswordUtils.createOneOff(credentials.passphrase.toCharArray()),
                )
            }
        } catch (e: IOException) {
            throw AuthenticationException("The private key could not be read: ${e.message}", e)
        }

    override fun disconnect() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    // ---------------------------------------------------------------- navigation

    override fun workingDirectory(): String = wrap { RemotePath.normalize(sftp().canonicalize(".")) }

    override fun list(path: String): List<RemoteFile> = wrap {
        val dir = RemotePath.normalize(path)
        val entries = try {
            sftp().ls(dir)
        } catch (e: SFTPException) {
            throw mapSftp(e, dir)
        }
        entries.mapNotNull { toRemoteFile(dir, it) }.sortedForDisplay()
    }

    private fun toRemoteFile(directory: String, info: RemoteResourceInfo): RemoteFile? {
        val name = info.name
        if (name == "." || name == "..") return null
        val path = RemotePath.join(directory, name)
        val attributes = info.attributes
        val isSymlink = attributes.type == FileMode.Type.SYMLINK
        var isDirectory = attributes.type == FileMode.Type.DIRECTORY
        var target: String? = null
        if (isSymlink) {
            target = runCatching { sftp().readlink(path) }.getOrNull()
            // Follow the link so a symlinked directory is still browsable.
            isDirectory = runCatching { sftp().stat(path).type == FileMode.Type.DIRECTORY }
                .getOrDefault(false)
        }
        return RemoteFile(
            path = path,
            isDirectory = isDirectory,
            size = attributes.size,
            modifiedEpochMillis = attributes.mtime.takeIf { it > 0 }?.times(1000L),
            isSymlink = isSymlink,
            symlinkTarget = target,
            permissions = attributes.mode.permissionsMask.takeIf { it != 0 },
        )
    }

    override fun stat(path: String): RemoteFile = wrap {
        val target = RemotePath.normalize(path)
        val link = try {
            sftp().lstat(target)
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
        val isSymlink = link.type == FileMode.Type.SYMLINK
        val effective: FileAttributes = if (isSymlink) {
            runCatching { sftp().stat(target) }.getOrDefault(link)
        } else {
            link
        }
        RemoteFile(
            path = target,
            isDirectory = effective.type == FileMode.Type.DIRECTORY,
            size = effective.size,
            modifiedEpochMillis = effective.mtime.takeIf { it > 0 }?.times(1000L),
            isSymlink = isSymlink,
            symlinkTarget = if (isSymlink) runCatching { sftp().readlink(target) }.getOrNull() else null,
            permissions = effective.mode.permissionsMask.takeIf { it != 0 },
        )
    }

    override fun exists(path: String): Boolean = wrap {
        sftp().statExistence(RemotePath.normalize(path)) != null
    }

    // ---------------------------------------------------------------- mutations

    override fun makeDirectory(path: String) = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().mkdir(target)
        } catch (e: SFTPException) {
            if (exists(target)) throw RemoteFileAlreadyExistsException(target, e)
            throw mapSftp(e, target)
        }
    }

    override fun makeDirectories(path: String) = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().mkdirs(target)
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
    }

    override fun touch(path: String) = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().open(target, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)).close()
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
    }

    override fun deleteFile(path: String) = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().rm(target)
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
    }

    override fun removeDirectory(path: String) = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().rmdir(target)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.FAILURE &&
                runCatching { sftp().ls(target).isNotEmpty() }.getOrDefault(false)
            ) {
                throw DirectoryNotEmptyException(target, e)
            }
            throw mapSftp(e, target)
        }
    }

    override fun deleteRecursively(path: String) {
        val target = RemotePath.normalize(path)
        val entry = stat(target)
        if (!entry.isDirectory || entry.isSymlink) {
            deleteFile(target)
            return
        }
        for (child in list(target)) deleteRecursively(child.path)
        removeDirectory(target)
    }

    override fun rename(from: String, to: String) = wrap {
        val source = RemotePath.normalize(from)
        val destination = RemotePath.normalize(to)
        try {
            sftp().rename(source, destination)
        } catch (e: SFTPException) {
            throw mapSftp(e, destination)
        }
    }

    override fun setModificationTime(path: String, epochMillis: Long) = wrap {
        val target = RemotePath.normalize(path)
        val seconds = epochMillis / 1000L
        try {
            val current = sftp().stat(target)
            sftp().setattr(
                target,
                FileAttributes.Builder()
                    .withAtimeMtime(current.atime.takeIf { it > 0 } ?: seconds, seconds)
                    .build(),
            )
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
    }

    override fun setPermissions(path: String, mode: Int) = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().chmod(target, mode)
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
    }

    /**
     * Creates a symbolic link at [link] pointing at [target]. SFTP-only.
     *
     * The arguments are passed to SSHJ in the opposite order to what its signature
     * suggests. `SSH_FXP_SYMLINK` is specified as `linkpath` then `targetpath`, but
     * OpenSSH shipped them reversed and every server that matters now follows OpenSSH,
     * so the wire order has to be target-then-link to create the link people expect.
     */
    fun symlink(link: String, target: String) = wrap {
        try {
            sftp().symlink(target, RemotePath.normalize(link))
        } catch (e: SFTPException) {
            throw mapSftp(e, link)
        }
    }

    /** Reads the target of the symbolic link at [path]. SFTP-only. */
    fun readlink(path: String): String = wrap {
        val target = RemotePath.normalize(path)
        try {
            sftp().readlink(target)
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
    }

    // ---------------------------------------------------------------- transfers

    override fun download(
        remotePath: String,
        sink: OutputStream,
        offset: Long,
        listener: ProgressListener,
        cancellation: CancellationSignal,
    ) = wrap {
        val target = RemotePath.normalize(remotePath)
        val handle: SshRemoteFile = try {
            sftp().open(target, EnumSet.of(OpenMode.READ))
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
        handle.use { file ->
            val total = file.length()
            if (offset > total) {
                throw RemoteException("Cannot resume $target at $offset bytes: the file is $total bytes")
            }
            file.ReadAheadRemoteFileInputStream(READ_AHEAD_CHUNKS, offset).use { input ->
                Transfers.copy(input, sink, total, offset, listener, cancellation)
            }
        }
        Unit
    }

    override fun upload(
        source: InputStream,
        remotePath: String,
        size: Long,
        offset: Long,
        listener: ProgressListener,
        cancellation: CancellationSignal,
    ) = wrap {
        val target = RemotePath.normalize(remotePath)
        if (offset > 0) {
            val remoteSize = runCatching { sftp().size(target) }.getOrDefault(0L)
            if (offset > remoteSize) {
                throw RemoteException(
                    "Cannot resume $target at $offset bytes: only $remoteSize bytes are on the server"
                )
            }
        }
        val modes = if (offset > 0) {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        } else {
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        }
        val handle: SshRemoteFile = try {
            sftp().open(target, modes)
        } catch (e: SFTPException) {
            throw mapSftp(e, target)
        }
        handle.use { file ->
            file.RemoteFileOutputStream(offset, WRITE_CHUNK_SIZE).use { output ->
                Transfers.copy(source, output, size, offset, listener, cancellation)
            }
        }
        Unit
    }

    // ---------------------------------------------------------------- error mapping

    private inline fun <T> wrap(body: () -> T): T =
        try {
            body()
        } catch (e: RemoteException) {
            throw e
        } catch (e: SFTPException) {
            throw mapSftp(e, "")
        } catch (e: SshTransportException) {
            throw TransportException("Connection to ${profile.host} failed: ${e.message}", e)
        } catch (e: IOException) {
            if (e.cause is TransferCancelledException) throw e.cause as TransferCancelledException
            throw TransportException("Connection to ${profile.host} failed: ${e.message}", e)
        }

    private fun mapConnectFailure(e: Exception): RemoteException = when (e) {
        is UnknownHostException -> TransportException("Unknown host: ${profile.host}", e)
        is ConnectException -> TransportException(
            "Could not connect to ${profile.host}:${profile.port}: connection refused",
            e,
        )
        is SocketTimeoutException -> TransportException(
            "Timed out connecting to ${profile.host}:${profile.port}",
            e,
        )
        is UserAuthException -> AuthenticationException(
            "The server rejected the credentials for ${profile.credentials.username}",
            e,
        )
        else -> TransportException("Could not connect to ${profile.host}:${profile.port}: ${e.message}", e)
    }

    /** SFTP has real status codes, so the mapping is exact rather than string-sniffed. */
    private fun mapSftp(e: SFTPException, path: String): RemoteException = when (e.statusCode) {
        Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH ->
            RemoteFileNotFoundException(path, e)
        Response.StatusCode.PERMISSION_DENIED, Response.StatusCode.WRITE_PROTECT ->
            PermissionDeniedException(path, e)
        Response.StatusCode.FILE_ALREADY_EXISTS -> RemoteFileAlreadyExistsException(path, e)
        Response.StatusCode.NOT_A_DIRECTORY -> NotADirectoryException(path, e)
        Response.StatusCode.DIR_NOT_EMPTY -> DirectoryNotEmptyException(path, e)
        Response.StatusCode.INVALID_FILENAME -> InvalidFileNameException(path, e)
        Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED ->
            QuotaExceededException(path, e)
        Response.StatusCode.OP_UNSUPPORTED -> UnsupportedFeatureException(
            "this operation".takeIf { path.isEmpty() } ?: "this operation on $path",
            e,
        )
        else -> ProtocolFailureException(
            e.statusCode.ordinal,
            e.message.orEmpty().ifBlank { e.statusCode.name },
            e,
        )
    }

    private companion object {
        /** Outstanding read-ahead requests; keeps the pipe full over a high-latency link. */
        const val READ_AHEAD_CHUNKS = 16

        /** Bytes per outgoing SFTP write packet. */
        const val WRITE_CHUNK_SIZE = 32 * 1024
    }
}
