package com.sparsh.sparshur

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.sparsh.sparshur.network.relay.RelayActivity
import com.sparsh.sparshur.network.relay.RelayPhase
import com.sparsh.sparshur.network.relay.RelayViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RelayScreenTest {
    @get:Rule val compose = createAndroidComposeRule<RelayActivity>()

    @Test fun enteringAndRecreatingDiagnosticDoesNotConnectOrClaimDelivery() {
        compose.onNodeWithText("Connection only").assertIsDisplayed()
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Relay WSS URL").performTextInput("wss://relay.example.test/v1/relay")
        compose.onNodeWithContentDescription("Relay ID").performTextInput("mini-test")
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[RelayViewModel::class.java]
            assertEquals(RelayPhase.STOPPED, model.status.phase)
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        compose.onNodeWithText("Authenticated to relay").assertDoesNotExist()
        compose.onNodeWithContentDescription("Relay WSS URL").assertTextContains("wss://relay.example.test/v1/relay")
        compose.onNodeWithContentDescription("Relay ID").assertTextContains("mini-test")
        compose.runOnIdle { assertEquals(RelayPhase.STOPPED, ViewModelProvider(compose.activity)[RelayViewModel::class.java].status.phase) }
    }
}
