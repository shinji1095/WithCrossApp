package com.example.withcrossdemo.ui.nav

sealed class Screen(val route: String) {
    object Home          : Screen("home")
    object BleSetup      : Screen("ble_setup")
    object DeviceSetting : Screen("device_setting")
    object AppMain       : Screen("app_main")
}
