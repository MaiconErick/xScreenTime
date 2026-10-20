package com.maiconerick.xscreentime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.maiconerick.xscreentime.data.PlayTime
import com.maiconerick.xscreentime.data.Tracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Background archive + PlayTime upload four times a day, spread over the waking hours (07:00 to
 * midnight). Nothing stays running in between: each alarm syncs once and arms the next slot.
 *
 * The sync runs right inside the alarm's broadcast. In doze, background jobs get no network until
 * a maintenance window (hours, with the screen off), but an allow-while-idle alarm puts the app on
 * the temporary power allowlist for a few seconds, which is enough for a ~100 KB download+upload.
 * Only if that attempt fails does a network-constrained worker take over and retry.
 */
object Schedule {
    val SLOTS: List<LocalTime> = listOf(LocalTime.of(7, 0), LocalTime.of(12, 40), LocalTime.of(18, 20), LocalTime.of(23, 55))
    const val ACTION_SYNC_NOW = "com.maiconerick.xscreentime.SYNC_NOW"
    private const val WORK = "slot-sync"

    fun next(now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime =
        SLOTS.map { now.with(it).withSecond(0).withNano(0) }.firstOrNull { it.isAfter(now) }
            ?: now.plusDays(1).with(SLOTS.first()).withSecond(0).withNano(0)

    fun arm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, SlotReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = next()
        val t = at.toInstant().toEpochMilli()
        // Exact needs the special-access grant; without it the slot may drift by some minutes.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        Log.i("Schedule", "next sync at $at")
    }

    /** Runs the sync now inside [receiver]'s broadcast; falls back to the worker if it fails. */
    fun runInBroadcast(receiver: BroadcastReceiver, ctx: Context, force: Boolean) {
        val pending = receiver.goAsync()
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val status = withTimeoutOrNull(40_000) {
                    Tracker.sync(app)
                    PlayTime.sync(app, force)
                }
                if (status == null || !status.ok) {
                    Log.i("Schedule", "direct sync didn't finish (${status?.message}); queuing the worker")
                    enqueue(app, force)
                }
            } finally {
                pending.finish()
            }
        }
    }

    fun enqueue(ctx: Context, force: Boolean) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }
}

/** Fired by our own alarm (not exported). */
class SlotReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Schedule.arm(ctx)
        Schedule.runInBroadcast(this, ctx, force = false)
    }
}

/**
 * Re-arms the alarm after a reboot or an update (both clear it), and lets adb trigger a forced sync:
 * `adb shell am broadcast -a com.maiconerick.xscreentime.SYNC_NOW -n com.maiconerick.xscreentime/.SystemReceiver`.
 * Guarded by DUMP, which only the system and the shell hold.
 */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Schedule.arm(ctx)
        if (intent.action == Schedule.ACTION_SYNC_NOW) Schedule.runInBroadcast(this, ctx, force = true)
    }
}
