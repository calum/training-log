package com.calum.traininglog

import android.content.Context

/** Private backup of the full data to the user's own Google Drive app-data folder. */
object DriveBackup {
    /** Called after each saved session. Must be cheap and safe to call when Drive isn't set up. */
    fun requestBackup(ctx: Context, data: AppData) {}
}
