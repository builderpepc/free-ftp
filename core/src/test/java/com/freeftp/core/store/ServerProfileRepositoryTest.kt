package com.freeftp.core.store

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.ServerProfile
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Connection profile persistence, and the encryption of the secrets inside it. */
class ServerProfileRepositoryTest {

    @TempDir
    lateinit var workspace: Path

    private lateinit var store: File
    private lateinit var cipher: SecretCipher
    private lateinit var repository: ServerProfileRepository

    private val password = "correct-horse-battery-staple"

    @BeforeEach
    fun setUp() {
        store = workspace.resolve("profiles/servers.dat").toFile()
        cipher = AesGcmSecretCipher(AesGcmSecretCipher.generateKey())
        repository = ServerProfileRepository(store, cipher)
    }

    private fun profile(
        id: String = "one",
        credentials: Credentials = Credentials.Password("bob", password),
        protocol: Protocol = Protocol.FTP,
    ) = ServerProfile(
        id = id,
        name = "Example $id",
        protocol = protocol,
        host = "ftp.example.com",
        port = 2121,
        credentials = credentials,
        initialPath = "/home/bob",
        passiveMode = false,
        controlEncoding = "ISO-8859-1",
        showHiddenFiles = true,
        trustAllCertificates = true,
        connectTimeoutMillis = 9_000,
        dataTimeoutMillis = 11_000,
    )

    @Test
    fun `every field round trips`() {
        val original = profile()
        repository.save(original)
        assertEquals(listOf(original), repository.load())
    }

    @Test
    fun `private key credentials round trip including newlines`() {
        val key = buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            appendLine("b3BlbnNzaC1rZXktdjEAAAAABG5vbmU=")
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
        val original = profile(
            credentials = Credentials.PrivateKey("bob", key, "pass phrase", password),
            protocol = Protocol.SFTP,
        )
        repository.save(original)
        assertEquals(original, repository.load().single())
    }

    @Test
    fun `anonymous credentials round trip`() {
        val original = profile(credentials = Credentials.Anonymous)
        repository.save(original)
        assertEquals(Credentials.Anonymous, repository.load().single().credentials)
    }

    @Test
    fun `saving an existing id updates rather than duplicating`() {
        repository.save(profile())
        repository.save(profile().copy(name = "Renamed"))
        assertEquals(listOf("Renamed"), repository.load().map { it.name })
    }

    @Test
    fun `delete removes only the named profile`() {
        repository.save(profile(id = "one"))
        repository.save(profile(id = "two"))
        repository.delete("one")
        assertEquals(listOf("two"), repository.load().map { it.id })
        assertNull(repository.find("one"))
    }

    @Test
    fun `secrets never reach the disk in clear`() {
        val keyMaterial = "secret-key-material"
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----" + System.lineSeparator() + keyMaterial
        repository.save(profile(id = "pw"))
        repository.save(
            profile(
                id = "key",
                credentials = Credentials.PrivateKey("bob", key, "my-passphrase"),
                protocol = Protocol.SFTP,
            )
        )
        val onDisk = store.readText()
        assertFalse(onDisk.contains(password), "the password was written in clear")
        assertFalse(onDisk.contains(keyMaterial), "the private key was written in clear")
        assertFalse(onDisk.contains("my-passphrase"), "the passphrase was written in clear")
        // ...but the API still returns the real values.
        assertEquals(password, (repository.find("pw")!!.credentials as Credentials.Password).password)
    }

    @Test
    fun `the same secret encrypts differently every time`() {
        repository.save(profile(id = "a"))
        val first = store.readText()
        repository.save(profile(id = "a", credentials = Credentials.Password("bob", password)))
        assertNotEquals(first, store.readText(), "a fixed nonce would leak that the value is unchanged")
    }

    @Test
    fun `a corrupt store loads as empty rather than crashing`() {
        store.parentFile.mkdirs()
        store.writeText("this is not a profile store" + System.lineSeparator() + "  garbage")
        assertEquals(emptyList<ServerProfile>(), repository.load())
    }

    @Test
    fun `a store whose secrets cannot be decrypted loads as empty rather than crashing`() {
        repository.save(profile(id = "good"))
        // Re-open with a different key, as if the device key store had been reset.
        val stranger = ServerProfileRepository(store, AesGcmSecretCipher(AesGcmSecretCipher.generateKey()))
        assertEquals(emptyList<ServerProfile>(), stranger.load())
    }

    @Test
    fun `a missing store is simply empty`() {
        assertEquals(emptyList<ServerProfile>(), repository.load())
        assertTrue(!store.exists())
    }

    @Test
    fun `AES-GCM detects tampering`() {
        val encrypted = cipher.encrypt("secret")
        val tampered = encrypted.dropLast(4) + "AAAA"
        assertEquals("secret", cipher.decrypt(encrypted))
        assertTrue(runCatching { cipher.decrypt(tampered) }.isFailure)
    }
}
