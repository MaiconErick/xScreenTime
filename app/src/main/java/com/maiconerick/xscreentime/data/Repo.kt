package com.maiconerick.xscreentime.data

import android.content.Context
import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.ZoneId

@Immutable
data class Session(
    val pkg: String,
    val start: Long,
    val end: Long,
    val live: Boolean = false,
    val endReason: Int = EndReason.UNKNOWN,
    val nextPkg: String? = null,
) {
    val duration get() = end - start
}

@Immutable
data class AppTotal(val pkg: String, val total: Long, val sessions: Int, val longest: Long)

@Immutable
data class DaySummary(
    val date: LocalDate,
    val total: Long,
    val unlocks: Int,
    val hourly: List<Long>,
    val apps: List<AppTotal>,
    val sessions: List<Session>,
    /** True when the day comes from an export (daily totals only: no sessions, no hours). */
    val imported: Boolean = false,
)

@Immutable
data class DayTotal(val date: LocalDate, val total: Long)

object Repo {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun dayStart(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Imported totals are used for days before our own session archive begins; from that day on,
     * sessions (which are more precise) win so nothing is counted twice.
     */
    private suspend fun importCutoff(ctx: Context): LocalDate =
        firstTrackedDay(ctx) ?: LocalDate.now().plusDays(1)

    /** Sessions overlapping [from, to), clipped to it, ignored packages removed, plus the live one. */
    private suspend fun clipped(ctx: Context, from: Long, to: Long, pkg: String? = null): List<Session> {
        val dao = UsageDb.get(ctx).dao()
        val ignored = AppInfoCache.ignoredPackages(ctx)
        val stored = if (pkg == null) dao.sessionsOverlapping(from, to) else dao.sessionsForApp(pkg, from, to)
        val live = Tracker.openSession?.takeIf { it.end > from && it.start < to && (pkg == null || it.pkg == pkg) }
        return buildList {
            stored.forEach {
                val clippedEnd = it.end > to
                add(
                    Session(
                        it.pkg, maxOf(it.start, from), minOf(it.end, to),
                        endReason = if (clippedEnd) EndReason.CONTINUES else it.endReason,
                        nextPkg = if (clippedEnd) null else it.nextPkg,
                    ),
                )
            }
            live?.let { add(Session(it.pkg, maxOf(it.start, from), minOf(System.currentTimeMillis(), to), live = true)) }
        }.filter { it.pkg !in ignored && it.end > it.start }.sortedBy { it.start }
    }

    suspend fun day(ctx: Context, date: LocalDate): DaySummary {
        if (date < importCutoff(ctx)) importedDay(ctx, date)?.let { return it }
        val from = dayStart(date)
        val to = dayStart(date.plusDays(1))
        val sessions = clipped(ctx, from, to)

        val hourly = LongArray(24)
        for (s in sessions) {
            var t = s.start
            while (t < s.end) {
                val h = ((t - from) / 3_600_000).toInt().coerceIn(0, 23)
                val hourEnd = from + (h + 1) * 3_600_000L
                val segEnd = minOf(s.end, hourEnd)
                hourly[h] += segEnd - t
                t = segEnd
            }
        }

        val apps = sessions.groupBy { it.pkg }.map { (pkg, list) ->
            AppTotal(pkg, list.sumOf { it.duration }, list.size, list.maxOf { it.duration })
        }.sortedByDescending { it.total }

        return DaySummary(
            date = date,
            total = sessions.sumOf { it.duration },
            unlocks = UsageDb.get(ctx).dao().unlockCount(from, to),
            hourly = hourly.toList(),
            apps = apps,
            sessions = sessions,
        )
    }

    private suspend fun importedDay(ctx: Context, date: LocalDate): DaySummary? {
        val dao = UsageDb.get(ctx).dao()
        val ignored = AppInfoCache.ignoredPackages(ctx)
        val rows = dao.importedDay(date.toEpochDay()).filter { it.pkg !in ignored }
        if (rows.isEmpty()) return null
        val apps = rows.map { AppTotal(it.pkg, it.ms, it.launches, 0L) }.sortedByDescending { it.total }
        return DaySummary(
            date = date,
            total = apps.sumOf { it.total },
            unlocks = dao.importedUnlocks(date.toEpochDay()) ?: 0,
            hourly = List(24) { 0L },
            apps = apps,
            sessions = emptyList(),
            imported = true,
        )
    }

    /** Imported rows for [first]..[last] that fall before the session archive starts. */
    private suspend fun importedBefore(ctx: Context, first: LocalDate, last: LocalDate, pkg: String?): List<ImportedDayEntity> {
        val cutoff = importCutoff(ctx)
        if (first >= cutoff) return emptyList()
        val to = minOf(last, cutoff.minusDays(1)).toEpochDay()
        val dao = UsageDb.get(ctx).dao()
        val ignored = AppInfoCache.ignoredPackages(ctx)
        val rows = if (pkg == null) dao.importedRange(first.toEpochDay(), to) else dao.importedRangeForApp(pkg, first.toEpochDay(), to)
        return rows.filter { it.pkg !in ignored }
    }

    /** Daily totals for the [days] days ending at [end] (inclusive); optionally for a single app. */
    suspend fun dailyTotals(ctx: Context, end: LocalDate, days: Int, pkg: String? = null): List<DayTotal> {
        val first = end.minusDays(days - 1L)
        val sessions = clipped(ctx, dayStart(first), dayStart(end.plusDays(1)), pkg)
        val totals = LongArray(days)
        for (s in sessions) {
            var t = s.start
            while (t < s.end) {
                val d = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
                val idx = (d.toEpochDay() - first.toEpochDay()).toInt()
                val segEnd = minOf(s.end, dayStart(d.plusDays(1)))
                if (idx in 0 until days) totals[idx] += segEnd - t
                t = segEnd
            }
        }
        for (row in importedBefore(ctx, first, end, pkg)) {
            val idx = (row.day - first.toEpochDay()).toInt()
            if (idx in 0 until days) totals[idx] += row.ms
        }
        return (0 until days).map { DayTotal(first.plusDays(it.toLong()), totals[it]) }
    }

    suspend fun appSessions(ctx: Context, pkg: String, date: LocalDate): List<Session> =
        clipped(ctx, dayStart(date), dayStart(date.plusDays(1)), pkg)

    /** Aggregate per-app totals across a date range (for the Trends top list). */
    suspend fun appTotals(ctx: Context, end: LocalDate, days: Int): List<AppTotal> {
        val first = end.minusDays(days - 1L)
        val sessions = clipped(ctx, dayStart(first), dayStart(end.plusDays(1)))
        val totals = HashMap<String, AppTotal>()
        sessions.groupBy { it.pkg }.forEach { (pkg, list) ->
            totals[pkg] = AppTotal(pkg, list.sumOf { it.duration }, list.size, list.maxOf { it.duration })
        }
        for (row in importedBefore(ctx, first, end, null)) {
            val prev = totals[row.pkg] ?: AppTotal(row.pkg, 0L, 0, 0L)
            totals[row.pkg] = prev.copy(total = prev.total + row.ms, sessions = prev.sessions + row.launches)
        }
        return totals.values.sortedByDescending { it.total }
    }

    suspend fun firstTrackedDay(ctx: Context): LocalDate? =
        UsageDb.get(ctx).dao().firstStart()?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
