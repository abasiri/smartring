package dev.ring.companion.ring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.ring.companion.MainActivity
import dev.ring.companion.R
import dev.ring.companion.data.Prefs
import dev.ring.companion.workout.WorkoutController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Hourly nudge to get up, like a stand reminder: at 10 minutes to the hour, if you have taken
 * fewer than [MIN_STEPS] steps since the hour began, post a notification.
 *
 * Steps come from the ring's day total. The total just before the hour turns over is the
 * baseline; an hour with no step reports (ring out of reach) never triggers a reminder.
 */
class MoveReminder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: RingSession,
    private val workout: WorkoutController,
    private val prefs: Prefs,
) {
    private var lastTotal: Int? = null
    private var lastHour: LocalDateTime? = null
    private var baseline: Int? = null
    private var baselineHour: LocalDateTime? = null

    fun start() {
        scope.launch {
            session.activity.collect { a ->
                if (a == null) return@collect
                val hour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
                if (hour != lastHour) {
                    // First report of a new hour: the last total from the previous hour is the baseline.
                    // (If the previous report was from an earlier day, start from this one.)
                    val prev = lastTotal
                    baseline = if (prev != null && lastHour?.toLocalDate() == hour.toLocalDate() && prev <= a.steps) prev else a.steps
                    baselineHour = hour
                    lastHour = hour
                }
                lastTotal = a.steps
            }
        }
        scope.launch {
            while (true) {
                delay(msUntilNextCheck())
                runCatching { check() }.onFailure { Log.w(TAG, "move check failed", it) }
            }
        }
    }

    private fun check() {
        if (!prefs.moveReminders.value) return
        val now = LocalDateTime.now()
        if (now.hour < prefs.moveStartHour || now.hour >= prefs.moveEndHour) return
        if (workout.isActive) return
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val base = baseline ?: return
        if (baselineHour != hour || lastHour != hour) return // no step data this hour
        val moved = (lastTotal ?: base) - base
        Log.i(TAG, "steps this hour: $moved")
        if (moved < MIN_STEPS) notifyMove()
    }

    /** Posts the reminder now (also used by the debug hook to preview it). */
    fun notifyMove() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Move reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A nudge at 10 to the hour when you've barely moved"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time to move")
            .setContentText("You've been still most of this hour. A two-minute walk counts.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L) // gone by the top of the hour
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** Time until the next hh:50. */
    private fun msUntilNextCheck(): Long {
        val now = LocalDateTime.now()
        var next = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(50)
        if (!next.isAfter(now)) next = next.plusHours(1)
        val zone = ZoneId.systemDefault()
        return next.atZone(zone).toInstant().toEpochMilli() - now.atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        private const val TAG = "MoveReminder"
        private const val CHANNEL = "move"
        private const val NOTIFICATION_ID = 2
        /** Roughly a minute of walking. */
        const val MIN_STEPS = 50
    }
}
