package com.sparsh.sparshur.devices

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import com.sparsh.sparshur.lab.paired.PairedFile
import java.io.ByteArrayOutputStream

internal sealed interface SharedContent {
    data class Text(val value: String) : SharedContent
    data class File(val uri: Uri, val mime: String?) : SharedContent {
        init {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty()) {
                throw ClipRejected("File unavailable", "Share the file from the app that can open it.")
            }
        }

        fun read(resolver: ContentResolver): PairedFile {
            val displayName = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } catch (_: UnsupportedOperationException) {
                null
            }
            val mimeTypes = MimeTypeMap.getSingleton()
            val guessedType = displayName?.substringAfterLast('.', "")?.lowercase()?.let(mimeTypes::getMimeTypeFromExtension)
            val contentType = PairedFile.safeMime(resolver.getType(uri) ?: mime ?: guessedType)
            val extension = mimeTypes.getExtensionFromMimeType(contentType)
            val fallback = if (extension == null) "shared-file" else "shared-file.$extension"
            val name = displayName?.takeIf { it.isNotBlank() && it != "." && it != ".." && it.toByteArray().size <= 255 &&
                it.none { c -> c == '/' || c == '\\' || c.isISOControl() } } ?: fallback

            val bytes = resolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    if (output.size() + read > PairedFile.MAX_BYTES) {
                        throw ClipRejected("File is too large", "Share a file up to 5 MiB.")
                    }
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            } ?: throw ClipRejected("File unavailable", "Share the file from the app that can open it.")
            return PairedFile(name, contentType, bytes)
        }
    }

    companion object {
        fun clipboard(clip: ClipData?): SharedContent {
            ClipboardText.checkSensitive(clip)
            val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val uri = item?.uri
            if (uri != null && (item.text == null || clip.description.hasMimeType("image/*") ||
                    clip.description.hasMimeType("text/uri-list"))) {
                val mime = (0 until clip.description.mimeTypeCount).map(clip.description::getMimeType)
                    .firstOrNull { it != "text/uri-list" }
                return File(uri, mime)
            }
            return Text(ClipboardText.read(clip))
        }

        fun share(intent: Intent): File {
            if (intent.action != Intent.ACTION_SEND) throw ClipRejected("File unavailable", "Share one file with Sparshur.")
            ClipboardText.checkSensitive(intent.clipData)
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                ?: throw ClipRejected("File unavailable", "Choose a file or image to share with Sparshur.")
            return File(uri, intent.type)
        }
    }
}
