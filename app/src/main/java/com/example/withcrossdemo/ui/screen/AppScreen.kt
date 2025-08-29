package com.example.withcrossdemo.ui.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.example.withcrossdemo.BuildConfig
import com.example.withcrossdemo.ui.viewmodel.AppViewModel
import com.example.withcrossdemo.ui.viewmodel.RunMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // ==== 追加: 保存トグル ====
    // 端末回転でも維持したいので rememberSaveable
    var saveImagesOn by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val saveDir = remember {
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "stream")
            .also { it.mkdirs() }
    }
    val saveDirPath = remember { saveDir.absolutePath }
    val sdf = remember { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }

    // ==== 既存: デバッグ用Bitmap描画 ====
    // debugOn が true の間だけ UI 表示用に Bitmap 化
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

    // ==== 追加: 1秒間隔での保存処理（デバッグ表示と独立）====
    LaunchedEffect(saveImagesOn) {
        if (!saveImagesOn) return@LaunchedEffect
        // 1秒に1枚、直近の最新フレームのみ保存
        jpegFlow
            .sample(1000)
            .collectLatest { bytes ->
                try {
                    val name = "${sdf.format(Date())}.jpg"
                    withContext(Dispatchers.IO) {
                        File(saveDir, name).outputStream().use { it.write(bytes) }
                    }
                    Timber.d("Saved JPEG: %s (%dB)", name, bytes.size)
                } catch (e: Exception) {
                    Timber.e(e, "Save failed")
                }
            }
    }

    // ログにモード変化を追記（既存）
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

            // ==== 追加: 画像保存トグル ====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("画像保存(1秒おき)")
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = saveImagesOn,
                    onCheckedChange = { saveImagesOn = it }
                )
            }
            if (saveImagesOn) {
                Text(
                    "保存先: $saveDirPath",
                    style = MaterialTheme.typography.bodySmall
                )
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
