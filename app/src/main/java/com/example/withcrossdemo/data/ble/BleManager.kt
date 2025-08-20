package com.example.withcrossdemo.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.bluetooth.BluetoothStatusCodes
import androidx.core.app.ActivityCompat
import com.example.withcrossdemo.data.ble.BleDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val ctx: Context
) {

    companion object {
        private const val TAG = "BleManager"
        private const val DESIRED_MTU = 128
    }

    /* ---------- Scan ---------- */
    private val writeResult = Channel<Boolean>(capacity = Channel.CONFLATED)

    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults

    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startScan(serviceUuid: UUID? = null) {
        Timber.tag(TAG).i("startScan()")
        stopScan()
        val bluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
        scanner = bluetoothManager.adapter.bluetoothLeScanner

        val filters = serviceUuid?.let {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build())
        } ?: emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanJob = callbackFlow {
            val cb = object : ScanCallback() {
                override fun onScanResult(code: Int, res: ScanResult) { trySend(res) }
                override fun onScanFailed(err: Int) { Timber.tag(TAG).e("scan failed $err"); close() }
            }
            Timber.tag(TAG).i("scanner.startScan()")
            scanner?.startScan(filters, settings, cb)
            awaitClose { Timber.tag(TAG).i("stopScan()"); scanner?.stopScan(cb) }
        }
            .onEach { res ->
                val dev = BleDevice(res.device, res.device.name, res.device.address)
//                Timber.tag(TAG).d("Scan found ${dev.address} (${dev.name})")
                _scanResults.update { list ->
                    if (list.any { it.address == dev.address }) list else list + dev
                }
            }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    fun stopScan() { scanJob?.cancel(); scanJob = null; _scanResults.value = emptyList() }

    /* ---------- Connect / Write ---------- */

    private val SERVICE_UUID        = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb") // 実機に合わせる
    private val CHARACTERISTIC_UUID = UUID.fromString("00002A26-0000-1000-8000-00805f9b34fb") // 実機に合わせる

    private var gatt: BluetoothGatt? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Timber.tag(TAG).i("stateChange status=$status new=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.tag(TAG).e("Connection error $status")
                if (ActivityCompat.checkSelfPermission(
                        ctx,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED)
                {
                    Timber.tag(TAG).e("BLUETOOTH_CONNECT permission missing")
                    g.close(); _connected.value = false
                    return
                }
                g.close(); _connected.value = false
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Timber.tag(TAG).i("Discovering services…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.tag(TAG).i("Disconnected")
                g.close(); _connected.value = false
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Timber.tag(TAG).i("servicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (ActivityCompat.checkSelfPermission(
                                ctx, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                            Timber.tag(TAG).e("BLUETOOTH_CONNECT permission missing")
                            return
                        }
                    val ok = g.requestMtu(DESIRED_MTU)   // Boolean
                    Timber.tag(TAG).i("requestMtu($DESIRED_MTU) → $ok")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Timber.tag(TAG).i("onMtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) _connected.value = true
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            Timber.i("charWrite ${c.uuid} status=$status")
            writeResult.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private val _writeResult = MutableSharedFlow<Boolean>()

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        Timber.tag(TAG).i("connect to ${device.address}")
        val success = suspendCancellableCoroutine<Boolean> { cont ->
            gatt = device.connectGatt(ctx, false, gattCallback)
            // Wait for services discovered
            _connected.onEach { if (cont.isActive) cont.resume(it, null) }
                .launchIn(CoroutineScope(Dispatchers.IO))
        }
        success.also { Timber.tag(TAG).i("connect() result=$it") }
    }

    @SuppressLint("MissingPermission")
    suspend fun writeSettings(payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val ch = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            ?: return@withContext false.also { Timber.e("Characteristic not found") }

        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            gatt?.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = payload
            gatt?.writeCharacteristic(ch) == true
        }

        if (!accepted) return@withContext false
        return@withContext writeResult.receive()
    }


    fun disconnect() {
        Timber.tag(TAG).i("disconnect()")
        if (ActivityCompat.checkSelfPermission(
            ctx,
            Manifest.permission.BLUETOOTH_CONNECT
        ) != PackageManager.PERMISSION_GRANTED
    ) {
            Timber.tag(TAG).e("BLUETOOTH_CONNECT permission missing")
            return
    }
        gatt?.disconnect()
    }
}
