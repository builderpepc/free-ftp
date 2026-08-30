package com.freeftp.core.preview

import com.freeftp.core.CancellationSignal
import com.freeftp.core.ProgressListener
import com.freeftp.core.RemoteClient
import com.freeftp.core.TransferCancelledException
import java.io.ByteArrayOutputStream

/**
 * Fetches the start of a remote file into memory so it can be shown in the app.
 *
 * Nothing touches local storage: the bytes go straight into a buffer, are classified,
 * and are dropped when the screen closes. That is the point — reading a config file or
 * a log tail should not litter the device with downloads.
 *
 * Reading stops at [limitBytes] using the ordinary transfer cancellation path, so an
 * enormous log costs one megabyte of transfer rather than all of it. On FTP that
 * cancellation drops the control connection — a half-finished transfer leaves the
 * channel mid-reply — so the connection is re-established before returning. Previewing
 * a file must not quietly leave the caller's client unusable.
 */
fun RemoteClient.previewText(
    remotePath: String,
    limitBytes: Long = TextPreview.DEFAULT_LIMIT_BYTES,
): FilePreview {
    require(limitBytes > 0) { "the preview limit must be positive" }

    val buffer = ByteArrayOutputStream()
    val cancellation = CancellationSignal()
    var hitLimit = false

    val stopAtLimit = ProgressListener { transferred, _ ->
        if (transferred >= limitBytes) {
            hitLimit = true
            cancellation.cancel()
        }
    }

    try {
        download(remotePath, buffer, listener = stopAtLimit, cancellation = cancellation)
    } catch (_: TransferCancelledException) {
        // Expected once the limit is reached; whatever arrived is still worth showing.
    }

    // Cancelling a transfer costs FTP its control connection. Restore it so the caller
    // gets its client back in the state it lent us; a failure here is not the preview's
    // problem to report, and the next operation will retry the connection anyway.
    if (hitLimit && !isConnected) runCatching { connect() }

    // Progress is reported after each buffer is written, so the last one can overshoot.
    val bytes = buffer.toByteArray().let {
        if (it.size > limitBytes) it.copyOf(limitBytes.toInt()) else it
    }
    return TextPreview.of(bytes, truncated = hitLimit)
}
