package com.example.everyreader

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

fun mimeFor(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"

fun shareFiles(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris: List<Uri> = files.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(files[0])
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share"))
}

fun deleteFiles(context: Context, files: List<File>): Int {
    var count = 0
    files.forEach { f ->
        if (runCatching { f.delete() }.getOrDefault(false)) {
            count++
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
        }
    }
    return count
}