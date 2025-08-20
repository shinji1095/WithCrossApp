package com.example.withcrossdemo.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("device_setting")

data class DeviceSetting(
    val ssid: String = "",
    val psk: String  = "",
    val ip: String   = "",
    val port: String = ""
)

@Singleton
class SettingRepository @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private object Keys {
        val SSID = stringPreferencesKey("ssid")
        val PSK  = stringPreferencesKey("psk")
        val IP   = stringPreferencesKey("ip")
        val PORT = stringPreferencesKey("port")
    }

    val settingFlow: Flow<DeviceSetting> = ctx.dataStore.data
        .map { pref ->
        DeviceSetting(
            ssid = pref[Keys.SSID] ?: "11111",
            psk  = pref[Keys.PSK ] ?: "22",
            ip   = pref[Keys.IP  ] ?: "33",
            port = pref[Keys.PORT] ?: "80"
        )
    }

    suspend fun save(s: DeviceSetting) = ctx.dataStore.edit {
        Timber.i("[DS] save %s", s)
        it[Keys.SSID] = s.ssid
        it[Keys.PSK] = s.psk
        it[Keys.IP] = s.ip
        it[Keys.PORT] = s.port
    }
}
