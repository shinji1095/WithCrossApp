package com.example.withcrossdemo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.withcrossdemo.data.ble.BleDevice
import com.example.withcrossdemo.data.ble.BleManager
import com.example.withcrossdemo.data.local.datastore.DeviceSetting
import com.example.withcrossdemo.data.local.datastore.SettingRepository
import com.example.withcrossdemo.network.WsServerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/* ---------- 画面状態 ---------- */
data class BleUiState(
    val devices:   List<BleDevice> = emptyList(),
    val scanning:  Boolean        = false,
    val connected: Boolean        = false,
    val sending:   Boolean        = false,
    val sendDone:  Boolean        = false
)

/* ---------- Wi-Fi / WS の保存用 ---------- */
class DeviceSettingVm @Inject constructor(
    private val repo: SettingRepository
) : ViewModel() {

    val ui: StateFlow<DeviceSetting> =
        repo.settingFlow.stateIn(viewModelScope, SharingStarted.Lazily, DeviceSetting())

    fun onSave(new: DeviceSetting) = viewModelScope.launch { repo.save(new) }
}

/* ---------- BLE セットアップ ---------- */
@HiltViewModel
class BleSetupViewModel @Inject constructor(
    private val ble:  BleManager,
    private val repo: SettingRepository
) : ViewModel() {

    /* Wi-Fi など保存値 */
    private val setting: StateFlow<DeviceSetting> =
        repo.settingFlow.stateIn(viewModelScope, SharingStarted.Eagerly, DeviceSetting())

    /* 画面状態 */
    private val _ui = MutableStateFlow(BleUiState())
    val ui: StateFlow<BleUiState> = _ui.asStateFlow()

    init {
        // スキャン結果
        ble.scanResults
            .onEach { list -> _ui.update { it.copy(devices = list) } }
            .launchIn(viewModelScope)

        // 接続状態
        ble.connected
            .onEach { con -> _ui.update { it.copy(connected = con) } }
            .launchIn(viewModelScope)
    }

    /* ---------- UI から呼ばれる関数 ---------- */
    fun startScan() {
        ble.startScan()
        _ui.update { it.copy(scanning = true) }
    }
    fun stopScan() {
        ble.stopScan()
        _ui.update { it.copy(scanning = false) }
    }

    fun connect(device: BleDevice) = viewModelScope.launch {
        val ok = ble.connect(device.device)
        if (ok) _ui.update { it.copy(connected = true) }
    }

    fun sendSettings() = viewModelScope.launch {
        val s      = setting.value
        val text   = "${s.ssid}|${s.psk}|${s.ip}|${s.port}"
        val bytes  = text.toByteArray(Charsets.UTF_8)

        Timber.tag("BleSetupVM").i("sendSettings text=%s", text)

        _ui.update { it.copy(sending = true) }

        val ok = ble.writeSettings(bytes)
        Timber.i("writeSettings returned = $ok")

        _ui.update { it.copy(sending = false, sendDone = ok) }

        if (ok) ble.disconnect()
    }

    /** 画面遷移後に呼ぶと sendDone をクリア */
    fun clearSendDone() {
        _ui.update { it.copy(sendDone = false) }
    }

    /* Setting 編集用 */
    fun saveSetting(new: DeviceSetting) = viewModelScope.launch { repo.save(new) }
}
