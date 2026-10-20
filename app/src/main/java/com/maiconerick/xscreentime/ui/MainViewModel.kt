package com.maiconerick.xscreentime.ui

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maiconerick.xscreentime.data.AppInfo
import com.maiconerick.xscreentime.data.AppInfoCache
import com.maiconerick.xscreentime.data.AppTotal
import com.maiconerick.xscreentime.data.DaySummary
import com.maiconerick.xscreentime.data.DayTotal
import com.maiconerick.xscreentime.data.GameEntity
import com.maiconerick.xscreentime.data.GameStats
import com.maiconerick.xscreentime.data.History
import com.maiconerick.xscreentime.data.Period
import com.maiconerick.xscreentime.data.PlaySyncStatus
import com.maiconerick.xscreentime.data.PlayTime
import com.maiconerick.xscreentime.data.Repo
import com.maiconerick.xscreentime.data.Session
import com.maiconerick.xscreentime.data.Tracker
import com.maiconerick.xscreentime.data.UsageDb
import com.maiconerick.xscreentime.data.XpChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Flattened timeline rows so the LazyColumn gets stable keys and cheap items. */
@Immutable
sealed interface TimelineRow {
    val key: String

    data class Hour(val hour: Int) : TimelineRow { override val key = "h$hour" }
    data class Item(val session: Session) : TimelineRow { override val key = "s${session.pkg}${session.start}" }
    data class Gap(val from: Long, val to: Long) : TimelineRow { override val key = "g$from" }
}

@Immutable
data class DayState(
    val summary: DaySummary,
    val timeline: List<TimelineRow>,
)

@Immutable
data class TrendsState(val days: Int, val totals: List<DayTotal>, val apps: List<AppTotal>)

@Immutable
data class HistoryState(val interval: Int, val periods: List<Period>, val selected: Int)

@Immutable
data class GamesState(
    val games: List<GameStats>,
    val status: PlaySyncStatus?,
    /** Apps used in the last year, for the "mark as game" picker. */
    val candidates: List<AppTotal>,
    /** Rows changed in xPlayTime, as found by the last upload's merge. */
    val changes: XpChanges = XpChanges(),
)

@Immutable
data class AppDetailState(
    val pkg: String,
    val date: LocalDate,
    val totals: List<DayTotal>,
    val sessions: List<Session>,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val date = MutableStateFlow(LocalDate.now())
    private val _day = MutableStateFlow<DayState?>(null)
    val day: StateFlow<DayState?> = _day
    private val _trends = MutableStateFlow<TrendsState?>(null)
    val trends: StateFlow<TrendsState?> = _trends
    private val _detail = MutableStateFlow<AppDetailState?>(null)
    val detail: StateFlow<AppDetailState?> = _detail
    private val _history = MutableStateFlow<HistoryState?>(null)
    val history: StateFlow<HistoryState?> = _history
    private val _apps = MutableStateFlow<Map<String, AppInfo>>(emptyMap())
    val apps: StateFlow<Map<String, AppInfo>> = _apps
    private val _games = MutableStateFlow<GamesState?>(null)
    val games: StateFlow<GamesState?> = _games
    private val _gamePkgs = MutableStateFlow<Set<String>>(emptySet())
    val gamePkgs: StateFlow<Set<String>> = _gamePkgs
    val playSyncing = MutableStateFlow(false)
    val hasPermission = MutableStateFlow(Tracker.hasPermission(app))
    val syncing = MutableStateFlow(false)

    private var trendDays = 7
    private var dayJob: Job? = null

    fun onResume() {
        hasPermission.value = Tracker.hasPermission(ctx)
        if (!hasPermission.value) return
        viewModelScope.launch {
            syncing.value = true
            Tracker.sync(ctx)
            syncing.value = false
            reloadAll()
            exportGames(force = false)
        }
    }

    fun loadGames() = viewModelScope.launch {
        val state = withContext(Dispatchers.Default) {
            val games = UsageDb.get(ctx).dao().games()
            _gamePkgs.value = games.map { it.pkg }.toSet()
            val candidates = Repo.appTotals(ctx, LocalDate.now(), 365).filter { !it.pkg.startsWith("stayfree:") }.take(150)
            resolveIcons(games.map { it.pkg } + candidates.map { it.pkg })
            val names = games.associate { it.pkg to (it.name ?: _apps.value[it.pkg]?.label ?: it.pkg) }
            GamesState(PlayTime.stats(PlayTime.rows(ctx), games, names), PlayTime.status(ctx), candidates, PlayTime.changes(ctx))
        }
        _games.value = state
    }

    fun toggleGame(pkg: String) = viewModelScope.launch {
        val dao = UsageDb.get(ctx).dao()
        if (pkg in _gamePkgs.value) dao.deleteGame(pkg) else dao.upsertGame(GameEntity(pkg))
        _gamePkgs.update { if (pkg in it) it - pkg else it + pkg }
        loadGames()
        exportGames(force = false)
    }

    fun renameGame(pkg: String, name: String) = viewModelScope.launch {
        val dao = UsageDb.get(ctx).dao()
        val game = dao.games().find { it.pkg == pkg } ?: return@launch
        dao.upsertGame(game.copy(name = name.trim().ifBlank { null }))
        loadGames()
        exportGames(force = false)
    }

    /** Uploads the PlayTime db when it changed; [force] re-uploads anyway. */
    fun exportGames(force: Boolean) = viewModelScope.launch {
        // PlayTime.sync is serialized internally, so a toggle during an upload is exported right after it.
        playSyncing.value = true
        val status = PlayTime.sync(ctx, force)
        playSyncing.value = false
        _games.update { it?.copy(status = status, changes = PlayTime.changes(ctx)) }
    }

    fun setDate(d: LocalDate) {
        if (d.isAfter(LocalDate.now())) return
        date.value = d
        loadDay()
        _detail.value?.let { openApp(it.pkg) }
    }

    fun setTrendDays(days: Int) {
        trendDays = days
        loadTrends()
    }

    fun loadHistory(interval: Int = _history.value?.interval ?: History.MONTHS) = viewModelScope.launch {
        val state = withContext(Dispatchers.Default) {
            val periods = History.load(ctx, interval)
            resolveIcons(periods.flatMap { p -> p.apps.take(40).map { it.pkg } }.distinct())
            HistoryState(interval, periods, periods.lastIndex)
        }
        _history.value = state
    }

    fun selectPeriod(index: Int) {
        _history.update { it?.copy(selected = index) }
    }

    fun openApp(pkg: String) = viewModelScope.launch {
        val d = date.value
        val state = withContext(Dispatchers.Default) {
            val sessions = Repo.appSessions(ctx, pkg, d)
            resolveIcons(listOf(pkg) + sessions.mapNotNull { it.nextPkg }.distinct())
            AppDetailState(pkg, d, Repo.dailyTotals(ctx, d, 14, pkg), sessions)
        }
        _detail.value = state
    }

    fun closeApp() { _detail.value = null }

    private fun reloadAll() {
        loadDay()
        loadTrends()
        loadHistory()
        loadGames()
        _detail.value?.let { openApp(it.pkg) }
    }

    private fun loadDay() {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            val d = date.value
            val state = withContext(Dispatchers.Default) {
                val summary = Repo.day(ctx, d)
                resolveIcons(summary.apps.map { it.pkg })
                DayState(summary, buildTimeline(summary.sessions))
            }
            _day.value = state
        }
    }

    private fun loadTrends() = viewModelScope.launch {
        val days = trendDays
        val state = withContext(Dispatchers.Default) {
            val end = LocalDate.now()
            // Long ranges can span hundreds of apps; the tail is noise and costs icon decoding.
            val apps = Repo.appTotals(ctx, end, days).take(150)
            resolveIcons(apps.map { it.pkg })
            TrendsState(days, Repo.dailyTotals(ctx, end, days), apps)
        }
        _trends.value = state
    }

    /** Decode icons before the list is shown, so rows never pop in or decode during a scroll. */
    private suspend fun resolveIcons(pkgs: List<String>) = withContext(Dispatchers.IO) {
        val missing = pkgs.filter { it !in _apps.value }
        if (missing.isEmpty()) return@withContext
        val loaded = AppInfoCache.loadAll(ctx, missing)
        _apps.update { it + loaded }
    }

    private fun buildTimeline(sessions: List<Session>): List<TimelineRow> {
        val rows = ArrayList<TimelineRow>(sessions.size + 32)
        var lastHour = -1
        var lastEnd = -1L
        val zone = java.time.ZoneId.systemDefault()
        for (s in sessions) {
            val hour = java.time.Instant.ofEpochMilli(s.start).atZone(zone).hour
            if (lastEnd > 0 && s.start - lastEnd >= 5 * 60_000) rows.add(TimelineRow.Gap(lastEnd, s.start))
            if (hour != lastHour) {
                rows.add(TimelineRow.Hour(hour))
                lastHour = hour
            }
            rows.add(TimelineRow.Item(s))
            lastEnd = s.end
        }
        return rows
    }
}
