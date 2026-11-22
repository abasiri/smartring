package dev.ring.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ring.companion.MainActivity
import dev.ring.companion.R
import dev.ring.companion.graph
import dev.ring.companion.hasLocationPermission
import dev.ring.companion.ring.RingSession
import dev.ring.companion.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the ring is connected or a workout runs, so
 * Android does not kill the Bluetooth link when the screen is off.
 */
class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var withLocation = false
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(buildNotification("Connecting to your ring…"))

        val g = graph
        // Android may restart this service on its own after killing the app; reconnect then too.
        (application as dev.ring.companion.RingApp).onServiceRestarted()
        scope.launch {
            combine(g.session.status, g.session.device, g.workout.live) { status, device, live ->
                Triple(status, device, live)
            }.collect { (status, device, live) ->
                val idle = live == null && status == RingSession.Status.Disconnected
                if (idle && (!g.prefs.keepInBackground.value || g.prefs.connectionPaused.value)) {
                    stopSelf()
                    return@collect
                }
                // Add the location type while a GPS workout runs.
                withLocation = live?.gpsActive == true
                // Update through startForeground, not notify(): Android may post the first
                // foreground notification late and would then cover a newer notify().
                val t = text(status, device, live)
                if (t != lastText) {
                    lastText = t
                    goForeground(buildNotification(t))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun text(
        status: RingSession.Status,
        device: RingSession.DeviceState?,
        live: dev.ring.companion.workout.WorkoutController.Live?,
    ): String {
        if (live != null) {
            val hr = live.hr?.let { " · $it bpm" } ?: ""
            val state = if (live.paused) "Paused" else live.kind.label
            val ring = if (status != RingSession.Status.Connected) " · reconnecting ring" else ""
            return "$state ${formatDuration(live.activeMs)}$hr$ring"
        }
        val name = device?.name ?: "Ring"
        return when (status) {
            RingSession.Status.Connected -> "$name connected" + (device?.battery?.let { " · $it%" } ?: "") +
                (if (!graph.prefs.keepInBackground.value) " · disconnects 2 min after you leave the app" else "")
            RingSession.Status.Connecting -> "Connecting to $name…"
            RingSession.Status.Reconnecting -> "Reconnecting to $name…"
            RingSession.Status.Disconnected -> "$name not connected"
        }
    }

    private fun goForeground(n: Notification) {
        var types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (withLocation && Build.VERSION.SDK_INT >= 29 && hasLocationPermission(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
        } catch (e: Exception) {
            Log.w("RingService", "startForeground failed", e)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ring")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Show right away instead of Android's up-to-10-second delay for service notifications.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Ring connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the app stays connected to your ring"
                },
            )
        }
    }

    companion object {
        private const val CHANNEL = "ring"
        private const val NOTIFICATION_ID = 1

        /** Returns false if Android refused (e.g. starting from the background is not allowed). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, RingService::class.java))
            true
        } catch (e: Exception) {
            Log.w("RingService", "could not start", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RingService::class.java))
        }
    }
}
