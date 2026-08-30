package com.freeftp.core

/** Wire protocol used to reach a server. */
enum class Protocol(val defaultPort: Int, val encrypted: Boolean) {
    /** Plain FTP (RFC 959). */
    FTP(21, false),

    /** FTP with `AUTH TLS` on the standard control port (RFC 4217). */
    FTPS_EXPLICIT(21, true),

    /** FTP wrapped in TLS from the first byte, historically on port 990. */
    FTPS_IMPLICIT(990, true),

    /** SFTP: the file-transfer subsystem of SSH-2. Unrelated to FTP. */
    SFTP(22, true),
    ;

    val isFtpFamily: Boolean get() = this != SFTP
}

/** How to authenticate. */
sealed interface Credentials {
    val username: String

    data object Anonymous : Credentials {
        override val username: String get() = "anonymous"
        /** Convention since RFC 1635: an e-mail address as the password. */
        const val PASSWORD: String = "anonymous@example.com"
    }

    data class Password(override val username: String, val password: String) : Credentials {
        override fun toString(): String = "Password(username=$username, password=***)"
    }

    data class PrivateKey(
        override val username: String,
        /** OpenSSH, PEM or PuTTY `.ppk` text of the private key. */
        val privateKey: String,
        val passphrase: String? = null,
        /** Optional password to fall back on when the server rejects the key. */
        val password: String? = null,
    ) : Credentials {
        override fun toString(): String =
            "PrivateKey(username=$username, privateKey=***, passphrase=***, password=***)"
    }
}

/** A saved server connection. */
data class ServerProfile(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val credentials: Credentials,
    val initialPath: String = RemotePath.ROOT,
    /** FTP only: passive (PASV/EPSV) rather than active (PORT/EPRT) data connections. */
    val passiveMode: Boolean = true,
    /** FTP only: encoding for the control channel. UTF-8 is upgraded automatically via FEAT. */
    val controlEncoding: String = "UTF-8",
    /**
     * FTP only: ask the server for dot-prefixed entries.
     *
     * `MLSD`, the machine-readable listing command, has no way to request hidden files,
     * and some servers omit them from it. Turning this on makes the client use `LIST -a`
     * instead — the same trade-off FileZilla exposes as "force showing hidden files".
     */
    val showHiddenFiles: Boolean = false,
    /** FTPS only: accept self-signed / otherwise untrusted certificates. */
    val trustAllCertificates: Boolean = false,
    val connectTimeoutMillis: Int = 15_000,
    val dataTimeoutMillis: Int = 30_000,
) {
    /** Field-level validation errors, empty when the profile is usable. */
    fun validate(): List<ValidationError> = buildList {
        if (name.isBlank()) add(ValidationError("name", "Name must not be empty"))
        if (host.isBlank()) add(ValidationError("host", "Host must not be empty"))
        if (port !in 1..65535) add(ValidationError("port", "Port must be between 1 and 65535"))
        if (credentials !is Credentials.Anonymous && credentials.username.isBlank()) {
            add(ValidationError("username", "Username must not be empty"))
        }
        if (protocol == Protocol.SFTP && credentials is Credentials.Anonymous) {
            add(ValidationError("credentials", "SFTP does not support anonymous login"))
        }
    }

    val isValid: Boolean get() = validate().isEmpty()
}

data class ValidationError(val field: String, val message: String)

/** One entry in a remote directory listing. */
data class RemoteFile(
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modifiedEpochMillis: Long? = null,
    val isSymlink: Boolean = false,
    val symlinkTarget: String? = null,
    /** Unix mode bits (e.g. `0o644`), when the server reports them. */
    val permissions: Int? = null,
    val owner: String? = null,
    val group: String? = null,
) {
    val name: String get() = RemotePath.name(path)
}

/** Directories first, then names compared case-insensitively; ties broken case-sensitively so the order is total. */
val RemoteFileOrdering: Comparator<RemoteFile> =
    compareByDescending<RemoteFile> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.name }

fun List<RemoteFile>.sortedForDisplay(): List<RemoteFile> = sortedWith(RemoteFileOrdering)

/** Renders Unix mode bits the way `ls -l` does, e.g. `rw-r-----`. */
fun permissionsToString(mode: Int): String {
    val chars = CharArray(9)
    val flags = "rwx"
    for (i in 0 until 9) {
        val bit = 1 shl (8 - i)
        chars[i] = if (mode and bit != 0) flags[i % 3] else '-'
    }
    return String(chars)
}
