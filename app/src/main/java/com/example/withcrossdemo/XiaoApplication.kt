package com.example.withcrossdemo

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class XiaoApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Log.d("App", "BuildConfig.DEBUG = ${com.example.withcrossdemo.BuildConfig.DEBUG}")

        if (com.example.withcrossdemo.BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
