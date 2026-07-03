package com.example.bodycam

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

class BleManager(
    private val context: Context
) {

    // Gives access to the phone's Bluetooth service
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // Bluetooth adapter (turn Bluetooth on/off, get scanner, etc.)
    private val bluetoothAdapter =
        bluetoothManager.adapter

    // BLE scanner
    private val scanner: BluetoothLeScanner =
        bluetoothAdapter.bluetoothLeScanner

    private var temperatureCharacteristic: BluetoothGattCharacteristic? = null

    private var bluetoothGatt: BluetoothGatt? = null

    var latestTemperature: Float? = null
        private set

    var onTemperatureReceived: ((Float) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {

            val device = result.device

            Log.d("BLE", "Found: ${device.name} (${device.address})")

            if (device.name == "ESP32 DHT Sensor") {

                Log.d("BLE", "ESP32 found!")

                scanner.stopScan(this)

                connectToDevice(device)
            }
        }
    }

    fun connect() {

        Log.d("BLE", "connect() was called")

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BLE", "Bluetooth Scan permission not granted")
            return
        }

        Log.d("BLE", "Starting scan...")

        scanner.startScan(scanCallback)
    }

    private fun connectToDevice(device: BluetoothDevice) {

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("BLE", "Bluetooth Connect permission not granted")
            return
        }

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {

            if (newState == BluetoothProfile.STATE_CONNECTED) {

                Log.d("BLE", "Connected!")

                gatt.discoverServices()

            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                Log.d("BLE", "Disconnected")

            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {

            Log.d("BLE", "Services discovered!")

            val service = gatt.getService(
                UUID.fromString("12345678-1234-1234-1234-1234567890ab")
            )

            if (service == null) {
                Log.e("BLE", "Service not found!")
                return
            }

            Log.d("BLE", "Service found!")

            temperatureCharacteristic = service.getCharacteristic(
                UUID.fromString("abcdef01-1234-1234-1234-1234567890ab")
            )

            if (temperatureCharacteristic == null) {
                Log.e("BLE", "Temperature characteristic not found!")
                return
            }

            Log.d("BLE", "Characteristic found!")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BLE", "Failed to read characteristic")
                return
            }

            val temp = value.decodeToString().toFloatOrNull()

            if (temp == null) {
                Log.e("BLE", "Invalid temperature received")
                return
            }

            latestTemperature = temp

            onTemperatureReceived?.invoke(temp)
        }
    }

    fun readTemperature() {

        val gatt = bluetoothGatt ?: return
        val characteristic = temperatureCharacteristic ?: return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        gatt.readCharacteristic(characteristic)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}