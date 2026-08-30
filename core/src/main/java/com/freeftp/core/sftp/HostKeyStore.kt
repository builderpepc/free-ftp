package com.freeftp.core.sftp

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which SSH host key was accepted for a server.
 *
 * Entries are keyed by host **and** port: the same machine on two ports may legitimately
 * run two different SSH daemons with different keys.
 */
interface HostKeyStore {
    fun fingerprintFor(host: String, port: Int): String?
    fun remember(host: String, port: Int, fingerprint: String)
    fun forget(host: String, port: Int)
    fun entries(): Map<String, String>

    companion object {
        fun keyOf(host: String, port: Int): String = "$host:$port"
    }
}

class InMemoryHostKeyStore : HostKeyStore {
    private val entries = ConcurrentHashMap<String, String>()

    override fun fingerprintFor(host: String, port: Int): String? =
        entries[HostKeyStore.keyOf(host, port)]

    override fun remember(host: String, port: Int, fingerprint: String) {
        entries[HostKeyStore.keyOf(host, port)] = fingerprint
    }

    override fun forget(host: String, port: Int) {
        entries.remove(HostKeyStore.keyOf(host, port))
    }

    override fun entries(): Map<String, String> = entries.toMap()
}

/** A [HostKeyStore] persisted as `host:port fingerprint` lines. */
class FileHostKeyStore(private val file: File) : HostKeyStore {

    private val cache = ConcurrentHashMap<String, String>()

    init {
        if (file.isFile) {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                val parts = trimmed.split(' ', limit = 2)
                if (parts.size == 2) cache[parts[0]] = parts[1]
            }
        }
    }

    override fun fingerprintFor(host: String, port: Int): String? =
        cache[HostKeyStore.keyOf(host, port)]

    override fun remember(host: String, port: Int, fingerprint: String) {
        cache[HostKeyStore.keyOf(host, port)] = fingerprint
        flush()
    }

    override fun forget(host: String, port: Int) {
        cache.remove(HostKeyStore.keyOf(host, port))
        flush()
    }

    override fun entries(): Map<String, String> = cache.toMap()

    private fun flush() {
        file.parentFile?.mkdirs()
        file.writeText(cache.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} ${it.value}" } + "\n")
    }
}
