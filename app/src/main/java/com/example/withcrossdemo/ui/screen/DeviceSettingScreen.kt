package com.example.withcrossdemo.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.example.withcrossdemo.data.local.datastore.DeviceSetting
import com.example.withcrossdemo.ui.viewmodel.BleSetupViewModel
import timber.log.Timber

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


private val android.content.Context.signalPrefs by preferencesDataStore(name = "signal_prefs")

private object SignalPrefsKeysUI {
    val ANNOUNCE_MS = longPreferencesKey("announce_ms")
    val TICK_MS     = longPreferencesKey("tick_ms")
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingScreen(navController: NavHostController) {
    val vm: BleSetupViewModel = hiltViewModel()
    val ctx = LocalContext.current

    var announceSec by remember { mutableStateOf("4") } // 既定 4s
    var tickSec     by remember { mutableStateOf("1") } // 既定 1s

    var ip      by remember { mutableStateOf("") }
    var ssid    by remember { mutableStateOf("") }
    var pw      by remember { mutableStateOf("") }
    var port    by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("デバイス設定") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(ip,   { ip = it },   label = { Text("IP アドレス") })
            OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") })
            OutlinedTextField(pw,   { pw = it },   label = { Text("パスワード") })
            OutlinedTextField(port, { port = it }, label = { Text("ポート") })
            OutlinedTextField(
                value = announceSec,
                onValueChange = { announceSec = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("アナウンス間隔 [秒]（red/green）") }
            )
            OutlinedTextField(
                value = tickSec,
                onValueChange = { tickSec = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("刻音間隔 [秒]（red_sound/green_sound）") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val new = DeviceSetting(ssid, pw, ip, port)
                    Timber.i("[UI] save click %s", new)
                    vm.saveSetting(new)

                    val aMs = ((announceSec.toDoubleOrNull() ?: 4.0) * 1000).toLong().coerceAtLeast(100L)
                    val tMs = ((tickSec.toDoubleOrNull() ?: 1.0) * 1000).toLong().coerceAtLeast(100L)

                    // ✅ 非 @Composable コンテキストなので coroutineScope を使う
                    scope.launch {
                        ctx.signalPrefs.edit { p ->
                            p[SignalPrefsKeysUI.ANNOUNCE_MS] = aMs
                            p[SignalPrefsKeysUI.TICK_MS]     = tMs
                        }
                        Toast.makeText(ctx, "設定を保存しました（音声間隔: ${announceSec}s / ${tickSec}s）", Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    }
                }) { Text("保存") }



                OutlinedButton(onClick = { navController.popBackStack() }) { Text("閉じる") }
            }
        }
    }
}
