package com.sparsh.sparshur

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.sparsh.sparshur.devices.*
import com.sparsh.sparshur.lab.paired.PairingStore
import com.sparsh.sparshur.widget.DevicesWidgetProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
    @Test fun imageClipboardAndFileShareKeepTheContentUri() {
        val uri = Uri.parse("content://test/image.png")
        val clip = ClipData(ClipDescription("Image", arrayOf("image/png")), ClipData.Item(uri))
        assertEquals(SharedContent.File(uri, "image/png"), SharedContent.clipboard(clip))
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(SharedContent.File(uri, "image/png"), SharedContent.share(send))
        val app = compose.activity
        send.setPackage(app.packageName)
        assertEquals(ShareFileActivity::class.java.name, send.resolveActivity(app.packageManager)?.className)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        assertEquals("Sensitive clip blocked", assertThrows(ClipRejected::class.java) { SharedContent.clipboard(clip) }.title)
        send.putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///private/image.png"))
        assertThrows(ClipRejected::class.java) { SharedContent.share(send) }
    }
    @Test fun staleWidgetDestinationNeverSelectsAnotherRecipient() {
        val activity = compose.activity
        val original = activity.intent
        try {
            compose.runOnIdle { DevicesWidgetProvider.openDevice(activity, "removed-device").send() }
            compose.onNodeWithText("Device unavailable").assertIsDisplayed()
        } finally { compose.runOnIdle { activity.intent = original } }
    }
    @Test fun sendsClipboardToSavedPeerAndShowsVerifiedReceipt() {
        val host = InstrumentationRegistry.getArguments().getString("pairedReceiverHost")
        assumeTrue("Pair with a test Mac and provide its expected receiver address", host != null)
        val activity = compose.activity
        val peer = checkNotNull(PairingStore(activity).peer())
        assertEquals(host, peer.host)
        assertEquals(8787, peer.port)
        sendClip(ClipData.newPlainText("test", "Sparshur paired clipboard test"), peer.pairingId,
            "Mac receipt signature verified. Your clip arrived.")
    }

    @Test fun sendsAContentUriImageFromTheClipboard() {
        val host = InstrumentationRegistry.getArguments().getString("pairedReceiverHost")
        assumeTrue("Pair with a test Mac and provide its expected receiver address", host != null)
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val activity = compose.activity
        val peer = checkNotNull(PairingStore(activity).peer())
        assertEquals(host, peer.host)
        val resolver = activity.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "sparshur-test-clipboard.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }))
        try {
            val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
            sendClip(ClipData.newUri(resolver, "test image", uri), peer.pairingId,
                "Mac receipt signature verified. Save the file from the Mac inbox.")
        } finally { resolver.delete(uri, null, null) }
    }

    private fun sendClip(clip: ClipData, pairingId: String, receipt: String) {
        val activity = compose.activity
        val original = activity.intent
        try {
            compose.runOnIdle {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                DevicesWidgetProvider.openDevice(activity, "paired:$pairingId").send()
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText(receipt).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(receipt).assertIsDisplayed()
        } finally {
            compose.runOnIdle { activity.intent = original }
        }
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
