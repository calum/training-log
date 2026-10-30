package com.calum.traininglog

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Pushes the public export to GitHub using the newest saved data. Retries (with backoff) only for transient trouble. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = when (GithubSync.runSync(applicationContext)) {
        is SyncOutcome.Retry -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        else -> Result.success() // success, or needs the user: the dirty flag keeps it queued
    }

    private companion object { const val MAX_ATTEMPTS = 8 }
}

object SyncManager {
    private const val WORK = "github-sync"

    /** Queues a sync for when the network is available. Cheap to call after every session. */
    fun request(ctx: Context) {
        val app = ctx.applicationContext
        if (!GithubSync.isConfigured(app)) return
        GithubSync.markDirty(app)
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE: a running job may have read the data before this save, so start over with the newest.
        WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** At app start: refresh the status and retry if the last attempt didn't finish. */
    fun onAppStart(ctx: Context) {
        val app = ctx.applicationContext
        GithubSync.refresh(app)
        if (GithubSync.isConfigured(app) && GithubSync.isDirty(app) && GithubSync.state.value !is SyncState.NeedsSignIn) request(app)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK) }
}
