package com.freeftp.core.sftp

import com.freeftp.core.AuthenticationException
import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemoteClient
import com.freeftp.core.ServerProfile
import com.freeftp.core.TransportException
import com.freeftp.core.testing.EmbeddedSftpServer
import com.freeftp.core.testing.SshKeyFixtures
import com.freeftp.core.testing.freePort
import java.nio.file.Path
import java.security.PublicKey
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** SFTP connection and every authentication method the client offers. */
class SftpConnectionTest {

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

    private fun start(
        authorizedKeys: List<PublicKey> = emptyList(),
        keyboardInteractiveEnabled: Boolean = false,
        passwordEnabled: Boolean = true,
    ): EmbeddedSftpServer = EmbeddedSftpServer(
        root = root,
        hostKeyFile = workspace.resolve("hostkey.ser"),
        authorizedKeys = authorizedKeys,
        keyboardInteractiveEnabled = keyboardInteractiveEnabled,
        passwordEnabled = passwordEnabled,
    ).start().also { server = it }

    private fun client(
        port: Int,
        credentials: Credentials = Credentials.Password(EmbeddedSftpServer.USER, EmbeddedSftpServer.PASSWORD),
        host: String = "127.0.0.1",
        connectTimeoutMillis: Int = 15_000,
    ): RemoteClient = SftpRemoteClient(
        ServerProfile(
            id = "sftp",
            name = "Test SFTP",
            protocol = Protocol.SFTP,
            host = host,
            port = port,
            credentials = credentials,
            connectTimeoutMillis = connectTimeoutMillis,
        ),
        hostKeyStore = InMemoryHostKeyStore(),
    )

    private fun keyPair(type: String, passphrase: String? = null) =
        SshKeyFixtures.generate(workspace, type, passphrase)

    @Test
    fun `password authentication connects`() {
        client(start().port).use {
            it.connect()
            assertTrue(it.isConnected)
            assertEquals("/", it.workingDirectory())
        }
    }

    @Test
    fun `a wrong password is an authentication failure`() {
        val port = start().port
        val client = client(port, Credentials.Password(EmbeddedSftpServer.USER, "wrong-password"))
        val failure = assertThrows<AuthenticationException> { client.connect() }
        assertFalse(failure.message!!.contains("wrong-password"))
    }

    @Test
    fun `ed25519 public key authentication connects`() {
        assumeTrue(SshKeyFixtures.isAvailable(), "ssh-keygen is required to generate test keys")
        val key = keyPair("ed25519")
        val port = start(authorizedKeys = listOf(key.publicKey), passwordEnabled = false).port
        client(
            port,
            Credentials.PrivateKey(EmbeddedSftpServer.USER, key.privateKeyText),
        ).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test
    fun `RSA public key authentication connects`() {
        assumeTrue(SshKeyFixtures.isAvailable())
        val key = keyPair("rsa")
        val port = start(authorizedKeys = listOf(key.publicKey), passwordEnabled = false).port
        client(port, Credentials.PrivateKey(EmbeddedSftpServer.USER, key.privateKeyText)).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test
    fun `an encrypted private key opens with the right passphrase`() {
        assumeTrue(SshKeyFixtures.isAvailable())
        val key = keyPair("ed25519", passphrase = "correct horse battery staple")
        val port = start(authorizedKeys = listOf(key.publicKey), passwordEnabled = false).port
        client(
            port,
            Credentials.PrivateKey(EmbeddedSftpServer.USER, key.privateKeyText, key.passphrase),
        ).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test
    fun `an encrypted private key with the wrong passphrase fails authentication`() {
        assumeTrue(SshKeyFixtures.isAvailable())
        val key = keyPair("ed25519", passphrase = "correct horse battery staple")
        val port = start(authorizedKeys = listOf(key.publicKey), passwordEnabled = false).port
        val client = client(
            port,
            Credentials.PrivateKey(EmbeddedSftpServer.USER, key.privateKeyText, "not the passphrase"),
        )
        val failure = assertThrows<AuthenticationException> { client.connect() }
        assertFalse(failure.message!!.contains("not the passphrase"))
    }

    @Test
    fun `keyboard-interactive authentication connects when password auth is unavailable`() {
        val port = start(keyboardInteractiveEnabled = true, passwordEnabled = false).port
        client(port).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test
    fun `a rejected key falls back to the password`() {
        assumeTrue(SshKeyFixtures.isAvailable())
        val offered = keyPair("ed25519")
        val accepted = keyPair("rsa") // the server trusts a different key entirely
        val port = start(authorizedKeys = listOf(accepted.publicKey), passwordEnabled = true).port
        client(
            port,
            Credentials.PrivateKey(
                username = EmbeddedSftpServer.USER,
                privateKey = offered.privateKeyText,
                password = EmbeddedSftpServer.PASSWORD,
            ),
        ).use {
            it.connect()
            assertTrue(it.isConnected)
        }
    }

    @Test
    @Timeout(30)
    fun `connecting to a closed port fails fast and names the endpoint`() {
        val port = freePort()
        val failure = assertThrows<TransportException> { client(port).connect() }
        assertTrue(failure.message!!.contains("127.0.0.1:$port"), failure.message)
    }

    @Test
    fun `an unknown host is reported as such`() {
        val failure = assertThrows<TransportException> {
            client(port = 22, host = "no-such-host.invalid", connectTimeoutMillis = 5_000).connect()
        }
        assertTrue(failure.message!!.contains("no-such-host.invalid"), failure.message)
    }
}
