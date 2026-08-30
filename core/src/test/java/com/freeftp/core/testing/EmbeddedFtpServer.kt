package com.freeftp.core.testing

import java.nio.file.Files
import java.nio.file.Path
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.command.Command
import org.apache.ftpserver.command.CommandFactoryFactory
import org.apache.ftpserver.impl.DefaultFtpServer
import org.apache.ftpserver.impl.FtpIoSession
import org.apache.ftpserver.impl.FtpServerContext
import org.apache.ftpserver.ftplet.DefaultFtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory

/**
 * A real Apache FtpServer bound to a loopback port, serving a temporary directory.
 *
 * The test suite deliberately does not mock the protocol: every FTP case runs against
 * this, so parser, reply-code and data-connection behaviour are all exercised for real.
 */
class EmbeddedFtpServer(
    val root: Path,
    private val tls: TlsMode = TlsMode.NONE,
    /** When false the server answers `MLSD`/`MLST`/`FEAT` as an old server would, forcing `LIST`. */
    private val mlsdSupported: Boolean = true,
    /** A specific port, for the hand-driven dev server; tests take an ephemeral one. */
    private val fixedPort: Int? = null,
    /**
     * A fixed `PASV` data-port range, e.g. `"30000-30009"`.
     *
     * Needed when the client reaches the server through a port forward: passive FTP
     * opens a second connection on a port the server picks, so that port has to be
     * predictable enough to forward as well.
     */
    private val passivePorts: String? = null,
) : AutoCloseable {

    enum class TlsMode { NONE, EXPLICIT, IMPLICIT }

    var port: Int = 0
        private set

    private var server: FtpServer? = null

    fun start(): EmbeddedFtpServer {
        val factory = FtpServerFactory()

        val userManager = PropertiesUserManagerFactory().createUserManager()
        userManager.save(
            BaseUser().apply {
                name = USER
                password = PASSWORD
                homeDirectory = root.toString()
                authorities = listOf(WritePermission())
            }
        )
        userManager.save(
            BaseUser().apply {
                name = READ_ONLY_USER
                password = PASSWORD
                homeDirectory = root.toString()
                authorities = emptyList()
            }
        )
        userManager.save(
            BaseUser().apply {
                name = "anonymous"
                homeDirectory = root.toString()
                authorities = listOf(WritePermission())
            }
        )
        factory.userManager = userManager

        factory.connectionConfig = ConnectionConfigFactory().apply {
            isAnonymousLoginEnabled = true
            maxAnonymousLogins = 10
            maxLogins = 50
            // Tests intentionally send wrong passwords; do not throttle them.
            loginFailureDelay = 0
            maxLoginFailures = 100
        }.createConnectionConfig()

        if (!mlsdSupported) {
            factory.commandFactory = CommandFactoryFactory().apply {
                isUseDefaultCommands = true
                addCommand("MLSD", notImplemented())
                addCommand("MLST", notImplemented())
                addCommand("FEAT", featureListWithoutMlsd())
            }.createCommandFactory()
        }

        val listenerFactory = ListenerFactory().apply {
            serverAddress = "127.0.0.1"
            this@apply.port = fixedPort ?: freePort()
            dataConnectionConfiguration = DataConnectionConfigurationFactory().apply {
                passiveAddress = "127.0.0.1"
                passiveExternalAddress = "127.0.0.1"
                // Qualified: inside this apply block a bare `passivePorts` would resolve
                // to the factory's own property, not the constructor argument.
                this@EmbeddedFtpServer.passivePorts?.let { passivePorts = it }
            }.createDataConnectionConfiguration()
            when (tls) {
                TlsMode.NONE -> Unit
                TlsMode.EXPLICIT, TlsMode.IMPLICIT -> {
                    val keystore = Files.createTempFile(root.parent, "ftps", ".p12")
                    TestCertificates.writeSelfSignedKeystore(keystore)
                    sslConfiguration = SslConfigurationFactory().apply {
                        keystoreFile = keystore.toFile()
                        keystorePassword = TestCertificates.KEYSTORE_PASSWORD
                        keystoreType = "PKCS12"
                        sslProtocol = "TLSv1.2"
                    }.createSslConfiguration()
                    isImplicitSsl = tls == TlsMode.IMPLICIT
                }
            }
        }
        port = listenerFactory.port
        factory.addListener("default", listenerFactory.createListener())

        server = (factory.createServer() as DefaultFtpServer).also { it.start() }
        return this
    }

    override fun close() {
        server?.stop()
        server = null
    }

    private fun notImplemented() = Command { session: FtpIoSession, _: FtpServerContext, request: FtpRequest ->
        session.write(DefaultFtpReply(502, "${request.command} not implemented"))
        Unit
    }

    /** A `FEAT` reply advertising only `SIZE`/`REST`, as a pre-RFC-3659 server would. */
    private fun featureListWithoutMlsd() = Command { session: FtpIoSession, _: FtpServerContext, _: FtpRequest ->
        session.write(DefaultFtpReply(211, "Extensions supported\n SIZE\n REST STREAM\nEnd"))
        Unit
    }

    companion object {
        const val USER: String = "ftpuser"
        const val PASSWORD: String = "ftp-secret"
        const val READ_ONLY_USER: String = "ftpreader"
    }
}
