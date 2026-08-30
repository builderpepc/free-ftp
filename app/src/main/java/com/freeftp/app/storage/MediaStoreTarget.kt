package com.freeftp.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.freeftp.core.transfer.LocalTarget
import java.io.InputStream
import java.io.OutputStream

/**
 * A [LocalTarget] in the phone's own **Downloads** folder, written through MediaStore.
 *
 * This is the default because it is the only place a download is actually *findable*.
 * An app's own external directory (`Android/data/...`) needs no permission but has been
 * hidden from the Files app since Android 11 — the user is told the folder is only
 * visible from a connected computer, which makes a downloaded file feel lost.
 *
 * MediaStore's Downloads collection is the modern answer: an app may add files it owns
 * with no permission at all, and they appear in Downloads alongside everything else.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreTarget(
    private val context: Context,
    /** Path below the app's folder in Downloads, e.g. `reports/2026/q3.txt`. */
    private val relativePath: String,
    private val folderName: String = DEFAULT_FOLDER,
) : LocalTarget {

    override val name: String get() = relativePath.substringAfterLast('/')

    override val identifier: String get() = find()?.toString() ?: relativePath

    override fun exists(): Boolean = find() != null

    override fun size(): Long {
        val uri = find() ?: return 0L
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return 0L
    }

    override fun openWrite(startAt: Long): OutputStream {
        val existing = find()
        val uri = when {
            // Continuing a paused transfer: append to what is already there.
            startAt > 0 && existing != null -> existing
            // Replacing: drop the old entry so the new one starts clean, rather than
            // leaving MediaStore to invent "file (1).txt".
            else -> {
                existing?.let { context.contentResolver.delete(it, null, null) }
                insert()
            }
        }
        val mode = if (startAt > 0) "wa" else "rwt"
        return context.contentResolver.openOutputStream(uri, mode)
            ?: error("Could not open $relativePath in Downloads for writing")
    }

    override fun openRead(): InputStream {
        val uri = find() ?: error("$relativePath is not in Downloads")
        return context.contentResolver.openInputStream(uri)
            ?: error("Could not read $relativePath from Downloads")
    }

    /** `Download/FreeFTP/<sub-folders>/`, which MediaStore requires to end in a slash. */
    private fun relativeDirectory(): String {
        val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val base = "${android.os.Environment.DIRECTORY_DOWNLOADS}/$folderName"
        return if (parent.isEmpty()) "$base/" else "$base/$parent/"
    }

    private fun insert(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDirectory())
        }
        return context.contentResolver.insert(collection, values)
            ?: error("Could not create $relativePath in Downloads")
    }

    private fun find(): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection =
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
        val arguments = arrayOf(relativeDirectory(), name)
        context.contentResolver.query(collection, projection, selection, arguments, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
            }
        return null
    }

    private val collection: Uri
        get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun toString(): String = "MediaStoreTarget(Downloads/$folderName/$relativePath)"

    companion object {
        const val DEFAULT_FOLDER = "FreeFTP"
    }
}
