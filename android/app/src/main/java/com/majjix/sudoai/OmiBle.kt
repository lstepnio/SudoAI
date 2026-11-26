package com.majjix.sudoai

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class OmiBle(
    private val context: Context,
    private val state: (String) -> Unit,
    private val audio: (ByteArray) -> Unit,
    private val lost: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java).adapter

    private var gatt: BluetoothGatt? = null
    private var decoder: OpusDecoder? = null
    private var codecId = -1
    private val framer = OmiFramer { lost("Omi packet discontinuity") }
    private var scanning = false
    private var scanTimeout: Runnable? = null
    private val service = id("00")
    private val notify = id("01")
    private val codec = id("02")

    private fun id(suffix: String) = UUID.fromString("19b100$suffix-e8f2-537e-4f6c-d104768a1214")

    private val scan =
        object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                if (!scanning) return
                scanning = false
                scanTimeout?.let(handler::removeCallbacks)
                adapter.bluetoothLeScanner.stopScan(this)
                state("Connecting ${result.device.name ?: "Omi"}")
                gatt =
                    result.device.connectGatt(
                        context,
                        false,
                        callback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                lost("Omi scan failed: $errorCode")
            }
        }

    fun connect() {
        disconnect(false)
        require(adapter != null && adapter.isEnabled) { "Enable Bluetooth first" }
        scanning = true
        state("Scanning for Omi (20 seconds)")
        adapter.bluetoothLeScanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scan,
        )
        scanTimeout = Runnable {
            if (scanning) {
                adapter.bluetoothLeScanner.stopScan(scan)
                scanning = false
                lost("No Omi found. Tap Connect to retry.")
            }
        }
        handler.postDelayed(scanTimeout!!, 20000)
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (gatt !== g) return
                if (
                    newState == BluetoothProfile.STATE_CONNECTED &&
                        status == BluetoothGatt.GATT_SUCCESS
                ) {
                    state("Negotiating Omi MTU")
                    if (!g.requestMtu(517)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    decoder?.close()
                    decoder = null
                    codecId = -1
                    framer.reset()
                    g.close()
                    if (gatt === g) gatt = null
                    lost("Omi disconnected ($status)")
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (gatt !== g) return
                framer.payloadLimit =
                    if (status == BluetoothGatt.GATT_SUCCESS) (mtu - 6).coerceAtLeast(17) else 17
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (gatt !== g) return
                val c = g.getService(service)?.getCharacteristic(codec)
                if (status != BluetoothGatt.GATT_SUCCESS || c == null || !g.readCharacteristic(c))
                    lost("Omi codec negotiation failed")
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (gatt !== g || characteristic.uuid != codec) return
                if (status != BluetoothGatt.GATT_SUCCESS || value.isEmpty()) {
                    lost("Cannot read Omi codec")
                    return
                }
                codecId = value[0].toInt() and 255
                try {
                    require(codecId == 0 || codecId == 20 || codecId == 21) {
                        "Unsupported Omi codec $codecId; capture stopped"
                    }
                    decoder = if (codecId == 0) null else OpusDecoder(codecId)
                    val c =
                        g.getService(service)?.getCharacteristic(notify)
                            ?: error("No Omi audio characteristic")
                    check(g.setCharacteristicNotification(c, true))
                    val d =
                        c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            ?: error("No notification descriptor")
                    check(
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            BluetoothStatusCodes.SUCCESS
                    )
                } catch (e: Exception) {
                    lost(e.message ?: "Omi initialization failed")
                    disconnect(false)
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (gatt !== g) return
                if (status == BluetoothGatt.GATT_SUCCESS)
                    state(
                        when (codecId) {
                            20 -> "Omi connected: DevKit Opus 16kHz / 10ms"
                            21 -> "Omi connected: CV1 Opus 16kHz / 20ms"
                            else -> "Omi connected: PCM16 / 16kHz"
                        }
                    )
                else lost("Omi notification subscription failed ($status)")
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (gatt !== g || characteristic.uuid != notify || codecId < 0) return
                try {
                    for (payload in framer.offer(value)) audio(
                        if (codecId == 0) payload else decoder?.decode(payload) ?: byteArrayOf()
                    )
                } catch (e: Exception) {
                    lost(e.message ?: "Omi decode error")
                }
            }
        }

    fun disconnect(report: Boolean = true) {
        scanTimeout?.let(handler::removeCallbacks)
        scanTimeout = null
        if (scanning) runCatching { adapter.bluetoothLeScanner.stopScan(scan) }
        scanning = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        decoder?.close()
        decoder = null
        codecId = -1
        framer.payloadLimit = 17
        framer.reset()
        if (report) lost("Omi disconnected by user")
    }
}
