package dev.ring.companion.protocol

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Yucheng YCBT: the command channel for time sync, measurements and workouts.
 * Write on be940001, replies arrive as indications on be940003. No auth needed.
 *
 *   main (1) | sub (1) | total length (LE u16, whole frame) | payload | CRC-16/CCITT-FALSE (LE)
 */
object Ycbt {
    val SERVICE: UUID = UUID.fromString("be940000-7333-be46-b7ae-689e71722bd5")
    val WRITE: UUID = UUID.fromString("be940001-7333-be46-b7ae-689e71722bd5")
    val NOTIFY: UUID = UUID.fromString("be940003-7333-be46-b7ae-689e71722bd5")

    /** Separate status beacon: steps / distance / calories, ~every 2 s. */
    val BEACON_SERVICE: UUID = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")
    val BEACON: UUID = UUID.fromString("0000fea1-0000-1000-8000-00805f9b34fb")

    // Frame types: (main shl 8) or sub
    const val T_TIME = 0x0100
    const val T_DEVICE_INFO = 0x0200
    const val T_DEVICE_NAME = 0x0203
    const val T_REAL_DATA = 0x0309
    const val T_RUN_MODE = 0x030C
    const val T_START_MEASURE = 0x032F
    const val T_MONITOR_INTERVAL = 0x010C
    const val T_LIVE_ACTIVITY = 0x0600
    const val T_LIVE_HEART = 0x0601
    const val T_LIVE_SPO2 = 0x0602
    /** Sent every second while the ring is in workout mode; byte 0 is heart rate (0 = no reading yet). */
    const val T_LIVE_WORKOUT = 0x060D

    object MeasureType {
        const val HEART_RATE = 0
        const val SPO2 = 2
        const val HRV = 10
        const val STRESS = 12
    }

    object SportType {
        const val RUN = 1
        const val RIDE = 3
        const val STRENGTH = 4
        const val WALK = 16
    }

    object RunState {
        const val END = 0
        const val START = 1
        const val PAUSE = 2
        const val RESUME = 3
    }

    // ------------------------------------------------------------ framing

    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        return crc
    }

    fun frame(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = payload.size + 6
        val body = byteArrayOf((type shr 8).toByte(), type.toByte(), total.toByte(), (total shr 8).toByte()) + payload
        val c = crc16(body)
        return body + byteArrayOf(c.toByte(), (c shr 8).toByte())
    }

    data class Frame(val type: Int, val payload: ByteArray, val crcOk: Boolean)

    fun parse(raw: ByteArray): Frame? {
        if (raw.size < 6) return null
        val total = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        if (total != raw.size) return null
        val type = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        val got = (raw[raw.size - 2].toInt() and 0xFF) or ((raw[raw.size - 1].toInt() and 0xFF) shl 8)
        return Frame(type, raw.copyOfRange(4, raw.size - 2), got == crc16(raw, raw.size - 2))
    }

    /** Joins indications that BLE split at the MTU. */
    class Framer {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): List<Frame> {
            buf += chunk
            val out = mutableListOf<Frame>()
            while (buf.size >= 4) {
                val total = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
                if (total < 6 || total > 0x2000) { buf = buf.copyOfRange(1, buf.size); continue }
                if (buf.size < total) break
                parse(buf.copyOfRange(0, total))?.let(out::add)
                buf = buf.copyOfRange(total, buf.size)
            }
            return out
        }

        fun reset() { buf = ByteArray(0) }
    }

    // ------------------------------------------------------------ commands

    fun timeSync(now: LocalDateTime = LocalDateTime.now()): ByteArray = frame(
        T_TIME,
        byteArrayOf(
            now.year.toByte(), (now.year shr 8).toByte(),
            now.monthValue.toByte(), now.dayOfMonth.toByte(),
            now.hour.toByte(), now.minute.toByte(), now.second.toByte(), 0,
        ),
    )

    /** Get requests need these 2-byte markers or the ring ignores them. */
    fun getDeviceInfo() = frame(T_DEVICE_INFO, byteArrayOf(0x47, 0x43))
    fun getDeviceName() = frame(T_DEVICE_NAME, byteArrayOf(0x47, 0x50))

    /**
     * Background readings on/off and how often, in minutes. The maker's app offers
     * multiples of 10; the ring also accepts 15.
     */
    fun monitorInterval(on: Boolean, minutes: Int) =
        frame(T_MONITOR_INTERVAL, byteArrayOf(if (on) 1 else 0, minutes.coerceIn(1, 255).toByte()))

    /** Powers the PPG sensor on/off. The result arrives later as a T_LIVE_* frame. */
    fun startMeasurement(on: Boolean, type: Int) =
        frame(T_START_MEASURE, byteArrayOf(if (on) 1 else 0, type.toByte()))

    /** Asks the ring to report live data; `sport = true` is the workout variant. */
    fun realData(on: Boolean, sport: Boolean) =
        frame(T_REAL_DATA, byteArrayOf(if (on) 1 else 0, if (sport) 1 else 0, 2))

    /** Start / pause / resume / end the ring's own workout mode. */
    fun runMode(state: Int, sportType: Int) =
        frame(T_RUN_MODE, byteArrayOf(state.toByte(), sportType.toByte()))

    // ------------------------------------------------------------ decoders

    data class DeviceInfo(val firmware: String, val batteryPercent: Int, val charging: Boolean)

    fun decodeDeviceInfo(p: ByteArray): DeviceInfo? {
        if (p.size < 8) return null
        val sub = p[2].toInt() and 0xFF
        val main = p[3].toInt() and 0xFF
        return DeviceInfo(
            firmware = "%d.%02d".format(main, sub),
            batteryPercent = (p[5].toInt() and 0xFF).coerceIn(0, 100),
            charging = (p[4].toInt() and 0xFF) == 1,
        )
    }

    fun decodeDeviceName(p: ByteArray): String =
        String(p.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.UTF_8)

    data class Activity(val steps: Int, val distanceM: Int, val calories: Int)

    /** T_LIVE_ACTIVITY payload: steps, distance (m), calories, each LE u16. */
    fun decodeLiveActivity(p: ByteArray): Activity? {
        if (p.size < 6) return null
        return Activity(u16(p, 0), u16(p, 2), u16(p, 4))
    }

    /** fea1 beacon: 0x07, then steps, distance (m), calories as LE u24. */
    fun decodeBeacon(p: ByteArray): Activity? {
        if (p.size < 10 || p[0].toInt() != 0x07) return null
        return Activity(u24(p, 1), u24(p, 4), u24(p, 7))
    }

    // ------------------------------------------------------------ stored history
    //
    // The ring keeps sleep, heart-rate and step records in memory. Reading them:
    //   app  -> 05 0K                     (K = kind)
    //   ring -> 05 0K  records u16, packets u32, bytes u32       (reply channel)
    //   ring -> 05 (0K + 0x0F) ...data...                        (one or more frames)
    //   ring -> 05 80  packets u16, bytes u16, check u16         (reply channel)
    // A kind with nothing stored answers 05 0K with a short all-zero payload and no data.

    object History {
        const val SPORT = 0x02
        const val SLEEP = 0x04
        const val HEART = 0x06
        /** Combined background reading every ~30 min: heart rate, blood oxygen, HRV, breathing. */
        const val COMBINED = 0x09
    }

    const val T_HISTORY_END = 0x0580

    fun historyRequest(kind: Int) = frame(0x0500 or kind)
    fun historyHeaderType(kind: Int) = 0x0500 or kind
    fun historyDataType(kind: Int) = 0x0500 or (kind + 0x0F)

    data class HistoryHeader(val records: Int, val bytes: Int)

    fun decodeHistoryHeader(p: ByteArray): HistoryHeader =
        if (p.size >= 10) HistoryHeader(u16(p, 0), u32(p, 6).toInt()) else HistoryHeader(0, 0)

    /** Ring times are local wall-clock seconds since 2000-01-01. */
    fun ringTimeToMs(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(2000, 1, 1, 0, 0).plusSeconds(seconds).atZone(zone).toInstant().toEpochMilli()

    object Stage {
        const val DEEP = 0xF1
        const val LIGHT = 0xF2
        const val REM = 0xF3
        const val AWAKE = 0xF4
    }

    data class SleepStage(val stage: Int, val startMs: Long, val seconds: Int) {
        val endMs: Long get() = startMs + seconds * 1000L
    }

    data class SleepRecord(val startMs: Long, val endMs: Long, val stages: List<SleepStage>)

    /**
     * Sleep history. Each record:
     *   0  2   marker
     *   2  2   record length (header + stages)
     *   4  4   start, 8 4 end
     *   12 8   totals (the app recomputes these from the stages)
     *   20 ... stages, 8 bytes each: stage u8, start u32, seconds u24
     */
    fun decodeSleepHistory(data: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<SleepRecord> {
        val out = mutableListOf<SleepRecord>()
        var o = 0
        while (o + 20 <= data.size) {
            val len = u16(data, o + 2)
            if (len < 20 || o + len > data.size) break
            val start = ringTimeToMs(u32(data, o + 4), zone)
            val end = ringTimeToMs(u32(data, o + 8), zone)
            val stages = mutableListOf<SleepStage>()
            var s = o + 20
            while (s + 8 <= o + len) {
                val stage = data[s].toInt() and 0xFF
                val st = ringTimeToMs(u32(data, s + 1), zone)
                val secs = u24(data, s + 5)
                if (stages.none { it.startMs == st }) stages += SleepStage(stage, st, secs)
                s += 8
            }
            out += SleepRecord(start, end, stages.sortedBy { it.startMs })
            o += len
        }
        return out
    }

    /** Heart-rate history: 6 bytes each — time u32, mode u8, bpm u8. */
    fun decodeHeartHistory(data: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, Int>> =
        (0..data.size - 6 step 6).map { o ->
            ringTimeToMs(u32(data, o), zone) to (data[o + 5].toInt() and 0xFF)
        }.filter { it.second in 30..230 }

    data class CombinedReading(
        val timeMs: Long,
        val steps: Int,
        val heartRate: Int,
        val spo2: Int,
        val hrvMs: Int,
        val breathsPerMin: Int,
    )

    /**
     * Combined history, 20 bytes each: time u32, steps u16, heart rate, blood-pressure estimate
     * (2 bytes), blood oxygen, breathing rate, HRV, then fields this ring leaves unused.
     */
    fun decodeCombinedHistory(data: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<CombinedReading> =
        (0..data.size - 20 step 20).map { o ->
            CombinedReading(
                timeMs = ringTimeToMs(u32(data, o), zone),
                steps = u16(data, o + 4),
                heartRate = data[o + 6].toInt() and 0xFF,
                spo2 = data[o + 9].toInt() and 0xFF,
                breathsPerMin = data[o + 10].toInt() and 0xFF,
                hrvMs = data[o + 11].toInt() and 0xFF,
            )
        }

    private fun u16(p: ByteArray, o: Int) = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
    private fun u24(p: ByteArray, o: Int) = u16(p, o) or ((p[o + 2].toInt() and 0xFF) shl 16)
    private fun u32(p: ByteArray, o: Int) = u24(p, o).toLong() or ((p[o + 3].toLong() and 0xFF) shl 24)
}

/** Standard Bluetooth Heart Rate service. */
object StandardHr {
    val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    data class Reading(val bpm: Int, val skinContact: Boolean)

    fun decode(p: ByteArray): Reading? {
        if (p.size < 2) return null
        val flags = p[0].toInt() and 0xFF
        val bpm = if (flags and 0x01 != 0) {
            if (p.size < 3) return null
            (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
        } else p[1].toInt() and 0xFF
        val contactSupported = flags and 0x04 != 0
        val contact = flags and 0x02 != 0
        return Reading(bpm, !contactSupported || contact)
    }
}
