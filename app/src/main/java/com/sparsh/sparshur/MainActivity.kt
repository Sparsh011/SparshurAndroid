package com.sparsh.sparshur

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sparsh.sparshur.devices.DevicesActivity
import com.sparsh.sparshur.devices.DevicesViewModel
import com.sparsh.sparshur.lab.LabActivity
import com.sparsh.sparshur.lab.LabViewModel
import com.sparsh.sparshur.lab.LocalNetworkActivity
import com.sparsh.sparshur.ui.AppScreen
import com.sparsh.sparshur.ui.theme.SparshurTheme
import com.sparsh.sparshur.widget.DevicesWidgetProvider

class MainActivity : LocalNetworkActivity() {
    private lateinit var devices: DevicesViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        devices = ViewModelProvider(this)[DevicesViewModel::class.java]
        val pairing = ViewModelProvider(this)[LabViewModel::class.java]
        onNetworkPermissionDenied = pairing::permissionDenied
        setContent {
            SparshurTheme {
                AppScreen(
                    devices, pairing, ::withLocalNetworkPermission,
                    send = { id -> startActivity(DevicesActivity.intent(this, id)) },
                    scan = {
                        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .enableAutoZoom().build()
                        GmsBarcodeScanning.getClient(this, options).startScan()
                            .addOnSuccessListener { result -> result.rawValue?.let(pairing::importBundle) ?: pairing.scannerUnavailable() }
                            .addOnFailureListener { pairing.scannerUnavailable() }
                    },
                    experiments = { startActivity(Intent(this, LabActivity::class.java)) }
                )
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::devices.isInitialized) {
            devices.refresh()
            ViewModelProvider(this)[LabViewModel::class.java].refreshSavedPeer()
            DevicesWidgetProvider.updateAll(this)
        }
    }
}
