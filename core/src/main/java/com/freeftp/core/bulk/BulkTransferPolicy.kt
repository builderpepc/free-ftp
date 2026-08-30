package com.freeftp.core.bulk

/**
 * Decides when a bulk action is big enough to be worth asking about.
 *
 * A long-press that lands slightly wrong, or a "download all" in the wrong directory,
 * should cost a second tap rather than a gigabyte of transfer. The thresholds are
 * deliberately low: the annoyance of one extra confirmation is far smaller than the
 * annoyance of accidentally pulling down a home directory.
 */
object BulkTransferPolicy {

    /** More files than this and the user is asked to confirm. */
    const val CONFIRM_ABOVE_FILE_COUNT: Int = 20

    /** More bytes than this and the user is asked to confirm. */
    const val CONFIRM_ABOVE_BYTES: Long = 100L * 1024 * 1024

    fun needsConfirmation(
        fileCount: Int,
        totalBytes: Long,
        truncated: Boolean = false,
    ): Boolean = when {
        // A truncated scan means we do not know the real size, and "unknown" is exactly
        // the case that deserves a prompt.
        truncated -> true
        fileCount > CONFIRM_ABOVE_FILE_COUNT -> true
        totalBytes > CONFIRM_ABOVE_BYTES -> true
        else -> false
    }

    fun needsConfirmation(scan: RemoteTreeScan): Boolean =
        needsConfirmation(scan.fileCount, scan.totalBytes, scan.truncated)
}
