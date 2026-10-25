package com.sparsh.sparshur.lab

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sparsh.sparshur.lab.LabScreen
import com.sparsh.sparshur.lab.LabViewModel
import com.sparsh.sparshur.lab.LocalNetworkActivity
import com.sparsh.sparshur.ui.theme.SparshurTheme

class LabActivity : LocalNetworkActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[LabViewModel::class.java]
        onNetworkPermissionDenied = model::permissionDenied
        setContent {
            SparshurTheme {
                LabScreen(model, ::withLocalNetworkPermission) {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
                    GmsBarcodeScanning.getClient(this, options).startScan()
                        .addOnSuccessListener { result ->
                            result.rawValue?.let(model::importBundle) ?: model.scannerUnavailable()
                        }.addOnFailureListener { model.scannerUnavailable() }
                }
            }
        }
    }
}
