package com.sparsh.sparshur.devices

import android.content.Context
import com.sparsh.sparshur.lab.paired.PairingStore

data class ConnectedDevice(val id: String, val name: String, val platform: String)

interface ConnectedDeviceSource {
    fun devices(): List<ConnectedDevice>
}

/** Only authenticated saved pairings. Reachability is established by a verified send receipt. */
class AvailableDevices(private val context: Context) : ConnectedDeviceSource {
    override fun devices(): List<ConnectedDevice> = PairingStore(context).peer()?.let {
        listOf(ConnectedDevice("paired:${it.pairingId}", "Paired Mac", "macOS"))
    } ?: emptyList()
}
