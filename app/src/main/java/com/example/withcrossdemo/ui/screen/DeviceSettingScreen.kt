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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingScreen(navController: NavHostController) {
    val vm: BleSetupViewModel = hiltViewModel()
    val ctx = LocalContext.current

    var ip      by remember { mutableStateOf("") }
    var ssid    by remember { mutableStateOf("") }
    var pw      by remember { mutableStateOf("") }
    var port    by remember { mutableStateOf("") }

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val new = DeviceSetting(ssid, pw, ip, port)
                    Timber.i("[UI] save click %s", new)           // ★追加
                    vm.saveSetting(new)
                    navController.popBackStack()
                }) { Text("保存") }

                OutlinedButton(onClick = { navController.popBackStack() }) { Text("閉じる") }
            }
        }
    }
}
