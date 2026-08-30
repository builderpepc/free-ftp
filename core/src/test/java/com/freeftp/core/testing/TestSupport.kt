package com.freeftp.core.testing

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random

/** Grabs a port the OS is currently willing to hand out. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Deterministic pseudo-random bytes, so a failure is reproducible. */
fun randomBytes(size: Int, seed: Int = 42): ByteArray = Random(seed).nextBytes(size)

/** An [OutputStream] that counts bytes and can be inspected afterwards. */
class RecordingOutputStream : ByteArrayOutputStream() {
    val bytes: ByteArray get() = toByteArray()
}

/** Records every progress callback so monotonicity and totals can be asserted. */
class ProgressRecorder : com.freeftp.core.ProgressListener {
    private val _events = mutableListOf<Pair<Long, Long>>()
    val events: List<Pair<Long, Long>> get() = synchronized(_events) { _events.toList() }
    val transferred: List<Long> get() = events.map { it.first }

    override fun onProgress(transferred: Long, total: Long) {
        synchronized(_events) { _events.add(transferred to total) }
    }
}

internal val secureRandom = SecureRandom()
