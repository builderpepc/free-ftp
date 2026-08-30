package com.freeftp.core.bulk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Test plan section 15 — when a bulk action is big enough to ask about. */
class BulkTransferPolicyTest {

    @Test // 15.1
    fun `a handful of small files goes ahead without asking`() {
        assertFalse(BulkTransferPolicy.needsConfirmation(fileCount = 3, totalBytes = 4_096))
        assertFalse(BulkTransferPolicy.needsConfirmation(fileCount = 0, totalBytes = 0))
    }

    @Test // 15.2
    fun `too many files needs confirmation even if they are tiny`() {
        assertTrue(BulkTransferPolicy.needsConfirmation(fileCount = 5_000, totalBytes = 1_024))
    }

    @Test // 15.3
    fun `too many bytes needs confirmation even if it is one file`() {
        assertTrue(
            BulkTransferPolicy.needsConfirmation(fileCount = 1, totalBytes = 4L * 1024 * 1024 * 1024)
        )
    }

    @Test // 15.4
    fun `the thresholds are inclusive of the limit and exclusive above it`() {
        val files = BulkTransferPolicy.CONFIRM_ABOVE_FILE_COUNT
        assertFalse(BulkTransferPolicy.needsConfirmation(fileCount = files, totalBytes = 0))
        assertTrue(BulkTransferPolicy.needsConfirmation(fileCount = files + 1, totalBytes = 0))

        val bytes = BulkTransferPolicy.CONFIRM_ABOVE_BYTES
        assertFalse(BulkTransferPolicy.needsConfirmation(fileCount = 1, totalBytes = bytes))
        assertTrue(BulkTransferPolicy.needsConfirmation(fileCount = 1, totalBytes = bytes + 1))
    }

    @Test // 15.5
    fun `a truncated scan always asks, because the real total is unknown`() {
        assertTrue(
            BulkTransferPolicy.needsConfirmation(fileCount = 1, totalBytes = 10, truncated = true),
            "if the walk gave up early we cannot claim it is a small download",
        )
    }

    @Test // 15.5
    fun `the scan overload agrees with the raw numbers`() {
        val small = RemoteTreeScan(
            files = List(3) { ScannedFile("/a$it", "a$it", 10) },
            directoryCount = 1,
            totalBytes = 30,
            truncated = false,
            skippedSymlinks = 0,
        )
        assertFalse(BulkTransferPolicy.needsConfirmation(small))
        assertTrue(BulkTransferPolicy.needsConfirmation(small.copy(truncated = true)))
    }
}
