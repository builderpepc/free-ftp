package com.freeftp.core.testing

import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.PromptEntry
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/**
 * A real Apache MINA SSHD instance exposing the SFTP subsystem over a temporary directory.
 *
 * [hostKeyFile] is explicit so a test can restart the server with a different key and
 * exercise the "host key changed" path (test plan 3.3).
 */
class EmbeddedSftpServer(
    val root: Path,
    private val hostKeyFile: Path,
    /** Public keys accepted for publickey authentication. */
    private val authorizedKeys: List<PublicKey> = emptyList(),
    private val keyboardInteractiveEnabled: Boolean = false,
    private val passwordEnabled: Boolean = true,
    /** A specific port, for the hand-driven dev server; tests take an ephemeral one. */
    private val fixedPort: Int? = null,
) : AutoCloseable {

    var port: Int = 0
        private set

    private var sshd: SshServer? = null

    fun start(): EmbeddedSftpServer {
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            this@apply.port = fixedPort ?: freePort()
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            fileSystemFactory = VirtualFileSystemFactory(root)
            // Offer every method explicitly; the defaults vary with which authenticators
            // happen to be set, which would make these tests depend on SSHD internals.
            userAuthFactories = listOf(
                UserAuthPublicKeyFactory.INSTANCE,
                UserAuthPasswordFactory.INSTANCE,
                UserAuthKeyboardInteractiveFactory.INSTANCE,
            )
            subsystemFactories = listOf(SftpSubsystemFactory())
            passwordAuthenticator = if (passwordEnabled) {
                PasswordAuthenticator { username, password, _: ServerSession ->
                    username == USER && password == PASSWORD
                }
            } else {
                null
            }
            publickeyAuthenticator = if (authorizedKeys.isEmpty()) {
                null
            } else {
                PublickeyAuthenticator { username, key, _: ServerSession ->
                    username == USER && authorizedKeys.any { it == key }
                }
            }
            if (keyboardInteractiveEnabled) {
                keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                    override fun generateChallenge(
                        session: ServerSession,
                        username: String,
                        lang: String,
                        subMethods: String,
                    ): InteractiveChallenge = InteractiveChallenge().apply {
                        interactionName = "Password"
                        interactionInstruction = "Enter your password"
                        addPrompt(PromptEntry("Password: ", false))
                    }

                    override fun authenticate(
                        session: ServerSession,
                        username: String,
                        responses: List<String>,
                    ): Boolean = username == USER && responses.singleOrNull() == PASSWORD
                }
            }
        }
        server.start()
        port = server.port
        sshd = server
        return this
    }

    /** The host key currently presented, as an OpenSSH `SHA256:` fingerprint. */
    fun hostKeyFingerprint(): String {
        val key = sshd!!.keyPairProvider.loadKeys(null).first().public
        return com.freeftp.core.sftp.SshFingerprint.sha256(key)
    }

    override fun close() {
        sshd?.stop(true)
        sshd = null
    }

    companion object {
        const val USER: String = "sftpuser"
        const val PASSWORD: String = "sftp-secret"

        fun freshHostKeyFile(directory: Path, name: String = "hostkey.ser"): Path =
            directory.resolve(name).also { Files.deleteIfExists(it) }
    }
}
