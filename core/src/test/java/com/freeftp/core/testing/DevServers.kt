package com.freeftp.core.testing

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Runs the same real FTP and SFTP servers the test suite uses, on fixed ports, so the
 * Android app can be driven against them by hand or from a device.
 *
 * Usage: `./gradlew :core:devServers -PserveDir=/tmp/ftproot -PftpPort=2121 -PsftpPort=2222`
 */
object DevServers {

    /** Fixed so the range can be forwarded to a device with `adb reverse`. */
    const val PASSIVE_PORTS: String = "30000-30009"
    const val FTPS_PASSIVE_PORTS: String = "30010-30019"

    @JvmStatic
    fun main(args: Array<String>) {
        val root: Path = Paths.get(args.getOrElse(0) { "/tmp/freeftp-root" })
        val ftpPort = args.getOrElse(1) { "2121" }.toInt()
        val sftpPort = args.getOrElse(2) { "2222" }.toInt()
        Files.createDirectories(root)

        val ftp = EmbeddedFtpServer(root, fixedPort = ftpPort, passivePorts = PASSIVE_PORTS).start()
        val ftps = EmbeddedFtpServer(
            root = root,
            tls = EmbeddedFtpServer.TlsMode.EXPLICIT,
            fixedPort = ftpPort + 1,
            passivePorts = FTPS_PASSIVE_PORTS,
        ).start()
        val sftp = EmbeddedSftpServer(
            root = root,
            hostKeyFile = root.resolveSibling("freeftp-devserver-hostkey.ser"),
            fixedPort = sftpPort,
        ).start()

        println("FTP  ready on 127.0.0.1:${ftp.port}  user=${EmbeddedFtpServer.USER} password=${EmbeddedFtpServer.PASSWORD}")
        println("FTPS ready on 127.0.0.1:${ftps.port} (explicit TLS, self-signed) user=${EmbeddedFtpServer.USER} password=${EmbeddedFtpServer.PASSWORD}")
        println("SFTP ready on 127.0.0.1:${sftp.port} user=${EmbeddedSftpServer.USER} password=${EmbeddedSftpServer.PASSWORD}")
        println("SFTP host key fingerprint: ${sftp.hostKeyFingerprint()}")
        println("FTP passive data ports: $PASSIVE_PORTS; FTPS: $FTPS_PASSIVE_PORTS")
        println("Serving $root")
        println("READY")
        System.out.flush()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                ftp.close()
                ftps.close()
                sftp.close()
            }
        )
        Thread.currentThread().join()
    }
}
