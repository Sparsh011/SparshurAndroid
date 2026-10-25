package com.sparsh.sparshur

import android.graphics.Bitmap
import com.sparsh.sparshur.lab.LabActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class LabScreenTest {
    @get:Rule val compose = createAndroidComposeRule<LabActivity>()

    @Test fun learnerCanEncryptDecryptAndRejectTampering() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("Inspecting the key…").fetchSemanticsNodes().isEmpty() }
        savePreview("bridge-paired-home.png")
        compose.onNodeWithText("Local key lab").performClick()
        savePreview("bridge-lab-key.png")
        compose.onNodeWithText("Encrypt", substring = false).performClick()
        compose.onNodeWithText("Encrypt on this phone →").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Verify & decrypt on phone").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Verify & decrypt on phone").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText(
                "Verified and decrypted on this phone:",
                substring = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "Verified and decrypted on this phone:",
            substring = true
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Flip one byte · test tampering").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Tampering rejected.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Tampering rejected.", substring = true).performScrollTo().assertIsDisplayed()
        savePreview("bridge-lab-tamper.png")
    }

    private fun savePreview(name: String) {
        // Only this test's invented sample is captured, never arbitrary user input.
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
