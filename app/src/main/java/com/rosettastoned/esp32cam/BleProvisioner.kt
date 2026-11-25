package com.rosettastoned.esp32cam

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class BleProvisioner(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var payload: ByteArray? = null
    private var writeOffset = 0
    private var chunkSize = 20
    private var characteristic: BluetoothGattCharacteristic? = null

    var onResult: ((Result<String>) -> Unit)? = null

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        private val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleProvisioner"
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun provision(payloadText: String) {
        if (!hasPermissions()) {
            fail("Bluetooth 권한이 필요합니다")
            return
        }
        payload = payloadText.toByteArray(Charsets.UTF_8)
        writeOffset = 0

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("Bluetooth가 꺼져 있습니다")
            return
        }

        disconnect()
        scanning = true
        val scanner = adapter.bluetoothLeScanner
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val scanSettings = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(filters, scanSettings, scanCallback)

        handler.postDelayed({
            if (scanning) {
                stopScan(adapter)
                fail("ESP32를 찾지 못했습니다. 기기의 LED가 프로비저닝 패턴인지 확인하세요")
            }
        }, 15000)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        characteristic = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            stopScan(adapter)
            gatt = result.device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(adapter: android.bluetooth.BluetoothAdapter) {
        if (scanning) {
            scanning = false
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("서비스 검색 실패")
                return
            }
            val char = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (char == null) {
                fail("ESP32의 프로비저닝 서비스를 찾지 못했습니다")
                return
            }
            characteristic = char
            g.requestMtu(247)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            chunkSize = (mtu - 3).coerceIn(20, 512)
            writeNextChunk()
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("전송 실패 (status=$status)")
                return
            }
            writeNextChunk()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeNextChunk() {
        val data = payload ?: return
        val char = characteristic ?: return
        val g = gatt ?: return
        if (writeOffset >= data.size) {
            succeed()
            return
        }
        val end = (writeOffset + chunkSize).coerceAtMost(data.size)
        val chunk = data.copyOfRange(writeOffset, end)
        Log.d(TAG, "write chunk ${chunk.size} bytes at $writeOffset")
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = chunk
        writeOffset = end
        if (!g.writeCharacteristic(char)) {
            fail("특성 쓰기 실패")
        }
    }

    private fun succeed() {
        val g = gatt
        if (g != null) {
            gatt = null
            characteristic = null
            g.disconnect()
            g.close()
        }
        handler.post {
            onResult?.invoke(Result.success("ESP32에 WiFi 설정을 전송했습니다"))
        }
    }

    private fun fail(message: String) {
        disconnect()
        handler.post {
            onResult?.invoke(Result.failure(Exception(message)))
        }
    }
}
