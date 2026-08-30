package com.freeftp.core.ftp

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
import com.freeftp.core.TlsException
import com.freeftp.core.TransferCancelledException
import com.freeftp.core.Transfers
import com.freeftp.core.TransportException
import com.freeftp.core.UnsupportedFeatureException
import com.freeftp.core.sortedForDisplay
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils

/**
 * [RemoteClient] over FTP, FTPS-explicit and FTPS-implicit, backed by Apache Commons Net.
 *
 * One instance owns one control connection and is not thread-safe.
 */
class FtpRemoteClient(private val profile: ServerProfile) : RemoteClient {

    init {
        require(profile.protocol.isFtpFamily) { "FtpRemoteClient does not speak ${profile.protocol}" }
    }

    private var ftp: FTPClient? = null

    override val protocol: Protocol get() = profile.protocol

    override val isConnected: Boolean get() = ftp?.isConnected == true

    /** The control-channel charset actually in use, after `FEAT`-based UTF-8 negotiation. */
    val controlEncoding: String get() = client().controlEncoding

    /** True when the server advertised `MLSD`, the machine-readable listing command. */
    var supportsMlsd: Boolean = false
        private set

    /** True when directory listings actually go through `MLSD` rather than `LIST`. */
    val useMlsdForListing: Boolean get() = supportsMlsd && !profile.showHiddenFiles

    private fun client(): FTPClient = ftp ?: throw NotConnectedException()

    // ---------------------------------------------------------------- lifecycle

    override fun connect() {
        if (isConnected) return
        val c = when (profile.protocol) {
            Protocol.FTPS_EXPLICIT -> FTPSClient(false)
            Protocol.FTPS_IMPLICIT -> FTPSClient(true)
            else -> FTPClient()
        }
        if (c is FTPSClient) {
            // Commons Net ships a permissive default here, so both branches are set
            // explicitly: an FTPS connection that silently accepts any certificate is
            // worse than a plain one, because it looks safe.
            if (profile.trustAllCertificates) {
                c.trustManager = TrustManagerUtils.getAcceptAllTrustManager()
            } else {
                c.trustManager = TrustManagerUtils.getDefaultTrustManager(null)
                c.isEndpointCheckingEnabled = true
            }
        }
        c.connectTimeout = profile.connectTimeoutMillis
        c.controlEncoding = profile.controlEncoding
        c.autodetectUTF8 = true
        c.bufferSize = Transfers.BUFFER_SIZE
        c.setListHiddenFiles(profile.showHiddenFiles)

        try {
            c.connect(profile.host, profile.port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) {
                val code = c.replyCode
                val text = c.replyString.orEmpty().trim()
                runCatching { c.disconnect() }
                throw TransportException("${profile.host}:${profile.port} refused the connection: $code $text")
            }
            c.soTimeout = profile.dataTimeoutMillis
            c.setDataTimeout(Duration.ofMillis(profile.dataTimeoutMillis.toLong()))

            login(c)

            if (c is FTPSClient) {
                // Protect the data channel too, otherwise only the credentials are encrypted.
                c.execPBSZ(0)
                c.execPROT("P")
            }
            if (profile.passiveMode) c.enterLocalPassiveMode() else c.enterLocalActiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            // RFC 3659 has servers advertise MLST (with the facts they support) and treats
            // MLSD as implied, so many list only MLST even though both work.
            supportsMlsd = runCatching { c.hasFeature("MLSD") || c.hasFeature("MLST") }
                .getOrDefault(false)
            ftp = c
        } catch (e: RemoteException) {
            runCatching { c.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw mapConnectFailure(e)
        }
    }

    private fun login(c: FTPClient) {
        val ok = when (val credentials = profile.credentials) {
            is Credentials.Anonymous ->
                c.login(Credentials.Anonymous.username, Credentials.Anonymous.PASSWORD)
            is Credentials.Password -> c.login(credentials.username, credentials.password)
            is Credentials.PrivateKey ->
                throw AuthenticationException("FTP does not support private-key authentication")
        }
        if (!ok) {
            // Deliberately reports only the server's own text: never the credentials.
            throw AuthenticationException(
                "The server rejected the credentials for ${profile.credentials.username}"
            )
        }
    }

    override fun disconnect() {
        val c = ftp ?: return
        ftp = null
        runCatching { if (c.isConnected) c.logout() }
        runCatching { if (c.isConnected) c.disconnect() }
    }

    // ---------------------------------------------------------------- navigation

    override fun workingDirectory(): String = wrap { RemotePath.normalize(client().printWorkingDirectory()) }

    override fun list(path: String): List<RemoteFile> = wrap {
        val c = client()
        val dir = RemotePath.normalize(path)
        changeDirectoryOrThrow(dir)
        // MLSD reports exact sizes and UTC timestamps, so it wins whenever it can be
        // used; only the hidden-file request forces the older LIST path.
        val entries = if (useMlsdForListing) c.mlistDir() else c.listFiles()
        // Only a positive completion (226) means the data connection actually delivered
        // the whole listing. Without this check a data connection that never opened
        // reads as "this folder is empty", which is the one wrong answer a file manager
        // must never give: the user deletes or re-uploads on the strength of it.
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            throw failure(lastReply(c), dir)
        }
        (entries ?: emptyArray())
            .mapNotNull { ListingParser.toRemoteFile(dir, it) }
            .sortedForDisplay()
    }

    /**
     * `CWD` doubles as the existence-and-is-a-directory check: it is the one command every
     * FTP server implements consistently, whereas `LIST` on a file silently succeeds.
     */
    private fun changeDirectoryOrThrow(dir: String) {
        val c = client()
        if (c.changeWorkingDirectory(dir)) return
        val reply = lastReply(c)
        if (sizeOrNull(dir) != null) throw NotADirectoryException(dir)
        throw failure(reply, dir)
    }

    override fun stat(path: String): RemoteFile = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        if (target == RemotePath.ROOT) return@wrap RemoteFile(RemotePath.ROOT, isDirectory = true)

        if (runCatching { c.hasFeature("MLST") }.getOrDefault(false)) {
            val entry = c.mlistFile(target)
            if (entry != null) {
                ListingParser.toRemoteFile(RemotePath.parent(target), entry)?.let { return@wrap it }
            }
        }
        // Fall back to listing the parent, which every server supports.
        val name = RemotePath.name(target)
        list(RemotePath.parent(target)).firstOrNull { it.name == name }
            ?: throw RemoteFileNotFoundException(target)
    }

    override fun exists(path: String): Boolean =
        try {
            stat(path)
            true
        } catch (_: RemoteFileNotFoundException) {
            false
        } catch (_: NotADirectoryException) {
            true
        }

    // ---------------------------------------------------------------- mutations

    override fun makeDirectory(path: String) = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        if (!c.makeDirectory(target)) {
            val reply = lastReply(c)
            if (existsQuietly(target)) throw RemoteFileAlreadyExistsException(target)
            throw failure(reply, target)
        }
    }

    override fun makeDirectories(path: String) = wrap {
        val target = RemotePath.normalize(path)
        var current = RemotePath.ROOT
        for (segment in RemotePath.segments(target)) {
            current = RemotePath.join(current, segment)
            if (client().changeWorkingDirectory(current)) continue
            try {
                makeDirectory(current)
            } catch (_: RemoteFileAlreadyExistsException) {
                // Raced with someone else, or the server reports an existing dir oddly.
            }
        }
    }

    override fun touch(path: String) = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        if (!c.storeFile(target, ByteArrayInputStream(ByteArray(0)))) {
            throw failure(lastReply(c), target, probeExistence = true)
        }
    }

    override fun deleteFile(path: String) = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        if (!c.deleteFile(target)) throw failure(lastReply(c), target, probeExistence = true)
    }

    override fun removeDirectory(path: String) = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        // Servers refuse to remove the directory the session is sitting in, and listing
        // a directory leaves the session exactly there. Step out to the parent first.
        if (target != RemotePath.ROOT) c.changeWorkingDirectory(RemotePath.parent(target))
        if (!c.removeDirectory(target)) {
            val reply = lastReply(c)
            if (runCatching { list(target).isNotEmpty() }.getOrDefault(false)) {
                throw DirectoryNotEmptyException(target)
            }
            throw failure(reply, target, probeExistence = true)
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
        val c = client()
        val source = RemotePath.normalize(from)
        val destination = RemotePath.normalize(to)
        if (!c.rename(source, destination)) {
            val reply = lastReply(c)
            if (!existsQuietly(source)) throw RemoteFileNotFoundException(source)
            throw failure(reply, destination)
        }
    }

    override fun setModificationTime(path: String, epochMillis: Long) = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        val stamp = MDTM_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))
        // MFMT (RFC draft) is the command meant for this; MDTM-with-argument is the
        // older de-facto equivalent that many servers accept instead.
        if (FTPReply.isPositiveCompletion(c.sendCommand("MFMT", "$stamp $target"))) return@wrap
        if (c.setModificationTime(target, stamp)) return@wrap
        val reply = lastReply(c)
        if (reply.code == FTPReply.FILE_UNAVAILABLE) throw failure(reply, target, probeExistence = true)
        throw UnsupportedFeatureException("setting the modification time (MFMT/MDTM)")
    }

    override fun setPermissions(path: String, mode: Int) = wrap {
        val c = client()
        val target = RemotePath.normalize(path)
        val octal = Integer.toOctalString(mode).padStart(3, '0')
        if (!FTPReply.isPositiveCompletion(c.sendCommand("SITE", "CHMOD $octal $target"))) {
            val reply = lastReply(c)
            if (reply.code == FTPReply.FILE_UNAVAILABLE) {
                throw failure(reply, target, probeExistence = true)
            }
            throw UnsupportedFeatureException("changing permissions (SITE CHMOD)")
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
        val c = client()
        val target = RemotePath.normalize(remotePath)
        val total = sizeOrNull(target) ?: -1L
        if (offset > 0) {
            if (total in 0 until offset) {
                throw RemoteException("Cannot resume $target at $offset bytes: the file is $total bytes")
            }
            c.restartOffset = offset
        }
        val stream = c.retrieveFileStream(target)
            ?: throw failure(lastReply(c), target, probeExistence = true)
        transfer(target, stream) { Transfers.copy(stream, sink, total, offset, listener, cancellation) }
        completeOrThrow(target)
    }

    override fun upload(
        source: InputStream,
        remotePath: String,
        size: Long,
        offset: Long,
        listener: ProgressListener,
        cancellation: CancellationSignal,
    ) = wrap {
        val c = client()
        val target = RemotePath.normalize(remotePath)
        if (offset > 0) {
            val remoteSize = sizeOrNull(target) ?: 0L
            if (offset > remoteSize) {
                throw RemoteException(
                    "Cannot resume $target at $offset bytes: only $remoteSize bytes are on the server"
                )
            }
            c.restartOffset = offset
        }
        val stream = (if (offset > 0) c.appendFileStream(target) else c.storeFileStream(target))
            ?: throw failure(lastReply(c), target)
        transfer(target, stream) { Transfers.copy(source, stream, size, offset, listener, cancellation) }
        completeOrThrow(target)
    }

    /**
     * Runs a transfer body, tearing the connection down on cancellation.
     *
     * A cancelled FTP transfer leaves an unfinished reply on the control channel; rather
     * than gamble on `ABOR` being handled (many servers ignore it), the connection is
     * dropped and the caller reconnects. That keeps every later command trustworthy.
     */
    private inline fun transfer(path: String, stream: Closeable, body: () -> Long): Long =
        try {
            body().also { runCatching { stream.close() } }
        } catch (e: TransferCancelledException) {
            abandonConnection(stream)
            throw e
        } catch (e: IOException) {
            abandonConnection(stream)
            throw TransportException("Transfer of $path failed: ${e.message}", e)
        }

    /**
     * Drops the connection without the usual `QUIT` handshake.
     *
     * A cancelled or broken transfer leaves the control channel mid-reply, so a polite
     * logout would simply block until the socket timeout expires — half a minute of the
     * user staring at a cancel button that already looks pressed.
     */
    private fun abandonConnection(stream: Closeable) {
        runCatching { stream.close() }
        val c = ftp
        ftp = null
        runCatching { c?.disconnect() }
    }

    private fun completeOrThrow(path: String) {
        val c = client()
        if (!c.completePendingCommand()) throw failure(lastReply(c), path)
    }

    private fun sizeOrNull(path: String): Long? = runCatching {
        client().getSize(path)?.trim()?.toLongOrNull()
    }.getOrNull()

    // ---------------------------------------------------------------- error mapping

    private inline fun <T> wrap(body: () -> T): T =
        try {
            body()
        } catch (e: RemoteException) {
            throw e
        } catch (e: SSLException) {
            throw TlsException("TLS error talking to ${profile.host}: ${e.message}", e)
        } catch (e: IOException) {
            throw TransportException("Connection to ${profile.host} failed: ${e.message}", e)
        }

    private fun mapConnectFailure(e: Exception): RemoteException = when (e) {
        // A rejected certificate is the single most common FTPS setup problem, and the
        // JSSE text for it ("No subjectAltNames ... match", "unable to find valid
        // certification path") tells a user nothing about what to do next.
        is SSLException -> TlsException(
            "Could not establish a secure connection to ${profile.host}:${profile.port}: " +
                "${e.message}." +
                if (profile.trustAllCertificates) {
                    ""
                } else {
                    " If this server uses a self-signed certificate, turn on " +
                        "\"Accept self-signed certificate\" for this connection."
                },
            e,
        )
        is UnknownHostException -> TransportException("Unknown host: ${profile.host}", e)
        is ConnectException -> TransportException(
            "Could not connect to ${profile.host}:${profile.port}: connection refused",
            e,
        )
        is SocketTimeoutException -> TransportException(
            "Timed out connecting to ${profile.host}:${profile.port}",
            e,
        )
        else -> TransportException("Could not connect to ${profile.host}:${profile.port}: ${e.message}", e)
    }

    /**
     * The server's reply to the command that just failed.
     *
     * Snapshotted the instant a command fails: mapping an error sometimes needs another
     * round trip (does the path exist?), and that round trip would otherwise overwrite
     * the very reply being diagnosed.
     */
    private data class Reply(val code: Int, val text: String)

    private fun lastReply(c: FTPClient) = Reply(c.replyCode, c.replyString.orEmpty().trim())

    /**
     * Turns a server reply into the most specific exception it justifies.
     *
     * FTP's 450 and 550 both mean "file action not taken" and cover missing files,
     * permission problems and locked files alike, so when [probeExistence] is set the
     * path is checked to tell "there is no such file" from "you may not touch it" —
     * a distinction the user very much cares about.
     */
    private fun failure(reply: Reply, path: String, probeExistence: Boolean = false): RemoteException {
        val denied = reply.text.contains("permission", ignoreCase = true) ||
            reply.text.contains("denied", ignoreCase = true)
        return when (reply.code) {
            FTPReply.NOT_LOGGED_IN -> AuthenticationException("Not logged in")
            FTPReply.NEED_ACCOUNT_FOR_STORING_FILES -> PermissionDeniedException(path)
            FTPReply.FILE_NAME_NOT_ALLOWED -> InvalidFileNameException(path)
            FTPReply.INSUFFICIENT_STORAGE, FTPReply.STORAGE_ALLOCATION_EXCEEDED ->
                QuotaExceededException(path)
            FTPReply.UNRECOGNIZED_COMMAND,
            FTPReply.COMMAND_NOT_IMPLEMENTED,
            FTPReply.COMMAND_NOT_IMPLEMENTED_FOR_PARAMETER,
            -> UnsupportedFeatureException(reply.text.ifBlank { "this command" })

            FTPReply.FILE_UNAVAILABLE, FTPReply.FILE_ACTION_NOT_TAKEN -> when {
                denied -> PermissionDeniedException(path)
                probeExistence && !existsQuietly(path) -> RemoteFileNotFoundException(path)
                probeExistence -> PermissionDeniedException(path)
                else -> RemoteFileNotFoundException(path)
            }

            else -> ProtocolFailureException(reply.code, reply.text)
        }
    }

    /** Existence check that never throws, for use while diagnosing another failure. */
    private fun existsQuietly(path: String): Boolean = runCatching { exists(path) }.getOrDefault(false)

    private companion object {
        val MDTM_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
