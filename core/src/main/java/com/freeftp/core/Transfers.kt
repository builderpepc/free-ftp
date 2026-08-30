package com.freeftp.core

import java.io.InputStream
import java.io.OutputStream

/** Shared byte-pump used by every protocol implementation. */
internal object Transfers {

    const val BUFFER_SIZE: Int = 64 * 1024

    /**
     * Copies [input] into [output], reporting progress and honouring cancellation.
     *
     * [alreadyTransferred] seeds the counter so a resumed transfer reports absolute
     * progress rather than restarting from zero. Progress is reported once before the
     * first byte and once after the last, so a zero-length transfer still produces a
     * terminal callback.
     *
     * @return the number of bytes copied by this call.
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        total: Long,
        alreadyTransferred: Long = 0L,
        listener: ProgressListener = ProgressListener.NONE,
        cancellation: CancellationSignal = CancellationSignal(),
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var transferred = alreadyTransferred
        listener.onProgress(transferred, total)
        while (true) {
            cancellation.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            cancellation.throwIfCancelled()
            output.write(buffer, 0, read)
            transferred += read
            listener.onProgress(transferred, total)
        }
        output.flush()
        listener.onProgress(transferred, total)
        return transferred - alreadyTransferred
    }
}
