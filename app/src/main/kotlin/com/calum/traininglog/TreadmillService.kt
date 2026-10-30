package com.calum.traininglog

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
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.calum.traininglog.core.CueTones
import com.calum.traininglog.core.CueType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the treadmill clock and its beeps alive while the app is minimised or the screen is dim.
 * Cues are a beep plus a screen flash (the UI watches [TreadmillEngine.Ui.flashing]); no vibration, no voice.
 *
 * Reliability notes:
 *  - Foreground service (type mediaPlayback) plus a partial wake lock keep the CPU running with the screen off.
 *  - The loop sleeps until the next second boundary rather than polling; if the OS still delays it, the engine
 *    catches up from the monotonic clock and plays only the most relevant cue.
 *  - START_NOT_STICKY: after process death the session state is gone, so the service must never come back
 *    as an empty or stale session. A start with no active session just stops itself.
 */
class TreadmillService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var player: CuePlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // Must call startForeground promptly after startForegroundService, even if we are about to stop.
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(currentText(), TreadmillEngine.ui.value.paused), type)
        } catch (e: Exception) {
            // e.g. foreground start not allowed; without foreground status the clock would be unreliable.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!TreadmillEngine.ui.value.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (player == null) player = CuePlayer(this, scope)
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_NOT_STICKY
    }

    private suspend fun runLoop() {
        try {
            while (scope.isActive) {
                val cues = TreadmillEngine.tick(SystemClock.elapsedRealtime())
                cues.lastOrNull()?.let { player?.play(it.type) }
                refreshNotification()
                val ui = TreadmillEngine.ui.value
                if (!ui.active) break
                if (ui.finished) {
                    // Let the done cue finish, then drop the foreground state and leave a dismissible notice.
                    delay(CueTones.durationMs(CueType.DONE) + 500L)
                    if (TreadmillEngine.ui.value.active) postFinishedNotice()
                    break
                }
                delay(TreadmillEngine.nextDelayMs(SystemClock.elapsedRealtime()))
            }
        } finally {
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun postFinishedNotice() {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Treadmill session")
            .setContentText("Session complete")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        // The id is reused; DETACH keeps this notice after the service stops, now clearable by swiping.
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun currentText(): String {
        val ui = TreadmillEngine.ui.value
        val s = ui.state ?: return "Starting…"
        val left = "${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)}"
        return when {
            ui.finished -> "Session complete"
            ui.paused -> "Paused, $left left in this block"
            else -> "${s.current.speedKmh} km/h, ${s.current.inclinePct}% incline, $left left"
        }
    }

    private fun refreshNotification() {
        val ui = TreadmillEngine.ui.value
        if (ui.state == null) return
        val text = currentText()
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, ui.paused))
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String, paused: Boolean): Notification {
        val toggleLabel = if (paused) "Resume" else "Pause"
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Treadmill session")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(toggleIcon, toggleLabel, TreadmillActionReceiver.pending(this, TreadmillActionReceiver.ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_rew, "-30s", TreadmillActionReceiver.pending(this, TreadmillActionReceiver.ACTION_MINUS))
            .addAction(android.R.drawable.ic_media_ff, "+30s", TreadmillActionReceiver.pending(this, TreadmillActionReceiver.ACTION_PLUS))
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Treadmill session", NotificationManager.IMPORTANCE_LOW))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Timeout is a safety net only (longest plan is 2 h plus pauses); released explicitly on every exit.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traininglog:treadmill").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        loop?.cancel()
        player?.release()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "treadmill"
        const val NOTIFICATION_ID = 1
    }
}
