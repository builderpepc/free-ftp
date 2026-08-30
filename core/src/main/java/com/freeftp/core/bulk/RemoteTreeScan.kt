package com.freeftp.core.bulk

import com.freeftp.core.RemoteClient
import com.freeftp.core.RemoteFile
import com.freeftp.core.RemotePath

/** One file found by a scan, with the path it should keep locally. */
data class ScannedFile(
    val remotePath: String,
    /** Path relative to the directory the scan started from, e.g. `docs/reports/q3.txt`. */
    val relativePath: String,
    val size: Long,
)

/** What a recursive walk found, and what it had to give up on. */
data class RemoteTreeScan(
    val files: List<ScannedFile>,
    val directoryCount: Int,
    val totalBytes: Long,
    /** True when a limit stopped the walk, so the real totals are larger than reported. */
    val truncated: Boolean,
    /** Directory symlinks deliberately not followed. */
    val skippedSymlinks: Int,
) {
    val fileCount: Int get() = files.size

    companion object {
        val EMPTY = RemoteTreeScan(emptyList(), 0, 0L, truncated = false, skippedSymlinks = 0)
    }
}

/** Ceilings that keep a mis-aimed scan from becoming an unbounded one. */
object ScanLimits {
    const val DEFAULT_MAX_FILES: Int = 5_000
    const val DEFAULT_MAX_DEPTH: Int = 24
}

/**
 * Walks [entries] — files taken as-is, directories descended into — and returns every
 * file underneath, with paths relative to [base].
 *
 * Bounded deliberately. Pointing this at `/` on a real server would otherwise enumerate
 * the whole filesystem before the first byte is transferred, and a directory symlink
 * pointing at its own ancestor would never terminate at all. Directory symlinks are
 * therefore skipped rather than followed, and the walk stops at [maxFiles] or [maxDepth],
 * reporting `truncated` so the caller can warn instead of pretending it saw everything.
 */
fun RemoteClient.scanForDownload(
    base: String,
    entries: List<RemoteFile>,
    maxFiles: Int = ScanLimits.DEFAULT_MAX_FILES,
    maxDepth: Int = ScanLimits.DEFAULT_MAX_DEPTH,
): RemoteTreeScan {
    require(maxFiles > 0) { "maxFiles must be positive" }
    require(maxDepth > 0) { "maxDepth must be positive" }

    val root = RemotePath.normalize(base)
    val files = ArrayList<ScannedFile>()
    var directoryCount = 0
    var totalBytes = 0L
    var truncated = false
    var skippedSymlinks = 0

    fun relativeTo(path: String): String {
        val normalized = RemotePath.normalize(path)
        return if (RemotePath.isAncestorOf(root, normalized)) {
            RemotePath.relativize(root, normalized)
        } else {
            RemotePath.name(normalized)
        }
    }

    // Explicit stack rather than recursion: a deep tree should hit maxDepth, not the
    // JVM stack.
    val pending = ArrayDeque<Pair<String, Int>>()

    fun accept(entry: RemoteFile, depth: Int) {
        when {
            entry.isDirectory && entry.isSymlink -> skippedSymlinks++
            entry.isDirectory -> {
                directoryCount++
                if (depth >= maxDepth) truncated = true else pending.addLast(entry.path to depth + 1)
            }

            files.size >= maxFiles -> truncated = true
            else -> {
                files.add(ScannedFile(entry.path, relativeTo(entry.path), entry.size))
                totalBytes += entry.size
            }
        }
    }

    entries.forEach { accept(it, depth = 1) }

    while (pending.isNotEmpty()) {
        if (files.size >= maxFiles) {
            truncated = true
            break
        }
        val (directory, depth) = pending.removeFirst()
        list(directory).forEach { accept(it, depth) }
    }

    return RemoteTreeScan(
        files = files,
        directoryCount = directoryCount,
        totalBytes = totalBytes,
        truncated = truncated,
        skippedSymlinks = skippedSymlinks,
    )
}
