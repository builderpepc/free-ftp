package com.freeftp.core.ftp

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemoteException
import com.freeftp.core.ServerProfile
import com.freeftp.core.TlsException
import com.freeftp.core.testing.EmbeddedFtpServer
import com.freeftp.core.testing.RecordingOutputStream
import com.freeftp.core.testing.randomBytes
import com.freeftp.core.testing.sha256
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Test plan section 4 — FTPS, explicit and implicit, against a real TLS-enabled server. */
class FtpsConnectionTest {

    @TempDir
    lateinit var workspace: Path

    private lateinit var root: Path
    private var server: EmbeddedFtpServer? = null

    @BeforeEach
    fun setUp() {
        root = workspace.resolve("served").also { it.createDirectories() }
    }

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun start(mode: EmbeddedFtpServer.TlsMode): EmbeddedFtpServer =
        EmbeddedFtpServer(root, tls = mode).start().also { server = it }

    private fun profile(
        port: Int,
        protocol: Protocol,
        trustAll: Boolean = true,
        timeoutMillis: Int = 10_000,
    ) = ServerProfile(
        id = "ftps",
        name = "Test FTPS",
        protocol = protocol,
        host = "127.0.0.1",
        port = port,
        credentials = Credentials.Password(EmbeddedFtpServer.USER, EmbeddedFtpServer.PASSWORD),
        trustAllCertificates = trustAll,
        connectTimeoutMillis = timeoutMillis,
        dataTimeoutMillis = timeoutMillis,
    )

    /** Connects, lists and moves bytes both ways — 4.5's proof that `PROT P` did not break the data channel. */
    private fun exerciseTransfers(client: FtpRemoteClient) {
        val bytes = randomBytes(256 * 1024, seed = 41)
        root.resolve("secret.bin").writeBytes(bytes)

        assertTrue(client.list("/").any { it.name == "secret.bin" })
        val sink = RecordingOutputStream()
        client.download("/secret.bin", sink)
        assertEquals(sha256(bytes), sha256(sink.bytes))

        client.upload(ByteArrayInputStream(bytes), "/uploaded.bin", bytes.size.toLong())
        assertEquals(sha256(bytes), sha256(root.resolve("uploaded.bin").readBytes()))
    }

    @Test // 4.1, 4.5
    @Timeout(60)
    fun `explicit FTPS connects lists and transfers over a protected data channel`() {
        val port = start(EmbeddedFtpServer.TlsMode.EXPLICIT).port
        FtpRemoteClient(profile(port, Protocol.FTPS_EXPLICIT)).use {
            it.connect()
            assertTrue(it.isConnected)
            exerciseTransfers(it)
        }
    }

    @Test // 4.2, 4.5
    @Timeout(60)
    fun `implicit FTPS connects lists and transfers`() {
        val port = start(EmbeddedFtpServer.TlsMode.IMPLICIT).port
        FtpRemoteClient(profile(port, Protocol.FTPS_IMPLICIT)).use {
            it.connect()
            assertTrue(it.isConnected)
            exerciseTransfers(it)
        }
    }

    @Test // 4.3
    @Timeout(60)
    fun `an untrusted certificate is refused when verification is on`() {
        val port = start(EmbeddedFtpServer.TlsMode.EXPLICIT).port
        val client = FtpRemoteClient(profile(port, Protocol.FTPS_EXPLICIT, trustAll = false))
        val failure = assertThrows<TlsException> { client.connect() }
        assertTrue(failure.message!!.isNotBlank())
        // The message has to point at the fix, not just restate the JSSE error.
        assertTrue(
            failure.message!!.contains("self-signed", ignoreCase = true),
            "the failure should tell the user how to proceed, got: ${failure.message}",
        )
    }

    @Test // 4.4
    @Timeout(60)
    fun `the same certificate is accepted once the user trusts it`() {
        val port = start(EmbeddedFtpServer.TlsMode.EXPLICIT).port
        FtpRemoteClient(profile(port, Protocol.FTPS_EXPLICIT, trustAll = true)).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test // 4.6
    @Timeout(90)
    fun `a plain FTP client against a TLS-only listener fails cleanly`() {
        val port = start(EmbeddedFtpServer.TlsMode.IMPLICIT).port
        val client = FtpRemoteClient(profile(port, Protocol.FTP, timeoutMillis = 5_000))
        assertThrows<RemoteException> { client.connect() }
        assertTrue(!client.isConnected)
    }
}
