package com.sparsh.sparshur

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.sparsh.sparshur.devices.ConnectedDevice
import com.sparsh.sparshur.devices.ShareFileActions
import com.sparsh.sparshur.devices.ShareFileActivity
import com.sparsh.sparshur.ui.theme.SparshurTheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ShareFileScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sendPickerOffersEachDeviceAndUsesTheSelectedId() {
        val devices = listOf(ConnectedDevice("first", "Desk Mac", "macOS"), ConnectedDevice("second", "Travel Mac", "macOS"))
        val selected = mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { SparshurTheme { Column { ShareFileActions(devices, {}, selected::add) } } }
            }
            compose.onNodeWithText("Copy on this device").assertIsDisplayed()
            compose.onNodeWithText("Send to…").performClick()
            compose.onNodeWithText("Desk Mac").assertIsDisplayed()
            compose.onNodeWithText("Travel Mac").performClick()
            compose.runOnIdle { assertEquals(listOf("second"), selected) }
        }
    }

    @Test fun sharedPngCanBeCopiedAndReadAfterTheSourceIsGone() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val png = ByteArrayOutputStream().use { bytes ->
            val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { image.recycle() }
            bytes.toByteArray()
        }
        val source = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "sparshur-share-copy-test.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }))
        var sourceRemoved = false
        try {
            checkNotNull(resolver.openOutputStream(source)).use { it.write(png) }
            val intent = Intent(context, ShareFileActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<ShareFileActivity>(intent).use { scenario ->
                compose.onNodeWithText("Copy on this device").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Copied on this device").fetchSemanticsNodes().isNotEmpty() }
                resolver.delete(source, null, null)
                sourceRemoved = true
                scenario.onActivity { activity ->
                    val clip = checkNotNull(activity.getSystemService(ClipboardManager::class.java).primaryClip)
                    assertTrue(clip.description.hasMimeType("image/png"))
                    val copied = checkNotNull(clip.getItemAt(0).uri)
                    assertEquals("${context.packageName}.clipboard", copied.authority)
                    assertEquals("image/png", resolver.getType(copied))
                    assertArrayEquals(png, checkNotNull(resolver.openInputStream(copied)).use { it.readBytes() })
                }
            }
        } finally { if (!sourceRemoved) resolver.delete(source, null, null) }
    }
}
