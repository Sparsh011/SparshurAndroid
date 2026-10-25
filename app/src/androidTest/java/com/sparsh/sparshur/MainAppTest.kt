package com.sparsh.sparshur

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.sparsh.sparshur.devices.DevicesViewModel
import java.io.File
import org.junit.Rule
import org.junit.Test

class MainAppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realCatalogueHasNoSeededDevicesAndEmptyStateSurvivesRecreation() {
        compose.waitUntil(10000) { !ViewModelProvider(compose.activity)[DevicesViewModel::class.java].loading }
        compose.onNodeWithText("Pair a Mac", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Personal Mac", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Work laptop", substring = false).assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Your Mac, one tap away.").assertIsDisplayed()
    }
    @Test fun captureActualEmptyAndLearningScreens() {
        compose.waitUntil(10000) { !ViewModelProvider(compose.activity)[DevicesViewModel::class.java].loading }
        save("sparshur-devices.png")
        compose.onNodeWithText("Learn", substring = false).performClick()
        save("sparshur-learn.png")
        compose.onNodeWithText("Settings", substring = false).performClick()
        save("sparshur-settings.png")
    }
    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        // Platform ripple rendering uses wall-clock time, independently of the Compose clock.
        android.os.SystemClock.sleep(500)
        File(compose.activity.cacheDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
