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
import com.example.withcrossdemo.ui.viewmodel.InputSource

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
    var saveImagesOn by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val saveDir = remember {
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "stream")
            .also { it.mkdirs() }
    }
    val saveDirPath = remember { saveDir.absolutePath }
    val sdf = remember { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }

    // ==== デバッグ用Bitmap描画 ====
    LaunchedEffect(debugOn) {
        uiBitmap = null
        if (!debugOn) return@LaunchedEffect
        jpegFlow
            .mapLatest { bytes ->
                withContext(Dispatchers.Default) {
                    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            }
            .collect { bmp -> uiBitmap = bmp }
    }

    // ==== 1秒間隔の保存 ====
    LaunchedEffect(saveImagesOn) {
        if (!saveImagesOn) return@LaunchedEffect
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

    // モード変化ログ
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---------- トグル & 状態 ----------
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("デバッグ出力")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = debugOn, onCheckedChange = { debugOn = it })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("画像保存(1秒おき)")
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = saveImagesOn, onCheckedChange = { saveImagesOn = it })
                }
            }
            item {
                if (saveImagesOn) {
                    Text("保存先: $saveDirPath", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text("現在モード: ${mode.name}")
            }

            item {
                if (mode == RunMode.OBJECT) {
                    TextField(
                        value = labels,
                        onValueChange = {},
                        label = { Text("検知ラベル") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = csvName,
                    onValueChange = { csvName = it },
                    label = { Text("CSV ファイル名") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Button(onClick = { Timber.i("TODO: export CSV $csvName") }) { Text("CSV 出力") }
            }

            item { Divider() }

            // ---------- デバッグ UI ----------
            item {
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

                    val inputSrc by vm.inputSource.collectAsState()
                    val gstStats by vm.gstStats.collectAsState()
                    val rtpPort by vm.rtpListenPort.collectAsState()
                    val rtspUrl by vm.rtspUrl.collectAsState()

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Input:", modifier = Modifier.padding(end = 8.dp))
                        AssistChip(onClick = { vm.selectInputSource(InputSource.UDP_JPEG) }, label = { Text("UDP/JPEG") })
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = { vm.selectInputSource(InputSource.GST_RTP_JPEG) }, label = { Text("GST RTP/JPEG") })
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = { vm.selectInputSource(InputSource.GST_RTSP_JPEG) }, label = { Text("GST RTSP/JPEG") })
                    }
                    Text("選択中: $inputSrc", style = MaterialTheme.typography.bodySmall)

                    if (inputSrc == InputSource.GST_RTP_JPEG) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = rtpPort.toString(),
                                onValueChange = { it.toIntOrNull()?.let(vm::setRtpPort) },
                                label = { Text("RTP Listen Port") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { vm.selectInputSource(InputSource.GST_RTP_JPEG) }) { Text("再接続") }
                        }
                    } else if (inputSrc == InputSource.GST_RTSP_JPEG) {
                        OutlinedTextField(
                            value = rtspUrl,
                            onValueChange = vm::setRtspUrl,
                            label = { Text("RTSP URL (JPEG over RTP/UDP)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { vm.selectInputSource(InputSource.GST_RTSP_JPEG) }) { Text("再接続") }
                        }
                    }

                    gstStats?.let { st ->
                        Text(
                            "GST fps=%.1f, avgΔ=%.1fms, jitter=%.1fms"
                                .format(st.framesPerSec, st.avgDeltaMs, st.jitterMs),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Pipeline: ${st.pipeline}", style = MaterialTheme.typography.bodySmall)
                    }

                    Divider()
                }
            }

            // ---------- ログ ----------
            item { Text("ログ", style = MaterialTheme.typography.titleSmall) }
            items(logs) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }

            if (BuildConfig.DEBUG) {
                item { Text("MODE = $mode", Modifier.padding(4.dp)) }
            }
        }
    }
}
