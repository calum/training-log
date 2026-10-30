package com.calum.traininglog

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var inPip by mutableStateOf(false)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the session still runs if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        UpdateManager.check(this)
        ReminderManager.onAppStart(this)
        setContent { AppRoot(vm, inPip) }
        // Keep PiP params (auto-enter flag and Pause/Resume button) in step with the session, on any thread of
        // events: start, pause, resume, finish, end.
        lifecycleScope.launch {
            TreadmillEngine.ui.map { Triple(it.active && !it.finished, it.paused, it.active) }
                .distinctUntilChanged()
                .collect { updatePipParams() }
        }
    }

    override fun onStart() {
        super.onStart()
        // A "Session complete" notice left over from a finished session is stale once the app is open again
        // without an active session (e.g. after End or a process restart).
        if (!TreadmillEngine.ui.value.active) {
            getSystemService(NotificationManager::class.java).cancel(TreadmillService.NOTIFICATION_ID)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePipParams() // refresh sourceRectHint for the current layout
    }

    /**
     * Leaving the app mid-treadmill-session floats the workout view so YouTube can run behind it.
     * API 31+ also enters via setAutoEnterEnabled; this covers older versions and is a guarded duplicate otherwise.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val ui = TreadmillEngine.ui.value
        if (ui.active && !ui.finished && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val ui = TreadmillEngine.ui.value
        val running = ui.active && !ui.finished
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(running).setSeamlessResizeEnabled(false)
        }
        // Source hint: a 16:9 slice of the content, so the transition animates from the right shape.
        val r = Rect()
        if (window.decorView.getGlobalVisibleRect(r) && r.width() > 0) {
            val h = r.width() * 9 / 16
            val top = r.centerY() - h / 2
            builder.setSourceRectHint(Rect(r.left, top, r.right, top + h))
        }
        if (running) builder.setActions(pipActions(ui.paused))
        return builder.build()
    }

    /** System-drawn buttons in the PiP window; they hit the engine through [TreadmillActionReceiver]. */
    private fun pipActions(paused: Boolean): List<RemoteAction> {
        fun action(icon: Int, title: String, act: String) = RemoteAction(
            Icon.createWithResource(this, icon), title, title, TreadmillActionReceiver.pending(this, act),
        )
        return listOf(
            action(android.R.drawable.ic_media_rew, "-30s", TreadmillActionReceiver.ACTION_MINUS),
            if (paused) action(android.R.drawable.ic_media_play, "Resume", TreadmillActionReceiver.ACTION_TOGGLE)
            else action(android.R.drawable.ic_media_pause, "Pause", TreadmillActionReceiver.ACTION_TOGGLE),
            action(android.R.drawable.ic_media_ff, "+30s", TreadmillActionReceiver.ACTION_PLUS),
        )
    }

    /**
     * Closing the PiP window only stops/destroys this activity; the session lives in [TreadmillEngine] and
     * [TreadmillService], so nothing is ended here.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }
}
