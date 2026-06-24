package com.example.coospohrm

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

data class BleState(
    val isConnected: Boolean = false,
    val statusText: String = "Запрос разрешений...",
    val heartRate: Int = 0,
    val batteryLevel: Int = -1,
    val manufacturer: String = "",
    val model: String = "",
) {
    val deviceName: String
        get() = if (manufacturer.isNotBlank() && model.isNotBlank())
            "$manufacturer $model" else "Coospo H9Z"
}

private sealed class GattOp {
    data class Read(val ch: BluetoothGattCharacteristic) : GattOp()
    data class EnableNotify(val ch: BluetoothGattCharacteristic) : GattOp()
}

class BleHeartRateManager(
    private val appContext: Context,
    private val onHeartRate: (Int) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var lastConnectedDevice: BluetoothDevice? = null

    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight = false

    private val _state = MutableStateFlow(BleState())
    val state: StateFlow<BleState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var isReconnecting = false

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!isReconnecting) return
            connectToPairedDevice()
            handler.postDelayed(this, 5000) // Каждые 5 секунд
        }
    }

    fun getCurrentHR(): Int = _state.value.heartRate

    @SuppressLint("MissingPermission")
    fun connectToPairedDevice() {
        val a = adapter ?: run { update { it.copy(statusText = "Bluetooth недоступен") }; return }
        if (!a.isEnabled) { update { it.copy(statusText = "Включите Bluetooth") }; return }

        // Если уже подключены - не переподключаемся
        if (_state.value.isConnected && gatt != null) return

        update { it.copy(statusText = "Поиск устройства...") }

        // Пробуем сохраненное устройство
        if (lastConnectedDevice != null) {
            val device = lastConnectedDevice!!
            update { it.copy(statusText = "Подключение к ${device.name}...") }
            try {
                gatt?.close()
                gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                return
            } catch (_: SecurityException) {}
        }

        // Ищем в сопряженных
        val device = try {
            a.bondedDevices?.firstOrNull { d ->
                val n = d.name ?: ""
                n.contains("H9Z", true) || n.contains("Coospo", true) || n.contains("Heart", true)
            }
        } catch (_: SecurityException) { null }

        if (device == null) {
            update { it.copy(statusText = "H9Z не найден") }
            return
        }

        lastConnectedDevice = device
        update { it.copy(statusText = "Подключение к ${device.name}...") }
        try {
            gatt?.close()
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            update { it.copy(statusText = "Нет разрешения BLUETOOTH_CONNECT") }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopReconnect()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        opQueue.clear(); opInFlight = false
        update { BleState(statusText = "Отключено") }
    }

    fun startReconnect() {
        if (isReconnecting) return
        isReconnecting = true
        handler.removeCallbacks(reconnectRunnable)
        handler.post(reconnectRunnable)
    }

    fun stopReconnect() {
        isReconnecting = false
        handler.removeCallbacks(reconnectRunnable)
    }

    private fun update(transform: (BleState) -> BleState) { _state.value = transform(_state.value) }

    @SuppressLint("MissingPermission")
    private fun enqueue(op: GattOp) {
        opQueue.add(op); runNext()
    }

    @SuppressLint("MissingPermission")
    private fun runNext() {
        if (opInFlight) return
        val g = gatt ?: return
        val op = opQueue.poll() ?: return
        opInFlight = true
        try {
            when (op) {
                is GattOp.Read -> g.readCharacteristic(op.ch)
                is GattOp.EnableNotify -> {
                    g.setCharacteristicNotification(op.ch, true)
                    val desc = op.ch.getDescriptor(BleUuids.CCCD) ?: run {
                        opInFlight = false; runNext(); return
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(desc)
                    }
                }
            }
        } catch (_: SecurityException) {
            opInFlight = false; runNext()
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == 133) {
                gatt?.close(); gatt = null
                opQueue.clear(); opInFlight = false
                update { it.copy(isConnected = false, statusText = "Ошибка подключения") }
                startReconnect()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    stopReconnect()
                    update { it.copy(isConnected = true, statusText = "Подключено!") }
                    try { g.discoverServices() } catch (_: SecurityException) {}
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    opQueue.clear(); opInFlight = false
                    update { it.copy(isConnected = false, statusText = "Отключено. Переподключение...", heartRate = 0) }
                    startReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            g.getService(BleUuids.HEART_RATE_SERVICE)
                ?.getCharacteristic(BleUuids.HEART_RATE_MEASUREMENT)
                ?.let { enqueue(GattOp.EnableNotify(it)) }
            g.getService(BleUuids.BATTERY_SERVICE)
                ?.getCharacteristic(BleUuids.BATTERY_LEVEL)
                ?.let { enqueue(GattOp.Read(it)); enqueue(GattOp.EnableNotify(it)) }
            g.getService(BleUuids.DEVICE_INFO_SERVICE)?.let { dis ->
                dis.getCharacteristic(BleUuids.MANUFACTURER_NAME)?.let { enqueue(GattOp.Read(it)) }
                dis.getCharacteristic(BleUuids.MODEL_NUMBER)?.let { enqueue(GattOp.Read(it)) }
            }
            update { it.copy(statusText = "Мониторинг...") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opInFlight = false; runNext()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(ch, value); opInFlight = false; runNext()
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleRead(ch, ch.value ?: ByteArray(0))
            }
            opInFlight = false; runNext()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleChanged(ch, value)
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleChanged(ch, ch.value ?: ByteArray(0))
            }
        }
    }

    private fun handleRead(ch: BluetoothGattCharacteristic, v: ByteArray) {
        when (ch.uuid) {
            BleUuids.BATTERY_LEVEL ->
                if (v.isNotEmpty()) update { it.copy(batteryLevel = v[0].toInt() and 0xFF) }
            BleUuids.MANUFACTURER_NAME -> update { it.copy(manufacturer = String(v).trim()) }
            BleUuids.MODEL_NUMBER -> update { it.copy(model = String(v).trim()) }
        }
    }

    private fun handleChanged(ch: BluetoothGattCharacteristic, v: ByteArray) {
        when (ch.uuid) {
            BleUuids.HEART_RATE_MEASUREMENT -> {
                val hr = parseHeartRate(v)
                if (hr > 0) {
                    update { it.copy(heartRate = hr, statusText = "Пульс: $hr BPM") }
                    onHeartRate(hr)
                }
            }
            BleUuids.BATTERY_LEVEL ->
                if (v.isNotEmpty()) update { it.copy(batteryLevel = v[0].toInt() and 0xFF) }
        }
    }

    private fun parseHeartRate(d: ByteArray): Int {
        if (d.size < 2) return 0
        return runCatching {
            val flags = d[0].toInt()
            if ((flags and 0x01) != 0 && d.size >= 3) {
                (d[1].toInt() and 0xFF) or ((d[2].toInt() and 0xFF) shl 8)
            } else d[1].toInt() and 0xFF
        }.getOrDefault(0)
    }
}