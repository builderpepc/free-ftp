package com.freeftp.core.sftp

import com.freeftp.core.HostKeyChangedException
import com.freeftp.core.RemoteException
import com.freeftp.core.UnknownHostKeyException
import java.security.PublicKey
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/** What to do when the server's key is not the one we already trust. */
enum class HostKeyPolicy {
    /** Accept and remember a key the first time it is seen; refuse any later change. */
    TRUST_ON_FIRST_USE,

    /** Refuse anything not already in the store. */
    STRICT,
}

/**
 * Trust-on-first-use host key verification.
 *
 * SSHJ's contract is a boolean, which would collapse "we have never seen this host" and
 * "this host's key changed" into one indistinguishable failure — and those two mean very
 * different things to a user. The precise reason is recorded in [failure] so
 * [SftpRemoteClient] can re-throw it in place of SSHJ's generic transport error.
 */
class TofuHostKeyVerifier(
    private val store: HostKeyStore,
    private val policy: HostKeyPolicy = HostKeyPolicy.TRUST_ON_FIRST_USE,
) : HostKeyVerifier {

    /** Set when [verify] rejected a key, describing exactly why. */
    var failure: RemoteException? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = SshFingerprint.sha256(key)
        val known = store.fingerprintFor(hostname, port)
        return when {
            known == null && policy == HostKeyPolicy.TRUST_ON_FIRST_USE -> {
                store.remember(hostname, port, fingerprint)
                true
            }

            known == null -> {
                failure = UnknownHostKeyException(hostname, fingerprint)
                false
            }

            known == fingerprint -> true

            else -> {
                failure = HostKeyChangedException(hostname, known, fingerprint)
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
