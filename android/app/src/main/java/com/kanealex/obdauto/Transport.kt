package com.kanealex.obdauto

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/** A byte pipe to the ELM327. Incoming bytes go to the onData callback given to the constructor. */
interface Transport {
    val isOpen: Boolean
    suspend fun open()
    suspend fun write(data: ByteArray)
    fun close()
}

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Bluetooth Classic serial (SPP/RFCOMM). The adapter must be paired in Android settings first. */
@SuppressLint("MissingPermission")
class ClassicTransport(
    private val device: BluetoothDevice,
    private val onData: (ByteArray) -> Unit,
) : Transport {
    private var socket: BluetoothSocket? = null
    @Volatile override var isOpen = false
        private set

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: IOException) {
            // Some clones only accept an insecure socket.
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        }
        socket = s
        isOpen = true
        Thread({
            val buf = ByteArray(512)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onData(buf.copyOf(n))
                }
            } catch (ignored: IOException) {
            } finally {
                isOpen = false
            }
        }, "obd-classic-reader").apply { isDaemon = true }.start()
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("Not connected")
        s.outputStream.write(data)
        s.outputStream.flush()
    }

    override fun close() {
        isOpen = false
        try { socket?.close() } catch (ignored: IOException) {}
        socket = null
    }
}

// Common BLE services used by ELM327-style dongles (same list as the web dashboard).
private val BLE_SERVICE_CANDIDATES = listOf(
    "0000fff0-0000-1000-8000-00805f9b34fb",
    "0000ffe0-0000-1000-8000-00805f9b34fb",
    "000018f0-0000-1000-8000-00805f9b34fb",
    "e7810a71-73ae-499d-8c15-faa9aef0c3f2",
).map(UUID::fromString)

private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** BLE serial: one notify characteristic for replies and one write characteristic for commands. */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val onData: (ByteArray) -> Unit,
) : Transport {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val connected = CompletableDeferred<Unit>()
    private val servicesFound = CompletableDeferred<Unit>()
    private val descriptorWritten = CompletableDeferred<Unit>()
    @Volatile private var writeDone: CompletableDeferred<Unit>? = null
    @Volatile override var isOpen = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isOpen = false
                val e = IOException("BLE disconnected (status $status)")
                connected.completeExceptionally(e)
                servicesFound.completeExceptionally(e)
                descriptorWritten.completeExceptionally(e)
                writeDone?.completeExceptionally(e)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesFound.complete(Unit)
            else servicesFound.completeExceptionally(IOException("Service discovery failed ($status)"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }
    }

    override suspend fun open() {
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not start BLE connection")
        gatt = g
        withTimeout(15_000) { connected.await() }
        if (!g.discoverServices()) throw IOException("Service discovery did not start")
        withTimeout(15_000) { servicesFound.await() }

        val ordered = g.services.sortedBy { svc ->
            BLE_SERVICE_CANDIDATES.indexOf(svc.uuid).let { if (it < 0) Int.MAX_VALUE else it }
        }
        var notify: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
        for (svc: BluetoothGattService in ordered) {
            val n = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val w = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (n != null && w != null) { notify = n; write = w; break }
        }
        if (notify == null || write == null) throw IOException("No usable serial characteristic on this adapter")
        writeChar = write

        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS) {
                withTimeout(5_000) { descriptorWritten.await() }
            }
        }
        isOpen = true
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // Chunk to 20 bytes, the default BLE payload size, and wait for each write to finish.
        var i = 0
        while (i < data.size) {
            val chunk = data.copyOfRange(i, minOf(i + 20, data.size))
            val done = CompletableDeferred<Unit>()
            writeDone = done
            val rc = g.writeCharacteristic(c, chunk, type)
            if (rc != BluetoothStatusCodes.SUCCESS) throw IOException("BLE write failed ($rc)")
            withTimeout(2_000) { done.await() }
            i += 20
        }
    }

    override fun close() {
        isOpen = false
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
    }
}
