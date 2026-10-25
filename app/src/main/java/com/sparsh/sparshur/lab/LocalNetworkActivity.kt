package com.sparsh.sparshur.lab

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Runtime permission applies on Android 17+ because this app targets API 37. */
abstract class LocalNetworkActivity : ComponentActivity() {
    private var pending: (() -> Unit)? = null
    protected var onNetworkPermissionDenied: () -> Unit = {}
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else onNetworkPermissionDenied()
    }

    protected fun withLocalNetworkPermission(action: () -> Unit) {
        val name = "android.permission.ACCESS_LOCAL_NETWORK"
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, name) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = action
            permission.launch(name)
        } else {
            action()
        }
    }
}
