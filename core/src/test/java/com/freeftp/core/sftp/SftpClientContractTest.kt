package com.freeftp.core.sftp

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemoteClient
import com.freeftp.core.RemoteClientContractTest
import com.freeftp.core.ServerProfile
import com.freeftp.core.testing.EmbeddedSftpServer
import java.nio.file.Path

/** The shared [RemoteClientContractTest] run over SFTP against a real Apache MINA SSHD. */
class SftpClientContractTest : RemoteClientContractTest() {

    private var sftp: EmbeddedSftpServer? = null

    override val secret: String get() = EmbeddedSftpServer.PASSWORD

    override fun startServer(root: Path): AutoCloseable =
        EmbeddedSftpServer(root, hostKeyFile = workspace.resolve("hostkey.ser")).start().also { sftp = it }

    override fun createClient(): RemoteClient = SftpRemoteClient(
        ServerProfile(
            id = "sftp",
            name = "Test SFTP",
            protocol = Protocol.SFTP,
            host = "127.0.0.1",
            port = sftp!!.port,
            credentials = Credentials.Password(EmbeddedSftpServer.USER, EmbeddedSftpServer.PASSWORD),
        ),
        hostKeyStore = InMemoryHostKeyStore(),
    )
}
