package com.sparsh.sparshur

import android.graphics.Bitmap
import com.sparsh.sparshur.lab.LabActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.sparsh.sparshur.lab.paired.PairingStore
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PairedScreenTest {
    @get:Rule val compose = createAndroidComposeRule<LabActivity>()

    @Test fun learnerCanEncryptSendAndVerifyMacFromUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(PairingStore(context).peer() != null)
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Encrypt for paired Mac").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Encrypt for paired Mac").performScrollTo().performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Send to Mac & verify receipt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send to Mac & verify receipt").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(
                "Mac verified your signature, unwrapped",
                substring = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Mac verified your signature, unwrapped", substring = true).performScrollTo()
        File(context.cacheDir, "bridge-paired-decrypted.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
