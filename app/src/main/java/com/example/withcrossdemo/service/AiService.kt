package com.example.withcrossdemo.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.withcrossdemo.network.WsServerManager
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class AiService : Service() {

    /* 参照だけで OK → 初期化時に WsServerManager が生成され start() 済み */
    @Inject lateinit var ws: WsServerManager

    override fun onCreate() {
        super.onCreate()
        Timber.i("AiService created (WS already started?)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY      // 常駐

    override fun onBind(intent: Intent?): IBinder? = null
}
