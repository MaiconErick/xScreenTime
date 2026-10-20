package com.maiconerick.xscreentime

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maiconerick.xscreentime.data.PlayTime
import com.maiconerick.xscreentime.data.Tracker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The old 12h periodic job is replaced by the four daily slots in Schedule.
        WorkManager.getInstance(this).cancelUniqueWork("archive")
        Schedule.arm(this)
    }
}

/** Archives the system's usage log (kept only ~a week) and uploads the games' play time if it changed. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Tracker.sync(applicationContext)
        val status = PlayTime.sync(applicationContext, force = inputData.getBoolean(KEY_FORCE, false))
        return if (status != null && !status.ok && runAttemptCount < 5) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}
