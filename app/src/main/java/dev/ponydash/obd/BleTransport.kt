package dev.ponydash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A byte pipe to an ELM327-style adapter. */
interface ByteTransport {
    val incoming: ReceiveChannel<ByteArray>
    suspend fun write(bytes: ByteArray)
    fun close()
}

/**
 * Bluetooth LE serial link to an OBD adapter (Vgate iCar Pro 2S and similar).
 *
 * BLE OBD adapters expose a vendor "serial" service: one characteristic the
 * adapter notifies with its output, one we write commands to. Vendors use
 * different UUIDs (FFF0/FFF1/FFF2, FFE0/FFE1, 18F0/2AF0/2AF1, ...), so known
 * pairs are tried first and any service with a notify + write characteristic
 * is the fallback.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : ByteTransport {

    private val rx = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> get() = rx

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Int>()
    private val opLock = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val err = IOException("Bluetooth link dropped (status $status)")
                connected.completeExceptionally(err)
                discovered.completeExceptionally(err)
                pendingOp?.completeExceptionally(err)
                rx.close(err)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered.complete(status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            rx.trySend(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }
    }

    suspend fun open() {
        log("Connecting to ${device.name ?: device.address} …")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeoutOrNull(15_000) { connected.await() }
            ?: throw IOException("Adapter did not answer the Bluetooth connection")
        delay(300) // some adapters drop service discovery sent right after connect
        val g = gatt ?: throw IOException("GATT gone")
        if (!g.discoverServices()) throw IOException("Service discovery failed to start")
        val status = withTimeoutOrNull(10_000) { discovered.await() }
            ?: throw IOException("Service discovery timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Service discovery status $status")

        val (notify, write) = pickCharacteristics(g)
        log("Serial: notify ${short(notify.uuid)} · write ${short(write.uuid)} (service ${short(notify.service.uuid)})")
        writeChar = write
        writeType =
            if (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD)
        if (cccd != null) {
            val v =
                if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            gattOp { g.writeDescriptor(cccd, v) }
        }
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic> {
        val services = g.services
        for (s in services) {
            log("  service ${short(s.uuid)}: " + s.characteristics.joinToString { "${short(it.uuid)}[${props(it.properties)}]" })
        }
        for ((svc, n, w) in KNOWN) {
            val s = services.firstOrNull { it.uuid == svc } ?: continue
            val nc = s.getCharacteristic(n) ?: continue
            val wc = s.getCharacteristic(w) ?: continue
            return nc to wc
        }
        for (s in services) {
            if (short(s.uuid) in SYSTEM_SERVICES) continue
            val nc = s.characteristics.firstOrNull { it.properties and (NOTIFY or INDICATE) != 0 } ?: continue
            val wc = s.characteristics.firstOrNull { it.properties and (WRITE or WRITE_NR) != 0 } ?: continue
            return nc to wc
        }
        throw IOException("No serial characteristics found — is this an OBD adapter?")
    }

    private suspend fun gattOp(start: () -> Int): Int = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pendingOp = d
        val r = start()
        if (r != BluetoothStatusCodes.SUCCESS) throw IOException("GATT op refused ($r)")
        withTimeoutOrNull(3_000) { d.await() } ?: throw IOException("GATT op timed out")
    }

    override suspend fun write(bytes: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        // ELM commands are short; chunk to the default 20-byte ATT payload anyway.
        for (chunk in bytes.toList().chunked(20)) {
            val arr = chunk.toByteArray()
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                gattOp { g.writeCharacteristic(c, arr, writeType) }
            } else {
                val r = g.writeCharacteristic(c, arr, writeType)
                if (r != BluetoothStatusCodes.SUCCESS) throw IOException("Write refused ($r)")
                delay(10)
            }
        }
    }

    override fun close() {
        rx.close()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    companion object {
        private val CCCD: UUID = uuid16("2902")
        private const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        private const val INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        private const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        private const val WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        private val SYSTEM_SERVICES = setOf("1800", "1801", "180A", "180F")

        private val KNOWN = listOf(
            Triple(uuid16("FFF0"), uuid16("FFF1"), uuid16("FFF2")),
            Triple(uuid16("FFE0"), uuid16("FFE1"), uuid16("FFE1")),
            Triple(uuid16("18F0"), uuid16("2AF0"), uuid16("2AF1")),
        )

        fun uuid16(s: String): UUID = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")

        fun short(u: UUID): String {
            val s = u.toString()
            return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000"))
                s.substring(4, 8).uppercase() else s
        }

        private fun props(p: Int) = buildString {
            if (p and NOTIFY != 0) append("N")
            if (p and INDICATE != 0) append("I")
            if (p and WRITE != 0) append("W")
            if (p and WRITE_NR != 0) append("w")
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
        }
    }
}
