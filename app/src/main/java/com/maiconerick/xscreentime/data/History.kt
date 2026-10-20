package com.maiconerick.xscreentime.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.runtime.Immutable

/** Everything Android keeps per app in a long-term bucket. */
@Immutable
data class HistoryApp(
    val pkg: String,
    /** Time with an activity in the foreground (what "screen time" means). */
    val foreground: Long,
    /** Time with any window visible, incl. split-screen / picture-in-picture. */
    val visible: Long,
    /** Time the app ran a foreground service (e.g. music, navigation) — not screen time. */
    val foregroundService: Long,
    val lastUsed: Long,
    /** Hidden system field; null when the platform refuses the reflective read. */
    val launches: Int?,
)

/** One bucket of Android's own long-term aggregates (per-app totals, no sessions). */
@Immutable
data class Period(
    val start: Long,
    val end: Long,
    val total: Long,
    val apps: List<HistoryApp>,
    val screenOnCount: Int?,
    val screenOnTime: Long?,
    val unlockCount: Int?,
)

/**
 * Android keeps per-app totals far longer than raw events: ~6 monthly and ~3 yearly buckets.
 * These are the system's own numbers, independent of our archive, so they reach back to before
 * this app was installed.
 */
object History {
    const val MONTHS = UsageStatsManager.INTERVAL_MONTHLY
    const val YEARS = UsageStatsManager.INTERVAL_YEARLY

    fun load(ctx: Context, interval: Int): List<Period> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val ignored = AppInfoCache.ignoredPackages(ctx)
        val now = System.currentTimeMillis()

        // Device-wide screen-on / unlock aggregates, keyed by bucket start like the app stats.
        val events = runCatching { usm.queryEventStats(interval, 0L, now) }.getOrDefault(emptyList())
            .groupBy { it.firstTimeStamp }

        return usm.queryUsageStats(interval, 0L, now)
            .filter { it.packageName !in ignored && (it.totalTimeInForeground > 0 || it.totalTimeVisible > 0) }
            .groupBy { it.firstTimeStamp }
            .map { (start, stats) ->
                val apps = stats.groupBy { it.packageName }.map { (pkg, s) ->
                    HistoryApp(
                        pkg = pkg,
                        foreground = s.sumOf { it.totalTimeInForeground },
                        visible = s.sumOf { it.totalTimeVisible },
                        foregroundService = s.sumOf { it.totalTimeForegroundServiceUsed },
                        lastUsed = s.maxOf { it.lastTimeUsed },
                        launches = s.map { launchCount(it) }.let { l -> if (l.any { it == null }) null else l.sumOf { it!! } },
                    )
                }.sortedByDescending { it.foreground }
                val ev = events[start].orEmpty()
                val screenOn = ev.firstOrNull { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE }
                val unlocks = ev.firstOrNull { it.eventType == UsageEvents.Event.KEYGUARD_HIDDEN }
                Period(
                    start = start,
                    end = stats.maxOf { it.lastTimeStamp },
                    total = apps.sumOf { it.foreground },
                    apps = apps,
                    screenOnCount = screenOn?.count,
                    screenOnTime = screenOn?.totalTime,
                    unlockCount = unlocks?.count,
                )
            }
            .sortedBy { it.start }
    }

    private var launchReader: ((UsageStats) -> Int?)? = null

    /** `appLaunchCount` is @SystemApi; try the getter, then the field, and remember what works. */
    private fun launchCount(u: UsageStats): Int? {
        launchReader?.let { return it(u) }
        val viaMethod = runCatching { UsageStats::class.java.getMethod("getAppLaunchCount") }.getOrNull()
        val viaField = runCatching { UsageStats::class.java.getDeclaredField("mAppLaunchCount").apply { isAccessible = true } }.getOrNull()
        val reader: (UsageStats) -> Int? = when {
            viaMethod != null && runCatching { viaMethod.invoke(u) }.isSuccess -> { x -> runCatching { viaMethod.invoke(x) as Int }.getOrNull() }
            viaField != null && runCatching { viaField.getInt(u) }.isSuccess -> { x -> runCatching { viaField.getInt(x) }.getOrNull() }
            else -> { _ -> null }
        }
        launchReader = reader
        return reader(u)
    }
}
