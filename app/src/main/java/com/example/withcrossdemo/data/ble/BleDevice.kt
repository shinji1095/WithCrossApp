package com.example.withcrossdemo.data.ble

import android.bluetooth.BluetoothDevice

data class BleDevice(
    val device: BluetoothDevice,
    val name: String?,
    val address: String
)
