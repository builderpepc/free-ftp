package com.freeftp.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.freeftp.core.transfer.TransferStatus
import java.io.File

/**
 * Hands a finished download to whichever app the user wants to open it with.
 *
 * The destination may be a MediaStore entry, a document in a folder the user granted, or
 * a plain file in the app's own storage — the first two are already content URIs, and the
 * last has to be wrapped by [FileProvider], because a bare `file://` URI has been
 * rejected by Android since Nougat.
 *
 * @return null on success, or a message explaining why it could not be opened.
 */
fun openDownloadedFile(context: Context, status: TransferStatus): String? {
    val identifier = status.request.target.identifier
    val uri = when {
        identifier.startsWith("content://") -> Uri.parse(identifier)
        else -> {
            val file = File(identifier)
            if (!file.isFile) return "${status.request.displayName} is no longer on this device"
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }.getOrElse { return "Could not share ${file.name} with another app" }
        }
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeOf(status.request.displayName))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "No app on this phone can open ${status.request.displayName}"
    }
}

/** Best guess from the extension; a wildcard type lets the chooser offer everything. */
private fun mimeTypeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return "*/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}
