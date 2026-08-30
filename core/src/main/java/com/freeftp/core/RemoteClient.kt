package com.freeftp.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Receives byte counts as a transfer proceeds. [total] is `-1` when the size is unknown. */
fun interface ProgressListener {
    fun onProgress(transferred: Long, total: Long)

    companion object {
        val NONE: ProgressListener = ProgressListener { _, _ -> }
    }
}

/** Cooperative cancellation flag, checked between buffer writes. */
class CancellationSignal {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }

    fun throwIfCancelled() {
        if (isCancelled) throw TransferCancelledException()
    }

    companion object {
        /** A signal that is never cancelled. */
        val NONE: CancellationSignal get() = CancellationSignal()
    }
}

/**
 * Protocol-independent view of a remote file store.
 *
 * All methods are blocking and must be called off the main thread; instances are
 * **not** thread-safe, mirroring the single control connection underneath.
 */
interface RemoteClient : Closeable {

    val protocol: Protocol

    val isConnected: Boolean

    /** Opens the connection and authenticates. */
    fun connect()

    /** Closes the connection. A no-op when not connected. */
    fun disconnect()

    override fun close() = disconnect()

    /** The server-side working directory, resolved to an absolute path. */
    fun workingDirectory(): String

    /** Entries of [path], excluding `.` and `..`, in [RemoteFileOrdering]. */
    fun list(path: String): List<RemoteFile>

    fun stat(path: String): RemoteFile

    fun exists(path: String): Boolean

    /** Creates a single directory; the parent must exist. */
    fun makeDirectory(path: String)

    /** Creates [path] and any missing parents. */
    fun makeDirectories(path: String)

    /** Creates an empty file, or updates the timestamp of an existing one. */
    fun touch(path: String)

    fun deleteFile(path: String)

    /** Removes an empty directory. */
    fun removeDirectory(path: String)

    /** Removes [path] and everything beneath it. */
    fun deleteRecursively(path: String)

    fun rename(from: String, to: String)

    fun setModificationTime(path: String, epochMillis: Long)

    fun setPermissions(path: String, mode: Int)

    /**
     * Streams [remotePath] into [sink], starting at [offset] bytes.
     * The caller owns [sink] and must close it.
     */
    fun download(
        remotePath: String,
        sink: OutputStream,
        offset: Long = 0L,
        listener: ProgressListener = ProgressListener.NONE,
        cancellation: CancellationSignal = CancellationSignal(),
    )

    /**
     * Streams [source] to [remotePath]. When [offset] is non-zero the bytes are appended
     * starting at that position, which is how an interrupted upload is resumed; [source]
     * must already be positioned at [offset].
     *
     * [size] is used only to report progress; pass `-1` when unknown.
     */
    fun upload(
        source: InputStream,
        remotePath: String,
        size: Long = -1L,
        offset: Long = 0L,
        listener: ProgressListener = ProgressListener.NONE,
        cancellation: CancellationSignal = CancellationSignal(),
    )
}
