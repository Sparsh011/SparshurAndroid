package com.sparsh.sparshur.devices

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

class ClipboardFileProvider : FileProvider()

internal object SharedFileClipboard {
    @Synchronized
    fun copy(context: Context, source: SharedContent.File) {
        val file = source.read(context.contentResolver)
        val directory = File(context.cacheDir, "clipboard")
        check(directory.isDirectory || directory.mkdirs())
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(file.mime.lowercase()) ?: "bin"
        val cached = File.createTempFile("clip-", ".$extension", directory)
        try {
            cached.writeBytes(file.data)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.clipboard", cached, file.name)
            val clip = ClipData(file.name, arrayOf(file.mime), ClipData.Item(uri))
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        } catch (exception: Exception) {
            cached.delete()
            throw exception
        }
        // The clipboard now points to this copy. Retain it after the source app's grant ends.
        directory.listFiles()?.forEach { if (it != cached) it.delete() }
    }
}
