package com.freeftp.core.ftp

import com.freeftp.core.AuthenticationException
import com.freeftp.core.Credentials
import com.freeftp.core.NotADirectoryException
import com.freeftp.core.PermissionDeniedException
import com.freeftp.core.Protocol
import com.freeftp.core.RemoteFileNotFoundException
import com.freeftp.core.ServerProfile
import com.freeftp.core.TransportException
import com.freeftp.core.UnsupportedFeatureException
import com.freeftp.core.testing.EmbeddedFtpServer
import com.freeftp.core.testing.RecordingOutputStream
import com.freeftp.core.testing.freePort
import com.freeftp.core.testing.randomBytes
import com.freeftp.core.testing.sha256
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** FTP connection, authentication, listing fallbacks and error mapping. */
class FtpConnectionTest {

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

    private fun start(mlsdSupported: Boolean = true): EmbeddedFtpServer =
        EmbeddedFtpServer(root, mlsdSupported = mlsdSupported).start().also { server = it }

    private fun profile(
        port: Int,
        credentials: Credentials = Credentials.Password(EmbeddedFtpServer.USER, EmbeddedFtpServer.PASSWORD),
        host: String = "127.0.0.1",
        passive: Boolean = true,
        connectTimeoutMillis: Int = 15_000,
    ) = ServerProfile(
        id = "ftp",
        name = "Test FTP",
        protocol = Protocol.FTP,
        host = host,
        port = port,
        credentials = credentials,
        passiveMode = passive,
        connectTimeoutMillis = connectTimeoutMillis,
    )

    @Test
    fun `valid credentials connect`() {
        val ftp = FtpRemoteClient(profile(start().port))
        ftp.use {
            it.connect()
            assertTrue(it.isConnected)
            assertEquals("/", it.workingDirectory())
        }
    }

    @Test
    fun `a wrong password reports an authentication failure not an IO error`() {
        val port = start().port
        val client = FtpRemoteClient(
            profile(port, Credentials.Password(EmbeddedFtpServer.USER, "not-the-password"))
        )
        val failure = assertThrows<AuthenticationException> { client.connect() }
        assertFalse(failure.message!!.contains("not-the-password"))
        assertFalse(client.isConnected)
    }

    @Test
    @Timeout(30)
    fun `connecting to a closed port fails fast and names the endpoint`() {
        val port = freePort() // nothing is listening here
        val failure = assertThrows<TransportException> { FtpRemoteClient(profile(port)).connect() }
        assertTrue(failure.message!!.contains("127.0.0.1:$port"), failure.message)
    }

    @Test
    fun `anonymous login works where the server allows it`() {
        val ftp = FtpRemoteClient(profile(start().port, Credentials.Anonymous))
        ftp.use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test
    fun `passive mode lists and transfers`() {
        val bytes = randomBytes(128 * 1024, seed = 31)
        root.resolve("passive.bin").writeBytes(bytes)
        FtpRemoteClient(profile(start().port, passive = true)).use {
            it.connect()
            assertEquals(listOf("passive.bin"), it.list("/").map { entry -> entry.name })
            val sink = RecordingOutputStream()
            it.download("/passive.bin", sink)
            assertEquals(sha256(bytes), sha256(sink.bytes))
        }
    }

    @Test
    fun `active mode lists and transfers`() {
        val bytes = randomBytes(128 * 1024, seed = 37)
        root.resolve("active.bin").writeBytes(bytes)
        FtpRemoteClient(profile(start().port, passive = false)).use {
            it.connect()
            assertEquals(listOf("active.bin"), it.list("/").map { entry -> entry.name })
            val sink = RecordingOutputStream()
            it.download("/active.bin", sink)
            assertEquals(sha256(bytes), sha256(sink.bytes))
            it.upload(ByteArrayInputStream(bytes), "/active-up.bin", bytes.size.toLong())
            assertEquals(sha256(bytes), sha256(root.resolve("active-up.bin").toFile().readBytes()))
        }
    }

    @Test
    @Timeout(60)
    fun `the connect timeout is honoured for an unreachable host`() {
        // 198.51.100.0/24 is TEST-NET-2: reserved for documentation, never routed.
        val client = FtpRemoteClient(
            profile(port = 21, host = "198.51.100.1", connectTimeoutMillis = 1_500)
        )
        val started = System.nanoTime()
        assertThrows<TransportException> { client.connect() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis < 30_000, "connect took ${elapsedMillis}ms despite a 1.5s timeout")
    }

    @Test
    fun `UTF-8 is negotiated for the control channel`() {
        val client = FtpRemoteClient(profile(start().port))
        client.use {
            it.connect()
            assertEquals("UTF-8", it.controlEncoding)
        }
    }

    @Test
    fun `a server without MLSD falls back to LIST and still parses`() {
        root.resolve("sub").createDirectories()
        root.resolve("plain.txt").writeBytes("12345".toByteArray())

        FtpRemoteClient(profile(start(mlsdSupported = false).port)).use { client ->
            client.connect()
            assertFalse(client.supportsMlsd, "the fixture must not advertise MLSD")
            val entries = client.list("/")
            assertEquals(listOf("sub", "plain.txt"), entries.map { it.name })
            assertTrue(entries[0].isDirectory)
            assertEquals(5L, entries[1].size)
        }

        server?.close()
        FtpRemoteClient(profile(start(mlsdSupported = true).port)).use { client ->
            client.connect()
            assertTrue(client.supportsMlsd)
            assertEquals(5L, client.list("/").single { it.name == "plain.txt" }.size)
        }
    }

    @Test
    fun `a server without SITE CHMOD reports the feature as unsupported`() {
        root.resolve("perms.txt").writeBytes("x".toByteArray())
        FtpRemoteClient(profile(start().port)).use {
            it.connect()
            val failure = assertThrows<UnsupportedFeatureException> { it.setPermissions("/perms.txt", 0b110_100_000) }
            assertTrue(failure.message!!.isNotBlank())
        }
    }

    @Test
    fun `an unknown host is reported as such`() {
        val client = FtpRemoteClient(
            profile(port = 21, host = "no-such-host.invalid", connectTimeoutMillis = 5_000)
        )
        val failure = assertThrows<TransportException> { client.connect() }
        assertTrue(failure.message!!.contains("no-such-host.invalid"), failure.message)
    }

    @Test
    fun `a write into a directory the account may not modify is a permission error`() {
        root.resolve("readonly.txt").writeBytes("x".toByteArray())
        val client = FtpRemoteClient(
            profile(start().port, Credentials.Password(EmbeddedFtpServer.READ_ONLY_USER, EmbeddedFtpServer.PASSWORD))
        )
        client.use {
            it.connect()
            // Reading is fine for this account...
            assertEquals(listOf("readonly.txt"), it.list("/").map { entry -> entry.name })
            // ...writing is not.
            assertThrows<PermissionDeniedException> { it.makeDirectory("/nope") }
            assertThrows<PermissionDeniedException> { it.deleteFile("/readonly.txt") }
        }
    }

    @Test
    fun `distinct failures map to distinct exception types`() {
        root.resolve("file.txt").writeBytes("x".toByteArray())
        FtpRemoteClient(profile(start().port)).use {
            it.connect()
            assertThrows<RemoteFileNotFoundException> { it.deleteFile("/missing.txt") }
            assertThrows<RemoteFileNotFoundException> { it.list("/missing-dir") }
            assertThrows<NotADirectoryException> { it.list("/file.txt") }
        }
    }
}
