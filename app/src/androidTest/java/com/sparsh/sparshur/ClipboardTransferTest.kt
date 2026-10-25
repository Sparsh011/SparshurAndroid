package com.sparsh.sparshur

import android.content.ClipData
import android.net.Uri
import android.os.PersistableBundle
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sparsh.sparshur.devices.*
import com.sparsh.sparshur.widget.DevicesWidgetProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClipboardTransferTest {
    @get:Rule val compose = createAndroidComposeRule<DevicesActivity>()
    @Test fun sensitiveEmptyUriAndOversizeClipsAreRejected() {
        val sensitive = ClipData.newPlainText("test", "Sensitive test input")
        sensitive.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        assertEquals("Sensitive clip blocked", assertThrows(ClipRejected::class.java) { ClipboardText.read(sensitive) }.title)
        assertEquals("Clipboard is empty", assertThrows(ClipRejected::class.java) { ClipboardText.read(null) }.title)
        assertEquals("Text clips only", assertThrows(ClipRejected::class.java) { ClipboardText.read(ClipData.newRawUri("test", Uri.parse("content://test/item"))) }.title)
        assertEquals("Clip is too large", assertThrows(ClipRejected::class.java) { ClipboardText.read(ClipData.newPlainText("test", "🔐".repeat(1100))) }.title)
        assertEquals("test text", ClipboardText.read(ClipData.newPlainText("test", "test text")))
    }
    @Test fun staleWidgetDestinationNeverSelectsAnotherRecipient() {
        val activity = compose.activity
        val original = activity.intent
        try {
            compose.runOnIdle { DevicesWidgetProvider.openDevice(activity, "removed-device").send() }
            compose.onNodeWithText("Device unavailable").assertIsDisplayed()
        } finally { compose.runOnIdle { activity.intent = original } }
    }
    @Test fun widgetHasNoPlaceholderDevicesAndFitsCompactMinimumSize() {
        compose.runOnIdle {
            val context = compose.activity
            val density = context.resources.displayMetrics.density
            val width = (250 * density).toInt()
            val height = (180 * density).toInt()
            val widget = DevicesWidgetProvider.views(context, emptyList()).apply(context, null)
            widget.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            widget.layout(0, 0, width, height)
            val list = widget.findViewById<android.widget.LinearLayout>(R.id.widget_device_list)
            assertEquals(1, list.childCount)
            assertEquals("Pair your Mac to show it here.", (list.getChildAt(0) as android.widget.TextView).text.toString())
            assertTrue(list.getChildAt(0).bottom <= list.height)
        }
    }
}
