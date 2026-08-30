package com.freeftp.core.transfer

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The local end of a transfer.
 *
 * Not a [File], because on Android the destination may be a folder the user picked
 * through the storage picker, which is reachable only as a `content://` document and has
 * no filesystem path at all. Keeping the queue behind this interface means a download to
 * the app's private folder and a download to the user's own SD card are the same code.
 */
interface LocalTarget {

    /** Name to show in the transfer list. */
    val name: String

    /** A stable handle — a file path or a document URI — for opening the result later. */
    val identifier: String

    fun exists(): Boolean

    /** Current length in bytes, or 0 when it does not exist. */
    fun size(): Long

    /**
     * Opens for writing with [startAt] bytes already present.
     *
     * Callers pass either `0` (replace the file) or exactly [size] (continue a partial
     * transfer). Nothing in between: a document tree can append or truncate, but it
     * cannot seek, and that limit is easier to honour than to work around.
     */
    fun openWrite(startAt: Long): OutputStream

    fun openRead(): InputStream
}

/** A [LocalTarget] backed by an ordinary file. */
class FileTarget(val file: File) : LocalTarget {

    override val name: String get() = file.name

    override val identifier: String get() = file.absolutePath

    override fun exists(): Boolean = file.isFile

    override fun size(): Long = if (file.isFile) file.length() else 0L

    override fun openWrite(startAt: Long): OutputStream {
        // Downloading a folder creates the folder: the parents come from the remote tree.
        file.parentFile?.mkdirs()
        val handle = RandomAccessFile(file, "rw")
        handle.setLength(startAt)
        handle.seek(startAt)
        return object : OutputStream() {
            override fun write(b: Int) = handle.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = handle.write(b, off, len)
            override fun close() = handle.close()
        }
    }

    override fun openRead(): InputStream = FileInputStream(file)

    override fun toString(): String = "FileTarget(${file.absolutePath})"
}
