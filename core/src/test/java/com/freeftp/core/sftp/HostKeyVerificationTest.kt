package com.freeftp.core.sftp

import com.freeftp.core.Credentials
import com.freeftp.core.HostKeyChangedException
import com.freeftp.core.Protocol
import com.freeftp.core.ServerProfile
import com.freeftp.core.UnknownHostKeyException
import com.freeftp.core.testing.EmbeddedSftpServer
import com.freeftp.core.testing.SshKeyFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import net.schmizz.sshj.common.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Test plan section 3 — SSH host key verification. */
class HostKeyVerificationTest {

    @TempDir
    lateinit var workspace: Path

    private lateinit var root: Path
    private var server: EmbeddedSftpServer? = null

    @BeforeEach
    fun setUp() {
        root = workspace.resolve("served").also { it.createDirectories() }
    }

    @AfterEach
    fun tearDown() {
        server?.close()
    }

    private fun start(hostKeyName: String = "hostkey.ser"): EmbeddedSftpServer {
        server?.close()
        return EmbeddedSftpServer(root, hostKeyFile = workspace.resolve(hostKeyName))
            .start()
            .also { server = it }
    }

    private fun client(
        port: Int,
        store: HostKeyStore,
        policy: HostKeyPolicy = HostKeyPolicy.TRUST_ON_FIRST_USE,
    ) = SftpRemoteClient(
        ServerProfile(
            id = "sftp",
            name = "Test SFTP",
            protocol = Protocol.SFTP,
            host = "127.0.0.1",
            port = port,
            credentials = Credentials.Password(EmbeddedSftpServer.USER, EmbeddedSftpServer.PASSWORD),
        ),
        hostKeyStore = store,
        hostKeyPolicy = policy,
    )

    @Test // 3.1
    fun `the first connection remembers the key it was shown`() {
        val store = InMemoryHostKeyStore()
        val sftp = start()
        client(sftp.port, store).use { it.connect() }
        assertEquals(sftp.hostKeyFingerprint(), store.fingerprintFor("127.0.0.1", sftp.port))
    }

    @Test // 3.2
    fun `reconnecting with the same key succeeds silently`() {
        val store = InMemoryHostKeyStore()
        val sftp = start()
        client(sftp.port, store).use { it.connect() }
        client(sftp.port, store).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test // 3.3
    fun `a changed host key is refused and names both fingerprints`() {
        val store = InMemoryHostKeyStore()
        val first = start("hostkey-a.ser")
        val originalFingerprint = first.hostKeyFingerprint()
        val port = first.port
        client(port, store).use { it.connect() }

        // The same host, now presenting a different key: the classic MITM signature.
        val second = start("hostkey-b.ser")
        store.remember("127.0.0.1", second.port, originalFingerprint)
        assertNotEquals(originalFingerprint, second.hostKeyFingerprint())

        val failure = assertThrows<HostKeyChangedException> { client(second.port, store).connect() }
        assertEquals(originalFingerprint, failure.expectedFingerprint)
        assertEquals(second.hostKeyFingerprint(), failure.actualFingerprint)
    }

    @Test // 3.4
    fun `strict mode refuses a host it has never seen`() {
        val store = InMemoryHostKeyStore()
        val sftp = start()
        val failure = assertThrows<UnknownHostKeyException> {
            client(sftp.port, store, HostKeyPolicy.STRICT).connect()
        }
        assertEquals(sftp.hostKeyFingerprint(), failure.fingerprint)
        assertNull(store.fingerprintFor("127.0.0.1", sftp.port), "a refused key must not be stored")
    }

    @Test // 3.5
    fun `fingerprints match what ssh-keygen prints`() {
        assumeTrue(SshKeyFixtures.isAvailable())
        val key = SshKeyFixtures.generate(workspace, "ed25519")
        val ours = SshFingerprint.sha256(key.publicKey)
        assertTrue(ours.startsWith("SHA256:"), ours)

        val publicKeyLine = "ssh-ed25519 " +
            Base64.getEncoder().encodeToString(Buffer.PlainBuffer().putPublicKey(key.publicKey).compactData)
        val file = workspace.resolve("fingerprint.pub")
        Files.writeString(file, "$publicKeyLine freeftp-test\n")

        val process = ProcessBuilder("ssh-keygen", "-l", "-f", file.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS))
        assertTrue(output.contains(ours), "ssh-keygen said: ${output.trim()}; we said: $ours")
    }

    @Test // 3.6
    fun `the store distinguishes the same host on different ports`() {
        val store = InMemoryHostKeyStore()
        store.remember("example.com", 22, "SHA256:aaa")
        store.remember("example.com", 2222, "SHA256:bbb")
        assertEquals("SHA256:aaa", store.fingerprintFor("example.com", 22))
        assertEquals("SHA256:bbb", store.fingerprintFor("example.com", 2222))
        assertNull(store.fingerprintFor("example.com", 2022))

        store.forget("example.com", 22)
        assertNull(store.fingerprintFor("example.com", 22))
        assertEquals("SHA256:bbb", store.fingerprintFor("example.com", 2222))
    }

    @Test // 3.6
    fun `a file backed store survives a restart`() {
        val file = workspace.resolve("hostkeys/known.txt").toFile()
        FileHostKeyStore(file).remember("example.com", 2222, "SHA256:persisted")
        assertEquals("SHA256:persisted", FileHostKeyStore(file).fingerprintFor("example.com", 2222))
    }
}
