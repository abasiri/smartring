package dev.ring.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ScanHit(val address: String, val name: String?, val rssi: Int, val looksLikeRing: Boolean)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    /** Emits every advertisement seen until the collector stops. */
    fun scan(): Flow<ScanHit> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close()
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: try { result.device.name } catch (_: SecurityException) { null }
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                trySend(ScanHit(result.device.address, name, result.rssi, looksLikeRing(name, uuids)))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { try { scanner.stopScan(cb) } catch (_: Exception) {} }
    }

    companion object {
        private val NAME_HINTS = listOf("RING", "RC", "R0", "SR0", "HEALTH", "SMART", "YCBT", "COLMI")
        private val RING_SERVICES = setOf(
            ParcelUuid.fromString("0000fee7-0000-1000-8000-00805f9b34fb"),
            ParcelUuid.fromString("0000ae00-0000-1000-8000-00805f9b34fb"),
            ParcelUuid.fromString("be940000-7333-be46-b7ae-689e71722bd5"),
        )

        fun looksLikeRing(name: String?, uuids: List<ParcelUuid>): Boolean {
            if (uuids.any { it in RING_SERVICES }) return true
            val n = name?.uppercase() ?: return false
            // Many of these rings advertise as R<digits>…, e.g. "R11M 801B".
            return Regex("^R\\d").containsMatchIn(n) || NAME_HINTS.any { n.startsWith(it) } || n.contains("RING")
        }
    }
}
