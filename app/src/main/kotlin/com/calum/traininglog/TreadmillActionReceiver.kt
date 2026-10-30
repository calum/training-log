package com.calum.traininglog

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Entry point for the notification buttons and the picture-in-picture buttons. They act on the in-process
 * [TreadmillEngine] directly, so they work whether the app is on screen, in PiP or only in the shade.
 * If the process was killed the engine is inactive and every action is a no-op.
 */
class TreadmillActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val now = SystemClock.elapsedRealtime()
        when (intent.action) {
            ACTION_TOGGLE -> TreadmillEngine.togglePause(now)
            ACTION_PLUS -> TreadmillEngine.adjustCurrent(30, now)
            ACTION_MINUS -> TreadmillEngine.adjustCurrent(-30, now)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.calum.traininglog.treadmill.TOGGLE"
        const val ACTION_PLUS = "com.calum.traininglog.treadmill.PLUS_30"
        const val ACTION_MINUS = "com.calum.traininglog.treadmill.MINUS_30"

        /** The action string is the PendingIntent identity; distinct request codes keep them apart anyway. */
        fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, TreadmillActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
