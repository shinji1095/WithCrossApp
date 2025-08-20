package com.example.withcrossdemo.core.util

import android.Manifest
import android.os.Build

fun runtimeBlePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 31 -> arrayOf(            // Android 12+
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    else -> arrayOf(                                    // Android 6 – 11
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}
