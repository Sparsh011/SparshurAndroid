package com.sparsh.sparshur

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import com.sparsh.sparshur.devices.*
import com.sparsh.sparshur.lab.LabViewModel
import com.sparsh.sparshur.ui.AppScreen
import com.sparsh.sparshur.ui.theme.SparshurTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Fixtures exist only in the test APK; they never enter PairingStore or the production catalogue. */
class DevicesFeatureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: DevicesViewModel
    private lateinit var store: ViewModelStore
    private var previousPrimary: PrimaryDevice? = null
    private lateinit var oldPolicies: Map<String, SmsPolicy>
    private val fixtures = listOf(ConnectedDevice("fixture:first", "Layout test Mac", "macOS"),
        ConnectedDevice("fixture:second", "Second test Mac", "macOS"))

    @Before fun render() {
        val app = compose.activity.application
        val dao = DeviceDatabase.get(app).primaryDevice()
        previousPrimary = dao.read()
        dao.clear()
        val policies = SmsPolicyStore(app)
        oldPolicies = fixtures.associate { it.id to policies.load(it.id) }
        fixtures.forEach { policies.save(it.id, SmsPolicy()) }
        store = ViewModelStore()
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                DevicesViewModel::class.java -> DevicesViewModel(app, object : ConnectedDeviceSource { override fun devices() = fixtures }) as T
                else -> LabViewModel(app) as T
            }
        })
        model = provider[DevicesViewModel::class.java]
        val pairing = provider[LabViewModel::class.java]
        compose.setContent { SparshurTheme { AppScreen(model, pairing, { it() }, {}, {}, {}) } }
        compose.waitUntil(10000) { !model.loading && !model.primaryBusy }
    }
    @After fun restore() {
        if (::store.isInitialized) store.clear()
        val app = compose.activity.application
        val dao = DeviceDatabase.get(app).primaryDevice()
        previousPrimary?.let(dao::save) ?: dao.clear()
        if (::oldPolicies.isInitialized) oldPolicies.forEach { (id, policy) -> SmsPolicyStore(app).save(id, policy) }
    }

    @Test fun primaryChoiceIsDurableAndDoesNotChangeSmsRules() {
        compose.onNodeWithContentDescription("Make Layout test Mac Primary").performClick()
        compose.waitUntil(10000) { model.primaryId == "fixture:first" && !model.primaryBusy }
        assertEquals("fixture:first", DeviceDatabase.get(compose.activity).primaryDevice().read()?.deviceId)
        compose.onNodeWithContentDescription("Layout test Mac is Primary").assertIsDisplayed()
        assertEquals(SmsMode.OFF, SmsPolicyStore(compose.activity).load("fixture:first").mode)
    }

    @Test fun rulesUseExplicitInputAndAreSavedWithoutChangingAnotherDevice() {
        compose.onNodeWithContentDescription("SMS sharing for Layout test Mac").performClick()
        compose.onNodeWithText("Selected", substring = false).performClick()
        compose.onNodeWithContentDescription("Add a sender").performTextInput("VM-ORG")
        compose.onNodeWithContentDescription("Add sender").performClick()
        compose.onNodeWithContentDescription("Remove sender VM-ORG").assertIsDisplayed()
        compose.onNodeWithText("Test these rules").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Test sender").performScrollTo().performTextInput("BANK")
        compose.onNodeWithContentDescription("Test message").performScrollTo().performTextInput("Invented private message")
        compose.onNodeWithText("Blocked by these rules").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save rules").performClick()
        compose.waitUntil(10000) { !model.savingPolicy && model.policies["fixture:first"]?.mode == SmsMode.ALLOWLIST }
        val saved = SmsPolicyStore(compose.activity).load("fixture:first")
        assertTrue(saved.allows(SmsSample("VM-ORG", "Invented organization message")))
        assertFalse(saved.allows(SmsSample("BANK", "Invented private message")))
        assertEquals(SmsMode.OFF, SmsPolicyStore(compose.activity).load("fixture:second").mode)
    }

    @Test fun unsavedRulesRequireAnExplicitDiscardBeforeNavigation() {
        compose.onNodeWithContentDescription("SMS sharing for Layout test Mac").performClick()
        compose.onNodeWithText("Selected", substring = false).performClick()
        compose.onNodeWithText("Learn", substring = false).performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Save rules").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Discard", substring = false).performClick()
        assertEquals(SmsMode.OFF, SmsPolicyStore(compose.activity).load("fixture:first").mode)
    }

    @Test fun guideStillProvidesAnInteractiveExerciseAndCodeReference() {
        compose.onNodeWithText("Learn", substring = false).performClick()
        compose.onNodeWithText("Explore lesson").performClick()
        compose.onNodeWithText("Change ciphertext").performScrollTo().performClick()
        compose.onNodeWithText("Ciphertext B7", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Where this lives in our code").performScrollTo().performClick()
        compose.onNodeWithText("lab/paired/PairedProtocol.kt", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun captureIsolatedPairedLayoutAndRuleEditor() {
        save("paired-layout-test.png")
        compose.onNodeWithContentDescription("SMS sharing for Layout test Mac").performClick()
        compose.onNodeWithText("Selected", substring = false).performClick()
        save("sms-layout-test.png")
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
