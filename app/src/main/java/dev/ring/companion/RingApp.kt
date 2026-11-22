package dev.ring.companion

import kotlinx.coroutines.launch

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.ring.companion.data.MeasurementStore
import dev.ring.companion.data.Prefs
import dev.ring.companion.data.WorkoutStore
import dev.ring.companion.ring.RingSession
import dev.ring.companion.service.RingService
import dev.ring.companion.workout.GpsTracker
import dev.ring.companion.workout.WorkoutController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide objects. They live as long as the process; the foreground service keeps it alive. */
class AppGraph(context: Context) {
    /** Bluetooth work runs off the main thread. */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Workout bookkeeping runs on one thread (main) so its lists need no locks. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs = Prefs(context)
    val measurements = MeasurementStore(context)
    val workouts = WorkoutStore(context)
    val session = RingSession(context, ioScope, prefs)
    val gps = GpsTracker(context)
    val workout = WorkoutController(mainScope, session, gps, prefs, workouts, measurements)
    val sleep = dev.ring.companion.data.SleepStore(context)
    val firmware = dev.ring.companion.firmware.FirmwareCheck(context, prefs)
    val moveReminder = dev.ring.companion.ring.MoveReminder(context, mainScope, session, workout, prefs).also { it.start() }
    val history = dev.ring.companion.ring.HistorySync(ioScope, session, sleep, measurements, { prefs.measureInterval.value }, { workout.isActive || session.live.value }).also { it.start() }
    val battery = dev.ring.companion.data.BatteryStore(context)

    init {
        // Log battery whenever the ring reports a level (on connect + every 10 min).
        ioScope.launch {
            session.device.collect { d -> d?.battery?.let { battery.record(it, d.charging) } }
        }
    }
}

class RingApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // adb shell am broadcast -a dev.ring.companion.DEBUG_SEND --es type 0504 --es hex ""
            val filter = android.content.IntentFilter("dev.ring.companion.DEBUG_SEND")
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    if (i.getStringExtra("type") == "move") { graph.moveReminder.notifyMove(); return }
                    val type = i.getStringExtra("type")?.toIntOrNull(16) ?: return
                    val hexStr = i.getStringExtra("hex").orEmpty()
                    graph.session.debugSend(type, if (hexStr.isEmpty()) ByteArray(0) else dev.ring.companion.protocol.hex(hexStr))
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(this, rx, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private var idleDisconnect: kotlinx.coroutines.Job? = null

    /**
     * The app came to the screen (or a workout started): connect to the saved ring and,
     * in always-connected mode, start the background service. Does nothing while the
     * user has paused the connection.
     */
    fun resumeRing() {
        idleDisconnect?.cancel()
        ensureConnected()
        graph.history.syncIfStale()
    }

    /** Android restarted the background service: reconnect only if the app should stay connected. */
    fun onServiceRestarted() {
        if (graph.prefs.keepInBackground.value || graph.workout.isActive) ensureConnected()
    }

    /**
     * Connects if not connected (and not paused by the user), without touching the
     * idle-disconnect timer. Used by the background service when Android restarts it.
     */
    fun ensureConnected() {
        val address = graph.prefs.ringAddress.value ?: return
        if (!hasBluetoothPermission(this) || graph.prefs.connectionPaused.value) return
        if (graph.session.status.value == RingSession.Status.Disconnected) graph.session.connect(address)
        if (graph.prefs.keepInBackground.value) RingService.start(this)
    }

    /**
     * The app left the screen. In "only while the app is open" mode, disconnect after
     * two minutes, unless a workout is running. The wait covers quick app switches
     * and gives the history sync time to finish.
     */
    fun onUiHidden() {
        if (graph.prefs.keepInBackground.value) return
        idleDisconnect?.cancel()
        if (graph.session.status.value == RingSession.Status.Disconnected || graph.workout.isActive) return
        // Android freezes a backgrounded app within seconds, which would stop this timer while
        // the Bluetooth link stays up. The foreground service keeps the app awake for the wait.
        val awake = RingService.start(this)
        if (!awake) {
            graph.session.disconnect()
            return
        }
        android.util.Log.i("RingApp", "app hidden; disconnecting in ${IDLE_DISCONNECT_MS / 1000}s")
        idleDisconnect = graph.mainScope.launch {
            kotlinx.coroutines.delay(IDLE_DISCONNECT_MS)
            if (!graph.workout.isActive && !graph.prefs.keepInBackground.value) {
                android.util.Log.i("RingApp", "idle: disconnecting")
                graph.session.disconnect()
            }
        }
    }

    /** Disconnect button: drop the link and stay off until [resumeConnection]. */
    fun pauseConnection() {
        idleDisconnect?.cancel()
        graph.prefs.connectionPaused.set(true)
        graph.session.disconnect()
    }

    fun resumeConnection() {
        graph.prefs.connectionPaused.set(false)
        resumeRing()
    }

    companion object {
        private const val IDLE_DISCONNECT_MS = 2 * 60_000L
    }
}

val Context.graph: AppGraph get() = (applicationContext as RingApp).graph

fun hasBluetoothPermission(context: Context): Boolean {
    val needed = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
