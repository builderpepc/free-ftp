package com.freeftp.core.store

import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemotePath
import com.freeftp.core.ServerProfile
import java.io.File

/**
 * Persists connection profiles, with every secret encrypted at rest.
 *
 * The on-disk format is a small hand-rolled record format rather than JSON: it keeps
 * the module free of a serialization dependency, and the one thing that genuinely
 * matters — that a password never touches the disk in clear — is easier to verify when
 * the writer is fifty lines long.
 */
class ServerProfileRepository(
    private val file: File,
    private val cipher: SecretCipher,
) {

    /** Every stored profile. A missing or unreadable store yields an empty list. */
    fun load(): List<ServerProfile> {
        if (!file.isFile) return emptyList()
        return try {
            file.readText()
                .split(RECORD_SEPARATOR)
                .mapNotNull { record -> parseRecord(record) }
        } catch (_: Exception) {
            // A corrupt store must not brick the app: the user can re-add their servers,
            // but they cannot recover from a crash loop on startup.
            emptyList()
        }
    }

    fun find(id: String): ServerProfile? = load().firstOrNull { it.id == id }

    /** Inserts [profile], or replaces the existing profile with the same id. */
    fun save(profile: ServerProfile) {
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current.add(profile)
        writeAll(current)
    }

    fun delete(id: String) {
        writeAll(load().filterNot { it.id == id })
    }

    private fun writeAll(profiles: List<ServerProfile>) {
        file.parentFile?.mkdirs()
        file.writeText(profiles.joinToString(RECORD_SEPARATOR) { renderRecord(it) })
    }

    // ---------------------------------------------------------------- serialization

    private fun renderRecord(profile: ServerProfile): String {
        val fields = linkedMapOf(
            "id" to profile.id,
            "name" to profile.name,
            "protocol" to profile.protocol.name,
            "host" to profile.host,
            "port" to profile.port.toString(),
            "initialPath" to profile.initialPath,
            "passiveMode" to profile.passiveMode.toString(),
            "controlEncoding" to profile.controlEncoding,
            "showHiddenFiles" to profile.showHiddenFiles.toString(),
            "trustAllCertificates" to profile.trustAllCertificates.toString(),
            "connectTimeoutMillis" to profile.connectTimeoutMillis.toString(),
            "dataTimeoutMillis" to profile.dataTimeoutMillis.toString(),
        )
        when (val credentials = profile.credentials) {
            is Credentials.Anonymous -> fields["auth"] = "anonymous"
            is Credentials.Password -> {
                fields["auth"] = "password"
                fields["username"] = credentials.username
                fields["password"] = cipher.encrypt(credentials.password)
            }

            is Credentials.PrivateKey -> {
                fields["auth"] = "key"
                fields["username"] = credentials.username
                fields["privateKey"] = cipher.encrypt(credentials.privateKey)
                credentials.passphrase?.let { fields["passphrase"] = cipher.encrypt(it) }
                credentials.password?.let { fields["password"] = cipher.encrypt(it) }
            }
        }
        return fields.entries.joinToString("\n") { "${it.key}=${escape(it.value)}" }
    }

    private fun parseRecord(record: String): ServerProfile? {
        val fields = record.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to unescape(line.substring(split + 1))
            }
            .toMap()
        if (fields.isEmpty()) return null

        val id = fields["id"] ?: return null
        val protocol = fields["protocol"]?.let { name ->
            Protocol.entries.firstOrNull { it.name == name }
        } ?: return null
        val username = fields["username"].orEmpty()

        val credentials = when (fields["auth"]) {
            "anonymous" -> Credentials.Anonymous
            "password" -> Credentials.Password(username, cipher.decrypt(fields["password"] ?: return null))
            "key" -> Credentials.PrivateKey(
                username = username,
                privateKey = cipher.decrypt(fields["privateKey"] ?: return null),
                passphrase = fields["passphrase"]?.let(cipher::decrypt),
                password = fields["password"]?.let(cipher::decrypt),
            )

            else -> return null
        }

        return ServerProfile(
            id = id,
            name = fields["name"].orEmpty(),
            protocol = protocol,
            host = fields["host"].orEmpty(),
            port = fields["port"]?.toIntOrNull() ?: protocol.defaultPort,
            credentials = credentials,
            initialPath = fields["initialPath"] ?: RemotePath.ROOT,
            passiveMode = fields["passiveMode"]?.toBooleanStrictOrNull() ?: true,
            controlEncoding = fields["controlEncoding"] ?: "UTF-8",
            showHiddenFiles = fields["showHiddenFiles"]?.toBooleanStrictOrNull() ?: false,
            trustAllCertificates = fields["trustAllCertificates"]?.toBooleanStrictOrNull() ?: false,
            connectTimeoutMillis = fields["connectTimeoutMillis"]?.toIntOrNull() ?: 15_000,
            dataTimeoutMillis = fields["dataTimeoutMillis"]?.toIntOrNull() ?: 30_000,
        )
    }

    /** Values are one per line, so any newline inside one (a private key) must be escaped. */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val c = value[index]
            if (c == '\\' && index + 1 < value.length) {
                index++
                when (value[index]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(value[index])
                }
            } else {
                out.append(c)
            }
            index++
        }
        return out.toString()
    }

    private companion object {
        const val RECORD_SEPARATOR = "\n---\n"
    }
}
