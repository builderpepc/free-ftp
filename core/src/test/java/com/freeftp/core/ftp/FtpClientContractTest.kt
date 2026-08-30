package com.freeftp.core.ftp

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemoteClient
import com.freeftp.core.RemoteClientContractTest
import com.freeftp.core.ServerProfile
import com.freeftp.core.testing.EmbeddedFtpServer
import java.nio.file.Path

/**
 * The shared [RemoteClientContractTest] run over plain FTP against a real Apache FtpServer,
 * in the `LIST -a` configuration. [FtpMlsdContractTest] covers the `MLSD` path.
 */
open class FtpClientContractTest : RemoteClientContractTest() {

    private var ftp: EmbeddedFtpServer? = null

    override val secret: String get() = EmbeddedFtpServer.PASSWORD

    /** Apache FtpServer implements no `SITE CHMOD`, and neither do many real servers. */
    override val supportsPermissions: Boolean get() = false

    /** `LIST -a` when true, `MLSD` when false. Both must satisfy the contract. */
    protected open val showHiddenFiles: Boolean get() = true

    override val listsHiddenFiles: Boolean get() = showHiddenFiles

    override fun startServer(root: Path): AutoCloseable =
        EmbeddedFtpServer(root).start().also { ftp = it }

    override fun createClient(): RemoteClient = FtpRemoteClient(
        ServerProfile(
            id = "ftp",
            name = "Test FTP",
            protocol = Protocol.FTP,
            host = "127.0.0.1",
            port = ftp!!.port,
            credentials = Credentials.Password(EmbeddedFtpServer.USER, EmbeddedFtpServer.PASSWORD),
            showHiddenFiles = showHiddenFiles,
        )
    )
}
