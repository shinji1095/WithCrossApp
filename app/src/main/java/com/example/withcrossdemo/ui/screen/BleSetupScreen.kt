package com.example.withcrossdemo.ui.screen

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.example.withcrossdemo.ui.nav.Screen
import com.example.withcrossdemo.ui.viewmodel.BleSetupViewModel
import com.google.accompanist.permissions.*
import kotlinx.coroutines.launch
import timber.log.Timber
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BleSetupScreen(
    navController: NavHostController,
    vm: BleSetupViewModel = hiltViewModel()
) {
    /* ---------- 権限 ---------- */
    val perms = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION   // Android ≤ 11
        )
    )
    val ui by vm.ui.collectAsState()

    /* ---------- Bluetooth ON 要求ランチャー ---------- */
    val enableBtLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
               ) { /* resultCode は不要。ON になれば下の LaunchedEffect で検知する */ }

    /* ---------- Bluetooth 状態 ---------- */
    val btAdapter = remember { BluetoothAdapter.getDefaultAdapter() }
    val bluetoothOn by remember { derivedStateOf { btAdapter?.isEnabled == true } }

        /* 権限取得 & Bluetooth ON 後にスキャン開始 */
    LaunchedEffect(perms.allPermissionsGranted, bluetoothOn) {
        if (perms.allPermissionsGranted && bluetoothOn) vm.startScan()
    }

    /* ★ sendDone になった瞬間に一度だけ遷移 */
    LaunchedEffect(ui.sendDone) {
        Timber.d("LaunchedEffect fire: sendDone = %s", ui.sendDone)
        if (ui.sendDone) {
            try {
                navController.navigate(Screen.AppMain.route) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            } catch (e: Exception) {
                Timber.e(e, "Navigation failed")
            }
            vm.clearSendDone()
        }
    }


    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("BLE 設定") },
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
                .padding(16.dp)
        ) {

            /* 権限未許可 */
            if (!perms.allPermissionsGranted) {
                Button(onClick = { perms.launchMultiplePermissionRequest() }) {
                    Text("Bluetooth 権限を許可")
                }
                return@Column
            }

            /* Bluetooth OFF */
            if (!bluetoothOn) {
                Text("Bluetooth が OFF です")
                Button(
                    onClick = {
                        enableBtLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                    )
                        }
                            ) { Text("Bluetooth を ON にする") }
                return@Column
            }

            /* スキャン結果 */
            /* 🔁 修正: スキャン結果をスクロール可能に表示 */
            if (ui.devices.isEmpty()) {
                CircularProgressIndicator()
                Text("スキャン中…")
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(ui.devices) { dev ->
                        Text(
                            "${dev.name ?: "Unknown"} (${dev.address})",
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.connect(dev) }
                                .padding(8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { vm.sendSettings() },
                enabled = ui.connected && !ui.sending
            ) { Text("設定送信") }

            when {
                ui.sending   -> CircularProgressIndicator()
                ui.sendDone  -> Text("送信完了！")           // 遷移は LaunchedEffect で実行済み
            }
        }
    }

    DisposableEffect(Unit) { onDispose { vm.stopScan() } }
}
