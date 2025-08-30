package com.example.withcrossdemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.withcrossdemo.service.AiService
import com.example.withcrossdemo.ui.nav.Screen
import com.example.withcrossdemo.ui.screen.*
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // onCreate 内の先頭あたりに追加（重複ロードは安全です）
        try {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("gstbridge")   // ← 後述のJNIブリッジSO
        } catch (t: Throwable) {
            t.printStackTrace()
            // 既存のエラーハンドリングに合わせて必要なら finish() など
        }
        setContent {
            MaterialTheme {
                Surface {
                    val navController: NavHostController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route
                    ) {
                        composable(Screen.Home.route)          { HomeScreen(navController) }
                        composable(Screen.BleSetup.route)      { BleSetupScreen(navController) }
                        composable(Screen.DeviceSetting.route) { DeviceSettingScreen(navController) }
                        composable(Screen.AppMain.route)       { AppScreen(navController) }
                    }
                }
            }
        }
        startService(Intent(this, AiService::class.java))
    }
}
