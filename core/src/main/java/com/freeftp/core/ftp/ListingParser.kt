package com.freeftp.core.ftp

import com.freeftp.core.RemoteFile
import com.freeftp.core.RemotePath
import org.apache.commons.net.ftp.FTPClientConfig
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPFileEntryParser
import org.apache.commons.net.ftp.parser.DefaultFTPFileEntryParserFactory
import org.apache.commons.net.ftp.parser.MLSxEntryParser

/**
 * Turns raw FTP directory listings into [RemoteFile]s.
 *
 * FTP has no standard listing format: `LIST` output is whatever the server's `ls`
 * happens to print, so a client has to recognise a dozen dialects. Commons Net ships
 * parsers for the common ones (Unix, Windows/IIS, Netware, VMS, OS/400, MVS, OS/2);
 * this object selects between them, adds `MLSD` (RFC 3659) and EPLF handling, and
 * maps everything onto one model. Kept separate from the client so every dialect can
 * be exercised without a server.
 */
object ListingParser {

    /** Entries a server includes for the directory itself and its parent; never shown. */
    private val DOT_ENTRIES = setOf(".", "..")

    private val factory = DefaultFTPFileEntryParserFactory()

    /**
     * A parser for the given `SYST` reply key (see [FTPClientConfig] `SYST_*` constants),
     * pinned to [timeZoneId] so listings without a year or offset are interpreted
     * consistently rather than in whatever zone the phone happens to be in.
     */
    fun parserFor(systemKey: String, timeZoneId: String = "UTC"): FTPFileEntryParser {
        val config = FTPClientConfig(systemKey).apply { setServerTimeZoneId(timeZoneId) }
        return factory.createFileEntryParser(config)
    }

    /** Parses `LIST` output using [parser], dropping headers, dot entries and unparseable lines. */
    fun parseListing(
        directory: String,
        lines: List<String>,
        parser: FTPFileEntryParser,
    ): List<RemoteFile> {
        // preParse strips dialect-specific noise such as the `total 12` header.
        val cleaned = parser.preParse(ArrayList(lines))
        return cleaned.mapNotNull { line ->
            val entry = runCatching { parser.parseFTPEntry(line) }.getOrNull()
            entry?.let { toRemoteFile(directory, it) }
        }
    }

    /** Convenience: parse `LIST` output for a named dialect. */
    fun parseListing(
        directory: String,
        lines: List<String>,
        systemKey: String = FTPClientConfig.SYST_UNIX,
        timeZoneId: String = "UTC",
    ): List<RemoteFile> = parseListing(directory, lines, parserFor(systemKey, timeZoneId))

    /**
     * Parses `MLSD` output (RFC 3659), which is the only machine-readable FTP listing
     * format. Lines are `fact=value;fact=value; <name>`; `cdir`/`pdir` entries describe
     * the directory itself and are dropped.
     */
    fun parseMlsd(directory: String, lines: List<String>): List<RemoteFile> =
        lines.mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val type = factOf(line, "type")?.lowercase()
            if (type == "cdir" || type == "pdir") return@mapNotNull null
            val entry = runCatching { MLSxEntryParser.parseEntry(line) }.getOrNull()
                ?: return@mapNotNull null
            toRemoteFile(directory, entry)
        }

    /**
     * Parses EPLF (Easily Parsed LIST Format): `+fact,fact,...\tname`. Rare, but it is
     * the one LIST dialect that is unambiguous, and clients that ignore it show garbage
     * for the servers that emit it.
     */
    fun parseEplf(directory: String, lines: List<String>): List<RemoteFile> =
        lines.mapNotNull { line -> parseEplfLine(directory, line) }

    private fun parseEplfLine(directory: String, line: String): RemoteFile? {
        if (!line.startsWith("+")) return null
        val tab = line.indexOf('\t')
        if (tab < 0) return null
        val name = line.substring(tab + 1)
        if (name.isEmpty() || name in DOT_ENTRIES) return null

        var isDirectory = false
        var size = 0L
        var mtime: Long? = null
        for (fact in line.substring(1, tab).split(',')) {
            when {
                fact == "/" -> isDirectory = true
                fact.startsWith("s") -> size = fact.drop(1).toLongOrNull() ?: 0L
                fact.startsWith("m") -> mtime = fact.drop(1).toLongOrNull()?.times(1000L)
            }
        }
        return RemoteFile(
            path = RemotePath.join(directory, name),
            isDirectory = isDirectory,
            size = size,
            modifiedEpochMillis = mtime,
        )
    }

    /** Maps a Commons Net [FTPFile] onto our model, or null when it should not be shown. */
    fun toRemoteFile(directory: String, entry: FTPFile): RemoteFile? {
        val name = entry.name?.let(::stripTrailingSeparator) ?: return null
        if (name.isEmpty() || name in DOT_ENTRIES) return null
        // MLSD reports the full path for some entries; only the last segment is the name.
        val leaf = if (name.contains('/')) RemotePath.name(name) else name
        if (leaf.isEmpty() || leaf in DOT_ENTRIES) return null

        val mode = unixModeOf(entry)
        return RemoteFile(
            path = RemotePath.join(directory, leaf),
            // A symlink to a directory is navigable, so treat it as one.
            isDirectory = entry.isDirectory,
            size = if (entry.size >= 0) entry.size else 0L,
            modifiedEpochMillis = entry.timestamp?.timeInMillis,
            isSymlink = entry.isSymbolicLink,
            symlinkTarget = entry.link,
            permissions = mode,
            owner = entry.user?.ifBlank { null },
            group = entry.group?.ifBlank { null },
        )
    }

    private fun stripTrailingSeparator(name: String): String =
        if (name.length > 1 && name.endsWith('/')) name.dropLast(1) else name

    /** Reads a single `fact=value;` from an MLSD line. */
    private fun factOf(line: String, fact: String): String? {
        val facts = line.substringBefore(' ')
        for (part in facts.split(';')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).equals(fact, ignoreCase = true)) {
                return part.substring(eq + 1)
            }
        }
        return null
    }

    /** Packs the nine Unix permission bits Commons Net exposes into a mode integer, or null if absent. */
    private fun unixModeOf(entry: FTPFile): Int? {
        val access = intArrayOf(FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS)
        val perms = intArrayOf(
            FTPFile.READ_PERMISSION,
            FTPFile.WRITE_PERMISSION,
            FTPFile.EXECUTE_PERMISSION,
        )
        var mode = 0
        for (a in access.indices) {
            for (p in perms.indices) {
                if (entry.hasPermission(access[a], perms[p])) {
                    mode = mode or (1 shl (8 - (a * 3 + p)))
                }
            }
        }
        return if (mode == 0) null else mode
    }
}
