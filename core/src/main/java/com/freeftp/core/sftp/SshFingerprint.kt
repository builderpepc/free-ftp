package com.freeftp.core.sftp

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer

/** OpenSSH-style public-key fingerprints. */
object SshFingerprint {

    /**
     * The `SHA256:base64` form printed by `ssh-keygen -lf` and shown by modern OpenSSH
     * on first connect, so a user can compare ours against theirs character for character.
     */
    fun sha256(key: PublicKey): String {
        val wire = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wire)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
