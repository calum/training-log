package com.calum.traininglog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
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
 */
class TreadmillService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traininglog:treadmill").apply {
                acquire(3 * 60 * 60 * 1000L)
            }
        }
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    val cues = TreadmillEngine.tick(SystemClock.elapsedRealtime())
                    cues.forEach { beep(it.type) }
                    refreshNotification()
                    if (!TreadmillEngine.ui.value.active) {
                        stopSelf()
                        break
                    }
                    delay(200)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun beep(type: CueType) {
        val t = tone ?: return
        when (type) {
            CueType.PRE_WARNING -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
            CueType.CHANGE -> t.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
            CueType.DONE -> t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
        }
    }

    private fun refreshNotification() {
        val ui = TreadmillEngine.ui.value
        val s = ui.state ?: return
        val text = when {
            ui.finished -> "Session complete"
            ui.paused -> "Paused"
            else -> "${s.current.speedKmh} km/h, ${s.current.inclinePct}% incline, ${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)} left"
        }
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Treadmill session")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Treadmill session", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        tone?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "treadmill"
        const val NOTIFICATION_ID = 1
    }
}
