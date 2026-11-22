package dev.ring.companion.ring

import android.content.Context
import android.util.Log
import dev.ring.companion.ble.BleLink
import dev.ring.companion.data.Prefs
import dev.ring.companion.protocol.Rcsp
import dev.ring.companion.protocol.RcspAuth
import dev.ring.companion.protocol.StandardHr
import dev.ring.companion.protocol.Ycbt
import dev.ring.companion.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything the app knows about the ring, plus the commands it can send.
 * Owns the connection loop: connect, set up, wait for a drop, reconnect.
 */
class RingSession(
    context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
) {
    enum class Status { Disconnected, Connecting, Connected, Reconnecting }

    data class DeviceState(
        val address: String,
        val name: String?,
        val firmware: String? = null,
        val battery: Int? = null,
        val charging: Boolean = false,
        val authenticated: Boolean = false,
        val lastSyncMs: Long? = null,
    )

    enum class HrSource { Measured, Live }
    data class HrSample(val bpm: Int, val source: HrSource, val timeMs: Long)
    data class ActivitySnapshot(val steps: Int, val distanceM: Int, val calories: Int, val timeMs: Long)

    private val link = BleLink(context)

    private val _status = MutableStateFlow(Status.Disconnected)
    val status: StateFlow<Status> = _status

    /** How many reconnect attempts have failed in a row. */
    private val _attempt = MutableStateFlow(0)
    val attempt: StateFlow<Int> = _attempt

    private val _device = MutableStateFlow<DeviceState?>(null)
    val device: StateFlow<DeviceState?> = _device

    private val _activity = MutableStateFlow(loadActivity())

    /** Every frame to or from the ring, for the Raw tab. */
    data class RawFrame(val timeMs: Long, val outgoing: Boolean, val channel: String, val bytes: ByteArray)
    private val _raw = MutableSharedFlow<RawFrame>(extraBufferCapacity = 512)
    val raw: SharedFlow<RawFrame> = _raw

    private val _live = MutableStateFlow(false)
    /** Raw-tab live mode: heart-rate sensor held on so the ring streams continuously. */
    val live: StateFlow<Boolean> = _live
    val activity: StateFlow<ActivitySnapshot?> = _activity

    private val _hr = MutableSharedFlow<HrSample>(extraBufferCapacity = 64)
    /** Fresh heart-rate readings only (never the ring's cached value). */
    val heartRate: SharedFlow<HrSample> = _hr

    private val _liveHr = MutableStateFlow<HrSample?>(null)
    val latestHr: StateFlow<HrSample?> = _liveHr

    private val _spo2 = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val spo2: SharedFlow<Int> = _spo2

    private val _connectedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /** Fires after every successful connect or reconnect. */
    val connectedEvents: SharedFlow<Unit> = _connectedEvents

    private var loopJob: Job? = null
    private val ycbtFramer = Ycbt.Framer()      // live data on be940003
    private val ycbtReplyFramer = Ycbt.Framer() // command replies on be940001
    private val rcspFramer = Rcsp.Framer()
    @Volatile private var auth: RcspAuth? = null
    private val rcspSn = AtomicInteger(1)
    private val rcspPending = ConcurrentHashMap<Int, CompletableDeferred<Rcsp.Packet>>()
    private val ycbtPending = ConcurrentHashMap<Int, CompletableDeferred<Ycbt.Frame>>()

    /** >0 while a measurement or workout keeps the PPG sensor on. */
    private val sensorUsers = AtomicInteger(0)
    /** The ring's standard HR value repeats its last reading; remember it to spot fresh ones. */
    @Volatile private var staleStandardBpm: Int? = null
    @Volatile private var lastStandardBpm: Int? = null

    init {
        scope.launch { link.notifications.collect { handle(it) } }
    }

    // ------------------------------------------------------------ connection

    fun connect(address: String) {
        loopJob?.cancel()
        _device.value = DeviceState(address, prefs.ringName.value)
        loopJob = scope.launch { connectionLoop(address) }
    }

    fun disconnect() {
        loopJob?.cancel()
        loopJob = null
        link.close()
        _status.value = Status.Disconnected
        _attempt.value = 0
    }

    /** Skips the back-off wait and tries again now. */
    fun reconnectNow() {
        val address = _device.value?.address ?: prefs.ringAddress.value ?: return
        connect(address)
    }

    private suspend fun connectionLoop(address: String) {
        var everConnected = false
        var failures = 0
        while (scope.isActive) {
            _status.value = if (everConnected) Status.Reconnecting else Status.Connecting
            _attempt.value = failures
            val ok = link.connect(address) && setup(address)
            if (!ok) {
                link.close()
                failures++
                delay(if (failures < 3) 2_000L else 5_000L)
                continue
            }
            everConnected = true
            failures = 0
            _attempt.value = 0
            _status.value = Status.Connected
            _connectedEvents.tryEmit(Unit)

            coroutineScope {
                val poll = launch {
                    var minute = 0
                    while (true) {
                        delay(60_000L)
                        minute++
                        if (sensorUsers.get() == 0) sendYcbt(Ycbt.realData(on = true, sport = false))
                        if (minute % 10 == 0) refreshDeviceInfo()
                    }
                }
                link.connected.first { !it }
                poll.cancel()
            }
            Log.i(TAG, "ring dropped")
            resetProtocolState()
            if (!prefs.autoReconnect.value) {
                _status.value = Status.Disconnected
                return
            }
            failures = 1
        }
    }

    private suspend fun setup(address: String): Boolean {
        resetProtocolState()
        val y = link.enableNotifications(Ycbt.SERVICE, Ycbt.NOTIFY)
        // Replies to commands come back as indications on the write characteristic itself.
        val yr = link.enableNotifications(Ycbt.SERVICE, Ycbt.WRITE)
        val r = link.enableNotifications(Rcsp.SERVICE, Rcsp.NOTIFY)
        val b = link.enableNotifications(Ycbt.BEACON_SERVICE, Ycbt.BEACON)
        val h = link.enableNotifications(StandardHr.SERVICE, StandardHr.MEASUREMENT)
        Log.i(TAG, "subscribed ycbt=$y replies=$yr rcsp=$r beacon=$b hr=$h")
        if (!y) return false

        val authed = authenticate()
        Log.i(TAG, "rcsp sign-in ${if (authed) "ok" else "FAILED (continuing with YCBT only)"}")

        sendYcbt(Ycbt.timeSync())
        // Re-send the reading interval on every connect; other apps may have changed it.
        applyMeasureInterval()
        val name = sendYcbt(Ycbt.getDeviceName(), awaitType = Ycbt.T_DEVICE_NAME)
            ?.let { Ycbt.decodeDeviceName(it.payload) }?.takeIf { it.isNotBlank() }
        if (name != null) prefs.updateRingName(name)
        _device.value = (_device.value ?: DeviceState(address, name)).copy(
            name = name ?: _device.value?.name,
            authenticated = authed,
            lastSyncMs = System.currentTimeMillis(),
        )
        refreshDeviceInfo()
        // Ask for today's steps / distance / calories. The ring answers with a 0x0600 frame
        // and starts filling its status beacon.
        sendYcbt(Ycbt.realData(on = true, sport = false))

        // A workout or measurement was running when the ring dropped: switch the sensor back on.
        if (sensorUsers.get() > 0) sendYcbt(Ycbt.startMeasurement(true, Ycbt.MeasureType.HEART_RATE))
        return link.connected.value
    }

    private suspend fun authenticate(): Boolean {
        if (!link.hasCharacteristic(Rcsp.SERVICE, Rcsp.WRITE)) return false
        val a = RcspAuth(prefs.linkKey)
        auth = a
        link.write(Rcsp.SERVICE, Rcsp.WRITE, RcspAuth.RESET_FRAME)
        delay(60)
        link.write(Rcsp.SERVICE, Rcsp.WRITE, a.firstMessage)
        val done = withTimeoutOrNull(6_000) {
            while (!a.isDone && !a.isRejected) delay(40)
            a.isDone
        } ?: false
        if (done) return true
        // Some rings keep an earlier session open and answer commands without a new
        // handshake. Check with a harmless request.
        auth = null
        val probe = sendRcsp(Rcsp.OP_GET_TARGET_INFO, byteArrayOf(-1, -1, -1, -1, 0), timeoutMs = 2_000)
        if (probe != null) Log.i(TAG, "rcsp already open; commands accepted without sign-in")
        return probe != null
    }

    private fun resetProtocolState() {
        ycbtFramer.reset()
        ycbtReplyFramer.reset()
        auth = null
        rcspPending.values.forEach { it.cancel() }
        rcspPending.clear()
        ycbtPending.values.forEach { it.cancel() }
        ycbtPending.clear()
    }

    // ------------------------------------------------------------ commands

    suspend fun refreshDeviceInfo() {
        sendYcbt(Ycbt.getDeviceInfo(), awaitType = Ycbt.T_DEVICE_INFO)
    }

    /** Sends the saved background-reading interval to the ring. True if the ring accepted it. */
    suspend fun applyMeasureInterval(): Boolean {
        if (_status.value != Status.Connected && _status.value != Status.Connecting) return false
        val f = sendYcbt(Ycbt.monitorInterval(true, prefs.measureInterval.value), awaitType = Ycbt.T_MONITOR_INTERVAL)
        val ok = f != null && (f.payload.firstOrNull()?.toInt() ?: 1) == 0
        Log.i(TAG, "reading interval ${prefs.measureInterval.value} min: ${if (ok) "accepted" else "not confirmed"}")
        return ok
    }

    suspend fun syncTime(): Boolean {
        val ok = sendYcbt(Ycbt.timeSync(), awaitType = Ycbt.T_TIME) != null
        if (ok) _device.value = _device.value?.copy(lastSyncMs = System.currentTimeMillis())
        return ok
    }

    /** Vibrates / blinks the ring. Needs a signed-in RCSP session. */
    suspend fun findRing(seconds: Int = 5): Boolean {
        if (_device.value?.authenticated != true) return false
        return sendRcsp(Rcsp.OP_SEARCH_DEVICE, Rcsp.searchDevicePayload(seconds)) != null
    }

    /**
     * Runs one heart-rate or SpO₂ measurement. Turns the sensor on, waits for the
     * ring's result, turns it off. Returns null on timeout or when disconnected.
     */
    suspend fun measure(type: Int, timeoutMs: Long): Int? {
        if (_status.value != Status.Connected) return null
        val isHr = type == Ycbt.MeasureType.HEART_RATE
        var lastLive: Int? = null
        return coroutineScope {
            val liveWatcher = launch {
                _hr.filter { it.source == HrSource.Live }.collect { lastLive = it.bpm }
            }
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) {
                    if (isHr) _hr.first { it.source == HrSource.Measured }.bpm else _spo2.first()
                }
            }
            beginSensor()
            try {
                sendYcbt(Ycbt.startMeasurement(true, type))
                result.await() ?: if (isHr) lastLive else null
            } finally {
                // Runs even when the user leaves the screen, so the sensor light goes off.
                withContext(NonCancellable) {
                    sendYcbt(Ycbt.startMeasurement(false, type))
                    endSensor()
                }
                liveWatcher.cancel()
            }
        }
    }

    suspend fun startWorkoutMode(sportType: Int) {
        beginSensor()
        resendWorkoutMode(sportType)
    }

    /** Puts the ring back into workout mode, e.g. after a reconnect. */
    suspend fun resendWorkoutMode(sportType: Int) {
        sendYcbt(Ycbt.runMode(Ycbt.RunState.START, sportType))
        sendYcbt(Ycbt.realData(on = true, sport = true))
        sendYcbt(Ycbt.startMeasurement(true, Ycbt.MeasureType.HEART_RATE))
    }

    suspend fun pauseWorkoutMode(sportType: Int) {
        sendYcbt(Ycbt.runMode(Ycbt.RunState.PAUSE, sportType))
    }

    suspend fun resumeWorkoutMode(sportType: Int) {
        sendYcbt(Ycbt.runMode(Ycbt.RunState.RESUME, sportType))
        sendYcbt(Ycbt.startMeasurement(true, Ycbt.MeasureType.HEART_RATE))
    }

    suspend fun stopWorkoutMode(sportType: Int) {
        sendYcbt(Ycbt.runMode(Ycbt.RunState.END, sportType))
        sendYcbt(Ycbt.realData(on = false, sport = true))
        sendYcbt(Ycbt.startMeasurement(false, Ycbt.MeasureType.HEART_RATE))
        endSensor()
    }

    /**
     * Asks the ring for its step total right now. Returns null if the ring is out of
     * reach or doesn't answer in time. The value has passed the same checks as
     * [activity] (a drop to zero right after connecting is ignored).
     */
    suspend fun readSteps(sport: Boolean): Int? {
        if (_status.value != Status.Connected) return null
        val asked = System.currentTimeMillis()
        // Outside workout mode the ring answers this with an activity frame. In workout mode it
        // declines, but its status beacon still reports the total every ~2 s; take whichever comes first.
        if (!sport) sendYcbt(Ycbt.realData(on = true, sport = false))
        return withTimeoutOrNull(4_000) {
            _activity.first { it != null && it.timeMs >= asked }?.steps
        }
    }

    /** Asks for a fresh heart-rate sample when the stream goes quiet. */
    /**
     * Raw-tab live mode. On: sensor on + live data reporting, so heart rate streams about once a
     * second. Off: sensor off. History sync is held off while this is on (it would stop the stream).
     */
    suspend fun setLive(on: Boolean) {
        if (on == _live.value) return
        if (on) {
            if (_status.value != Status.Connected) return
            beginSensor()
            _live.value = true
            sendYcbt(Ycbt.realData(on = true, sport = false))
            sendYcbt(Ycbt.startMeasurement(true, Ycbt.MeasureType.HEART_RATE))
        } else {
            _live.value = false
            sendYcbt(Ycbt.startMeasurement(false, Ycbt.MeasureType.HEART_RATE))
            endSensor()
        }
    }

    suspend fun nudgeHeartRate() {
        sendYcbt(Ycbt.startMeasurement(true, Ycbt.MeasureType.HEART_RATE))
    }

    private fun beginSensor() {
        if (sensorUsers.getAndIncrement() == 0) staleStandardBpm = lastStandardBpm
    }

    private fun endSensor() {
        if (sensorUsers.decrementAndGet() < 0) sensorUsers.set(0)
    }

    // ------------------------------------------------------------ transport

    /** Debug builds only: send a raw YCBT command (type 0xMMSS) and log what comes back. */
    fun debugSend(type: Int, payload: ByteArray) {
        scope.launch { sendYcbt(Ycbt.frame(type, payload)) }
    }

    private suspend fun sendYcbt(frame: ByteArray, awaitType: Int? = null, timeoutMs: Long = 3_000): Ycbt.Frame? {
        if (!link.connected.value) return null
        val waiter = awaitType?.let { CompletableDeferred<Ycbt.Frame>().also { d -> ycbtPending[it] = d } }
        val wrote = link.write(Ycbt.SERVICE, Ycbt.WRITE, frame)
        _raw.tryEmit(RawFrame(System.currentTimeMillis(), true, "be940001", frame))
        Log.d(PROTO, "-> ycbt ${frame.toHex()} ok=$wrote")
        if (!wrote) {
            awaitType?.let { ycbtPending.remove(it) }
            return null
        }
        if (waiter == null) return null
        return withTimeoutOrNull(timeoutMs) { runCatching { waiter.await() }.getOrNull() }
            .also { ycbtPending.remove(awaitType!!) }
    }

    private suspend fun sendRcsp(op: Int, payload: ByteArray, timeoutMs: Long = 4_000): Rcsp.Packet? {
        if (!link.connected.value) return null
        val sn = (rcspSn.getAndIncrement() and 0xFF).let { if (it == 0) rcspSn.getAndIncrement() and 0xFF else it }
        val waiter = CompletableDeferred<Rcsp.Packet>()
        rcspPending[sn] = waiter
        if (!link.write(Rcsp.SERVICE, Rcsp.WRITE, Rcsp.request(op, sn, payload).encode())) {
            rcspPending.remove(sn)
            return null
        }
        return withTimeoutOrNull(timeoutMs) { runCatching { waiter.await() }.getOrNull() }
            .also { rcspPending.remove(sn) }
    }

    private fun handle(n: BleLink.Notification) {
        _raw.tryEmit(RawFrame(System.currentTimeMillis(), false, n.characteristic.toString().substring(4, 8), n.value))
        // The status beacon repeats every ~2 s; log it only when it changes.
        val quiet = n.characteristic == StandardHr.MEASUREMENT ||
            (n.characteristic == Ycbt.BEACON && n.value.contentEquals(lastBeacon).also { lastBeacon = n.value })
        if (!quiet) Log.d(PROTO, "<- ${n.characteristic.toString().substring(4, 8)} ${n.value.toHex()}")
        when (n.characteristic) {
            Rcsp.NOTIFY -> {
                val a = auth
                if (a != null && !a.isDone) {
                    a.onMessage(n.value)?.let { reply ->
                        scope.launch { link.write(Rcsp.SERVICE, Rcsp.WRITE, reply) }
                    }
                    return
                }
                for (p in rcspFramer.feed(n.value)) rcspPending.remove(p.sn)?.complete(p)
            }
            Ycbt.NOTIFY -> ycbtFramer.feed(n.value).forEach(::onYcbtFrame)
            Ycbt.WRITE -> ycbtReplyFramer.feed(n.value).forEach(::onYcbtFrame)
            Ycbt.BEACON -> Ycbt.decodeBeacon(n.value)?.let(::setActivity)
            StandardHr.MEASUREMENT -> StandardHr.decode(n.value)?.let(::onStandardHr)
        }
    }

    private class HistoryRead(val dataType: Int) {
        val buf = java.io.ByteArrayOutputStream()
        val done = CompletableDeferred<Unit>()
    }
    @Volatile private var historyRead: HistoryRead? = null
    private var lastBeacon: ByteArray? = null

    /** Yesterday's step total, while the ring is still reporting it after midnight. */
    @Volatile private var carriedTotal: Int? = null
    private val historyMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Downloads one kind of stored history ([Ycbt.History]). Returns the joined data bytes,
     * an empty array when the ring has nothing stored, or null if the read failed.
     */
    suspend fun fetchHistory(kind: Int): ByteArray? = historyMutex.withLock {
        if (_status.value != Status.Connected) return null
        val read = HistoryRead(Ycbt.historyDataType(kind))
        historyRead = read
        try {
            val header = sendYcbt(Ycbt.historyRequest(kind), awaitType = Ycbt.historyHeaderType(kind))
                ?: return null
            val h = Ycbt.decodeHistoryHeader(header.payload)
            Log.i(TAG, "history ${"%02x".format(kind)}: ${h.records} records, ${h.bytes} bytes")
            if (h.records == 0 || h.bytes == 0) return ByteArray(0)
            withTimeoutOrNull(30_000) { read.done.await() } ?: return null
            read.buf.toByteArray().let { if (it.size > h.bytes) it.copyOf(h.bytes) else it }
        } finally {
            historyRead = null
        }
    }

    private fun onYcbtFrame(f: Ycbt.Frame) {
        ycbtPending.remove(f.type)?.complete(f)
        historyRead?.let { r ->
            if (f.type == r.dataType) r.buf.write(f.payload)
            if (f.type == Ycbt.T_HISTORY_END) r.done.complete(Unit)
        }
        val now = System.currentTimeMillis()
        when (f.type) {
            Ycbt.T_LIVE_HEART -> f.payload.firstOrNull()?.toInt()?.and(0xFF)
                ?.takeIf { it in 30..230 }
                ?.let { emitHr(HrSample(it, HrSource.Measured, now)) }
            Ycbt.T_LIVE_WORKOUT -> f.payload.firstOrNull()?.toInt()?.and(0xFF)
                ?.takeIf { it in 30..230 }
                ?.let { emitHr(HrSample(it, HrSource.Measured, now)) }
            Ycbt.T_LIVE_SPO2 -> f.payload.firstOrNull()?.toInt()?.and(0xFF)
                ?.takeIf { it in 70..100 }
                ?.let { _spo2.tryEmit(it) }
            Ycbt.T_LIVE_ACTIVITY -> Ycbt.decodeLiveActivity(f.payload)?.let(::setActivity)
            Ycbt.T_DEVICE_INFO -> Ycbt.decodeDeviceInfo(f.payload)?.let { info ->
                if (prefs.lastFirmware != info.firmware) prefs.lastFirmware = info.firmware
                _device.value = _device.value?.copy(
                    firmware = info.firmware, battery = info.batteryPercent, charging = info.charging,
                )
            }
        }
    }

    /**
     * The standard HR characteristic repeats the last stored value every second.
     * Count a value as fresh only while the sensor is on and it differs from the stale one,
     * or when the ring reports skin contact.
     */
    private fun onStandardHr(r: StandardHr.Reading) {
        if (r.bpm !in 30..230) return
        lastStandardBpm = r.bpm
        val sensorOn = sensorUsers.get() > 0
        val fresh = sensorOn && (r.skinContact || r.bpm != staleStandardBpm)
        if (fresh) {
            staleStandardBpm = null
            emitHr(HrSample(r.bpm, HrSource.Live, System.currentTimeMillis()))
        }
    }

    private fun emitHr(s: HrSample) {
        _liveHr.value = s
        _hr.tryEmit(s)
    }

    /**
     * The ring sends the day's totals from two places (status beacon and activity reply).
     * Right after connecting the beacon reads all zeros for a while, so a total that goes
     * down on the same day is ignored. A new day starts over.
     *
     * Just after midnight the ring can keep sending yesterday's total until it resets its own
     * count. That carried-over total is remembered in [carriedTotal], and the drop when the
     * ring does reset is accepted.
     */
    private fun setActivity(a: Ycbt.Activity) {
        val now = System.currentTimeMillis()
        val prev = _activity.value
        val sameDay = prev != null && java.time.LocalDate.now().toEpochDay() ==
            java.time.Instant.ofEpochMilli(prev.timeMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        if (!sameDay) carriedTotal = a.steps.takeIf { it > 0 }
        if (sameDay && a.steps < prev!!.steps) {
            if (prev.steps != carriedTotal) return
            carriedTotal = null // the ring has reset for the new day
        }
        _activity.value = ActivitySnapshot(a.steps, a.distanceM, a.calories, now)
        if (prev == null || prev.steps != a.steps || prev.calories != a.calories || prev.distanceM != a.distanceM) {
            prefs.lastActivity = "${a.steps},${a.distanceM},${a.calories},$now"
        }
    }

    /** Today's last saved totals, or null if they are from an earlier day. */
    private fun loadActivity(): ActivitySnapshot? = prefs.lastActivity?.split(',')?.takeIf { it.size == 4 }?.let { f ->
        val snap = runCatching { ActivitySnapshot(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toLong()) }.getOrNull()
        snap?.takeIf {
            java.time.Instant.ofEpochMilli(it.timeMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now()
        }
    }

    companion object {
        private const val TAG = "RingSession"
        private const val PROTO = "RingProto"
    }
}
