package com.calum.traininglog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.calum.traininglog.core.Reminder
import com.calum.traininglog.core.UserSettings
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Optional evening nudge. Reads the saved data, asks [Reminder] whether today is a good day, posts at most one
 * quiet notification and queues itself for the next day. Never uses exact alarms.
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val data = Store(app).load()
        val settings = data.profile.settings
        try {
            if (settings.reminderEnabled && !tooLate(settings, LocalDateTime.now())) {
                val today = LocalDate.now()
                val text = Reminder.nudgeText(data.records, data.profile, today, ReminderManager.sentThisWeek(app, today))
                if (text != null && post(app, text)) ReminderManager.recordSent(app, today)
            }
        } finally {
            ReminderManager.schedule(app, settings)
        }
        return Result.success()
    }

    /** WorkManager may run late (Doze); a nudge more than 2 hours after its time is no longer a gentle one. */
    private fun tooLate(s: UserSettings, now: LocalDateTime): Boolean {
        val due = now.toLocalDate().atTime(s.reminderHour, s.reminderMinute)
        return ChronoUnit.MINUTES.between(due, now) > 120
    }

    private fun post(ctx: Context, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ReminderManager.ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, ReminderManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Training Log")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(ReminderManager.NOTIFICATION_ID, n)
        return true
    }
}

object ReminderManager {
    const val CHANNEL_ID = "gentle_reminders"
    const val NOTIFICATION_ID = 4107
    private const val WORK = "gentle-reminder"
    private const val PREFS = "reminder_prefs"
    private const val KEY_WEEK = "week_start_epoch_day"
    private const val KEY_COUNT = "count"

    fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "Gentle reminders", NotificationManager.IMPORTANCE_DEFAULT)
        ch.enableVibration(false)
        ch.description = "An optional, quiet evening nudge. Never about missed sessions."
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Queues the next nudge check at the chosen time, or cancels it when the reminder is off. */
    fun schedule(ctx: Context, settings: UserSettings, now: LocalDateTime = LocalDateTime.now()) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!settings.reminderEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        var next = now.toLocalDate().atTime(settings.reminderHour, settings.reminderMinute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** Convenience for app start: reads the saved settings and (re)schedules. */
    fun onAppStart(ctx: Context) = schedule(ctx, Store(ctx.applicationContext).load().profile.settings)

    private fun weekStart(d: LocalDate): Long = d.minusDays((d.dayOfWeek.value - 1).toLong()).toEpochDay()

    fun sentThisWeek(ctx: Context, today: LocalDate): Int {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getLong(KEY_WEEK, -1) == weekStart(today)) p.getInt(KEY_COUNT, 0) else 0
    }

    fun recordSent(ctx: Context, today: LocalDate) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = sentThisWeek(ctx, today) + 1
        p.edit().putLong(KEY_WEEK, weekStart(today)).putInt(KEY_COUNT, n).apply()
    }
}
