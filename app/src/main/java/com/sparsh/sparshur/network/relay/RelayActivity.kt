package com.sparsh.sparshur.network.relay

import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.sparsh.sparshur.BuildConfig
import com.sparsh.sparshur.lab.LocalNetworkActivity
import com.sparsh.sparshur.ui.*
import com.sparsh.sparshur.ui.theme.SparshurTheme

/** Registered only in the debug manifest; exiting this screen stops the session. */
class RelayActivity : LocalNetworkActivity() {
    private lateinit var model: RelayViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) { finish(); return }
        enableEdgeToEdge()
        model = ViewModelProvider(this)[RelayViewModel::class.java]
        onNetworkPermissionDenied = { model.localNetworkPermission(false) }
        setContent {
            SparshurTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        QuietButton("Back", ::finish)
                        PageHeader("Networking lesson", "Mac mini relay", "Connect and prove your Android identity over TLS.")
                        Panel(Modifier.fillMaxWidth()) {
                            Text("Connection only", style = MaterialTheme.typography.titleMedium)
                            SecondaryText("The relay and receiving Mac are separate roles. This lesson authenticates your connection; message delivery and relay pairing are still being built.")
                            SecondaryText("The connection stops when you leave this screen. Nothing runs in the background.")
                        }
                        AppField(model.endpoint, model::editEndpoint, "Relay WSS URL",
                            placeholder = "wss://relay.example.com/bridge/v1/ws")
                        if (Build.VERSION.SDK_INT >= 37 && model.status.phase in setOf(RelayPhase.STOPPED, RelayPhase.FAILED)) {
                            SecondaryText("A relay on your Wi-Fi or private network may need local-network permission. Public internet relays don’t need it.")
                            QuietButton("Allow access to a local relay", {
                                withLocalNetworkPermission { model.localNetworkPermission(true) }
                            })
                        }
                        Panel(Modifier.fillMaxWidth()) {
                            Text(when (model.status.phase) {
                                RelayPhase.STOPPED -> "Disconnected"
                                RelayPhase.CONNECTING -> "Connecting over TLS"
                                RelayPhase.AUTHENTICATING -> "Proving device identity"
                                RelayPhase.CONNECTED -> "Authenticated to relay"
                                RelayPhase.WAITING_TO_RETRY -> "Waiting to reconnect (${model.status.retry}/6)"
                                RelayPhase.FAILED -> "Connection stopped"
                            }, style = MaterialTheme.typography.titleMedium)
                            SecondaryText(when (model.status.problem) {
                                RelayProblem.TLS -> "TLS validation failed. Check the relay certificate, hostname and TLS 1.3 configuration."
                                RelayProblem.AUTHORIZATION -> "The relay refused this identity or session. Check enrollment and revocation on the relay."
                                RelayProblem.PROTOCOL -> "The relay did not follow the expected connection protocol. Check its version and configuration."
                                RelayProblem.IDENTITY -> "The saved signing identity could not be used. No key was replaced."
                                RelayProblem.RETRY_LIMIT -> "The reconnect limit was reached. Check the network and try explicitly again."
                                RelayProblem.NETWORK -> "The connection was interrupted. A retry uses fresh authentication."
                                null -> if (model.status.phase == RelayPhase.CONNECTED) "No message has been delivered. This is a relay connection, not a Mac delivery receipt." else model.notice
                            })
                        }
                        if (model.status.phase in setOf(RelayPhase.STOPPED, RelayPhase.FAILED)) {
                            ActionButton("Connect to relay", model::connect, Modifier.fillMaxWidth(), enabled = model.deviceId.isNotEmpty())
                        } else ActionButton("Disconnect", model::disconnect, Modifier.fillMaxWidth(), primary = false)
                        if (model.deviceId.isNotEmpty()) Panel(Modifier.fillMaxWidth()) {
                            Text("Public enrollment details", style = MaterialTheme.typography.titleMedium)
                            SecondaryText("Register these on the Go relay through your trusted admin path. These are public identifiers, not private keys.")
                            SelectionContainer { Text("Device ID\n${model.deviceId}\n\nEd25519 public key (Base64)\n${model.publicKey}",
                                style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); if (::model.isInitialized) model.foreground() }
    override fun onStop() { if (::model.isInitialized) model.background(); super.onStop() }
}
