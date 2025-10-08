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
import androidx.compose.runtime.collectAsState
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
import com.example.withcrossdemo.ui.viewmodel.AppViewModel.RgbaFrame

@Composable
private fun SignalDebugPanel(viewModel: AppViewModel) {
    val signalLines by viewModel.signalDebugLines.collectAsState()
    if (signalLines.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Signal Debug",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 最新から最大8行程度を見せる
        val shown = signalLines.takeLast(8)
        shown.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    navController: NavHostController,
    vm: AppViewModel = hiltViewModel()
) {
    var debugOn by remember { mutableStateOf(false) }
    var csvName by remember { mutableStateOf("log.csv") }

    val mode = vm.mode.collectAsState().value
    val labels = vm.detectedLabels.collectAsState().value

    val scope = rememberCoroutineScope()
    val jpegFlow = remember { vm.jpegFlow }  // SharedFlow<ByteArray>
    var uiBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 保存トグル
    var saveImagesOn by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val saveDir = remember {
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "stream").also { it.mkdirs() }
    }
    val saveDirPath = remember { saveDir.absolutePath }
    val sdf = remember { SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US) }

    val appsinkRawRgba = vm.appsinkRawRgba.collectAsState().value

    fun rgbaToBitmap(frame: RgbaFrame): Bitmap {
        val (bytes, w, h) = frame
        val pixels = IntArray(w * h)
        var j = 0
        for (i in 0 until w * h) {
            val r = bytes[j++].toInt() and 0xFF
            val g = bytes[j++].toInt() and 0xFF
            val b = bytes[j++].toInt() and 0xFF
            val a = bytes[j++].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // デバッグ表示: JPEG と RGBA を切替
    LaunchedEffect(debugOn, appsinkRawRgba) {
        uiBitmap = null
        if (!debugOn) return@LaunchedEffect

        if (!appsinkRawRgba) {
            // JPEG
            jpegFlow
                .mapLatest { bytes ->
                    withContext(Dispatchers.Default) {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    }
                }
                .collect { bmp ->
                    if (bmp == null) Timber.w("UI: decode failed (JPEG)")
                    else Timber.i("UI: decoded bitmap %dx%d", bmp.width, bmp.height)
                    uiBitmap = bmp
                }
        } else {
            // RGBA
            vm.rgbaFlow
                .mapLatest { frame -> withContext(Dispatchers.Default) { rgbaToBitmap(frame) } }
                .collect { bmp ->
                    Timber.i("UI: RGBA bitmap %dx%d", bmp.width, bmp.height)
                    uiBitmap = bmp
                }
        }
    }

    // 1秒間隔の保存
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

    // モード変化ログ（簡易）
    val logs = remember { mutableStateListOf<String>() }
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
            // トグル群
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
            item { Text("現在モード: ${mode.name}") }

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

            item { Button(onClick = { Timber.i("TODO: export CSV $csvName") }) { Text("CSV 出力") } }

            item { Divider() }

            // デバッグUI
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

                    val inputSrc = vm.inputSource.collectAsState().value
                    val gstStats = vm.gstStats.collectAsState().value
                    val rtpPort = vm.rtpListenPort.collectAsState().value
                    val rtspUrl = vm.rtspUrl.collectAsState().value

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Input:", modifier = Modifier.padding(end = 8.dp))
                        AssistChip(onClick = { vm.selectInputSource(InputSource.UDP_JPEG) }, label = { Text("UDP/JPEG") })
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = { vm.selectInputSource(InputSource.GST_RTP_JPEG) }, label = { Text("GST RTP/JPEG") })
                        Spacer(Modifier.width(8.dp))
                        AssistChip(onClick = { vm.selectInputSource(InputSource.GST_RTSP_JPEG) }, label = { Text("GST RTSP/JPEG") })
                    }

                    // appsink 出力選択
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Appsink 出力:", modifier = Modifier.padding(end = 8.dp))
                        AssistChip(
                            onClick = { vm.setAppsinkRawRgba(false) },
                            label = { Text("JPEG") },
                            leadingIcon = { if (!appsinkRawRgba) Text("●") }
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = { vm.setAppsinkRawRgba(true) },
                            label = { Text("RGBA") },
                            leadingIcon = { if (appsinkRawRgba) Text("●") }
                        )
                    }
                    Text(
                        if (appsinkRawRgba) "appsink: video/x-raw,format=RGBA"
                        else "appsink: image/jpeg",
                        style = MaterialTheme.typography.bodySmall
                    )

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

                    SignalDebugPanel(vm)

                    Divider()
                }
            }

            // ログ（簡易）
            item { Text("ログ", style = MaterialTheme.typography.titleSmall) }
            items(logs) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }

            if (BuildConfig.DEBUG) {
                item { Text("MODE = $mode", Modifier.padding(4.dp)) }
            }
        }
    }
}
