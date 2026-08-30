package com.freeftp.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.freeftp.core.transfer.FileTarget
import com.freeftp.core.transfer.LocalTarget
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Where downloads are written. */
sealed interface DownloadLocation {

    /** The phone's own Downloads folder, via MediaStore. The default, and browsable. */
    data object PublicDownloads : DownloadLocation

    /**
     * The app's own external folder. Needs no permission, but since Android 11 the Files
     * app refuses to show `Android/data`, so anything here is hard for the user to reach.
     */
    data object AppStorage : DownloadLocation

    /** A folder the user picked, reachable through the granted document tree. */
    data class UserFolder(val treeUri: Uri) : DownloadLocation
}

/**
 * Remembers where downloads go, and hands out a [LocalTarget] for each file.
 *
 * No storage permission is ever requested. The default writes to the phone's Downloads
 * folder through MediaStore, which an app may add its own files to freely, and choosing
 * a different folder goes through the system's folder picker — which *is* the permission
 * prompt. The user grants access to exactly one directory, the grant survives reboots,
 * and dismissing the picker leaves the previous choice untouched.
 */
class DownloadLocationStore(private val context: Context) {

    private val preferences =
        context.getSharedPreferences("freeftp.settings", Context.MODE_PRIVATE)

    /** The app-private fallback, always writable. */
    val appStorageDirectory: File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads").apply { mkdirs() }

    /**
     * The location in force. A saved folder whose grant has been revoked — the user
     * cleared it, or the SD card is gone — silently falls back rather than failing every
     * transfer with a permission error.
     */
    val current: DownloadLocation
        get() {
            val saved = preferences.getString(KEY_TREE_URI, null) ?: return default
            val uri = Uri.parse(saved)
            return if (hasPersistedAccess(uri)) DownloadLocation.UserFolder(uri) else default
        }

    /**
     * MediaStore's Downloads collection where it exists (Android 10+), and the app's own
     * folder on older releases, where writing to public Downloads would mean asking for
     * broad storage permission.
     */
    val default: DownloadLocation
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DownloadLocation.PublicDownloads
        } else {
            DownloadLocation.AppStorage
        }

    /** True when a folder was chosen but its grant no longer holds. */
    val hasLostAccess: Boolean
        get() {
            val saved = preferences.getString(KEY_TREE_URI, null) ?: return false
            return !hasPersistedAccess(Uri.parse(saved))
        }

    /** Records the folder the user picked, keeping access across restarts. */
    fun useUserFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
    }

    /** Reverts to the built-in location, releasing any folder grant. */
    fun useDefault() {
        preferences.getString(KEY_TREE_URI, null)?.let { saved ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(saved),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    /** A human-readable description of where downloads land. */
    fun describe(): String = when (val location = current) {
        DownloadLocation.PublicDownloads -> "Downloads/${MediaStoreTarget.DEFAULT_FOLDER}"
        DownloadLocation.AppStorage -> "App storage · ${appStorageDirectory.absolutePath}"
        is DownloadLocation.UserFolder ->
            DocumentFile.fromTreeUri(context, location.treeUri)?.name
                ?: location.treeUri.lastPathSegment
                ?: location.treeUri.toString()
    }

    /** True when downloads land somewhere the user can browse with the Files app. */
    fun isBrowsableByUser(): Boolean = current != DownloadLocation.AppStorage

    /** Builds the destination for [relativePath], creating intermediate folders. */
    fun targetFor(relativePath: String): LocalTarget = when (val location = current) {
        DownloadLocation.PublicDownloads -> MediaStoreTarget(context, relativePath)
        DownloadLocation.AppStorage -> FileTarget(File(appStorageDirectory, relativePath))
        is DownloadLocation.UserFolder -> DocumentTarget(context, location.treeUri, relativePath)
    }

    private fun hasPersistedAccess(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

    private companion object {
        const val KEY_TREE_URI = "download.treeUri"
    }
}

/**
 * A [LocalTarget] inside a document tree the user granted.
 *
 * Documents have no filesystem path and cannot be seeked, so writing is limited to
 * "replace" and "append" — which is exactly the pair [LocalTarget.openWrite] promises.
 */
class DocumentTarget(
    private val context: Context,
    private val treeUri: Uri,
    private val relativePath: String,
) : LocalTarget {

    override val name: String get() = relativePath.substringAfterLast('/')

    override val identifier: String get() = resolve(create = false)?.uri?.toString() ?: relativePath

    override fun exists(): Boolean = resolve(create = false) != null

    override fun size(): Long = resolve(create = false)?.length() ?: 0L

    override fun openWrite(startAt: Long): OutputStream {
        val document = resolve(create = true)
            ?: error("Could not create $relativePath in the chosen folder")
        // "wa" appends to what is there; "rwt" truncates first.
        val mode = if (startAt > 0) "wa" else "rwt"
        return context.contentResolver.openOutputStream(document.uri, mode)
            ?: error("Could not open $relativePath for writing")
    }

    override fun openRead(): InputStream {
        val document = resolve(create = false) ?: error("$relativePath is not in the chosen folder")
        return context.contentResolver.openInputStream(document.uri)
            ?: error("Could not open $relativePath for reading")
    }

    /** Walks the tree segment by segment, creating folders when [create] is set. */
    private fun resolve(create: Boolean): DocumentFile? {
        var directory = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        for (segment in segments.dropLast(1)) {
            val existing = directory.findFile(segment)?.takeIf { it.isDirectory }
            directory = existing
                ?: (if (create) directory.createDirectory(segment) else null)
                ?: return null
        }
        val leaf = segments.last()
        return directory.findFile(leaf)
            ?: if (create) directory.createFile("application/octet-stream", leaf) else null
    }

    override fun toString(): String = "DocumentTarget($relativePath in $treeUri)"
}
