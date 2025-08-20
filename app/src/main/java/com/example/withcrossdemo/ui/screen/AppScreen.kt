package com.example.withcrossdemo.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.example.withcrossdemo.BuildConfig
import com.example.withcrossdemo.ui.viewmodel.AppViewModel
import com.example.withcrossdemo.ui.viewmodel.RunMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    navController: NavHostController,
    vm: AppViewModel = hiltViewModel()
) {
    var debugOn by remember { mutableStateOf(false) }
    var csvName by remember { mutableStateOf("log.csv") }

    val mode  by vm.mode.collectAsState()
    val logs  = remember { mutableStateListOf<String>() }
    val labels by vm.detectedLabels.collectAsState()

    val scope = rememberCoroutineScope()
    val jpegFlow = remember { vm.jpegFlow }  // SharedFlow<ByteArray>
    var uiBitmap by remember { mutableStateOf<Bitmap?>(null) }


    // ログにモード変化を追記
    LaunchedEffect(debugOn) {
        uiBitmap = null
        if (!debugOn) return@LaunchedEffect
        jpegFlow
            .mapLatest { bytes ->
                withContext(Dispatchers.Default) {
                    // UI 用は軽量化（RGB_565）
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }
            .collect { bmp -> uiBitmap = bmp }
    }

    LaunchedEffect(mode) { logs.add("MODE → ${mode.name}") }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("アプリ画面") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            /* ---------- トグル & 状態 ---------- */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("デバッグ出力")
                Spacer(Modifier.width(8.dp))
                Switch(checked = debugOn, onCheckedChange = { debugOn = it })
            }

            Text("現在モード: ${mode.name}")

            if (mode == RunMode.OBJECT) {
                TextField(
                    value = labels,
                    onValueChange = {},
                    label = { Text("検知ラベル") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = csvName,
                onValueChange = { csvName = it },
                label = { Text("CSV ファイル名") }
            )

            Button(onClick = { Timber.i("TODO: export CSV $csvName") }) { Text("CSV 出力") }

            Divider()

            /* ---------- デバッグ UI ---------- */
            if (debugOn) {
                uiBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "stream frame",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmp.width / bmp.height.toFloat())
                    )
                } ?: Text("画像がまだ届いていません…", style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(
                    value = mode.name,
                    onValueChange = {},
                    label = { Text("受信コマンド") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Divider()
            }

            /* ---------- ログ ---------- */
            LazyColumn(Modifier.weight(1f)) {
                items(logs) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (BuildConfig.DEBUG) {
        Text("MODE = $mode", Modifier.padding(4.dp))
    }
}