package dev.ring.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One GATT connection. Android allows a single outstanding GATT operation, so
 * every write / subscribe goes through [runOp], which waits for its callback
 * before the next one starts.
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context) {

    data class Notification(val characteristic: UUID, val value: ByteArray)

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connectSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var servicesSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingOp: CompletableDeferred<Boolean>? = null
    private val opLock = Mutex()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 512)
    val notifications: SharedFlow<Notification> = _notifications

    /** Connects, discovers services and raises the MTU. Returns false on any failure. */
    suspend fun connect(address: String, timeoutMs: Long = 15_000): Boolean {
        close()
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false

        val signal = CompletableDeferred<Boolean>()
        connectSignal = signal
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return false
        gatt = g

        if (withTimeoutOrNull(timeoutMs) { signal.await() } != true) {
            close()
            return false
        }
        delay(300) // some phones drop the link if discovery starts immediately

        val discovered = CompletableDeferred<Boolean>()
        servicesSignal = discovered
        if (!g.discoverServices() || withTimeoutOrNull(10_000) { discovered.await() } != true) {
            close()
            return false
        }
        runOp { it.requestMtu(247) }
        return gatt != null
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    /** Turns on notify or indicate (whichever the characteristic supports). */
    suspend fun enableNotifications(service: UUID, characteristic: UUID): Boolean = runOp { g ->
        val c = g.getService(service)?.getCharacteristic(characteristic) ?: return@runOp false
        if (!g.setCharacteristicNotification(c, true)) return@runOp false
        val d = c.getDescriptor(CCCD) ?: return@runOp false
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        writeDescriptorCompat(g, d, value)
    }

    /** Writes without response when the characteristic allows it (what the ring expects). */
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray): Boolean = runOp { g ->
        val c = g.getService(service)?.getCharacteristic(characteristic) ?: return@runOp false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeCharacteristicCompat(g, c, value, type)
    }

    /** Drops the connection on purpose. No reconnect follows from here. */
    fun close() {
        val g = gatt
        gatt = null
        _connected.value = false
        connectSignal?.complete(false)
        servicesSignal?.complete(false)
        pendingOp?.complete(false)
        if (g != null) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun runOp(timeoutMs: Long = 4_000, start: (BluetoothGatt) -> Boolean): Boolean =
        opLock.withLock {
            val g = gatt ?: return@withLock false
            val done = CompletableDeferred<Boolean>()
            pendingOp = done
            val started = try { start(g) } catch (e: Exception) {
                Log.w(TAG, "GATT op failed to start", e)
                false
            }
            val ok = started && withTimeoutOrNull(timeoutMs) { done.await() } == true
            pendingOp = null
            ok
        }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int,
    ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
    } else {
        c.writeType = type
        @Suppress("DEPRECATION")
        c.value = value
        @Suppress("DEPRECATION")
        g.writeCharacteristic(c)
    }

    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectSignal?.complete(true)
                return
            }
            Log.i(TAG, "link down status=$status state=$newState")
            connectSignal?.complete(false)
            servicesSignal?.complete(false)
            pendingOp?.complete(false)
            if (gatt === g) gatt = null
            try { g.close() } catch (_: Exception) {}
            _connected.value = false
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendingOp?.complete(true)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.uuid, value.copyOf()))
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            _notifications.tryEmit(Notification(c.uuid, v.copyOf()))
        }
    }

    companion object {
        private const val TAG = "BleLink"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
