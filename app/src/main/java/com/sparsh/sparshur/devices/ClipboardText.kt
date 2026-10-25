package com.sparsh.sparshur.devices

import android.content.ClipData
import android.content.ClipDescription

class ClipRejected(val title: String, val detail: String) : IllegalArgumentException(title)

/** Never resolves a URI or inspects the text of a clip marked sensitive. */
object ClipboardText {
    fun read(clip: ClipData?): String {
        if (clip == null || clip.itemCount == 0) throw ClipRejected("Clipboard is empty", "Copy some text, then tap your Mac again.")
        if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
            throw ClipRejected("Sensitive clip blocked", "This item is marked sensitive and won’t be shared.")
        if (!clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            throw ClipRejected("Text clips only", "Copy text rather than a file or image, then try again.")
        val text = clip.getItemAt(0).text?.toString() ?: throw ClipRejected("Text clips only", "This clipboard item has no text.")
        if (text.isBlank()) throw ClipRejected("Clipboard is empty", "Copy some text, then tap your Mac again.")
        if (text.toByteArray(Charsets.UTF_8).size > 4096) throw ClipRejected("Clip is too large", "This connection supports up to 4096 UTF-8 bytes.")
        return text
    }
}
