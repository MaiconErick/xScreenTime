package com.maiconerick.xscreentime.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maiconerick.xscreentime.Schedule
import com.maiconerick.xscreentime.data.AppInfo
import com.maiconerick.xscreentime.data.AppTotal
import com.maiconerick.xscreentime.data.EndReason
import com.maiconerick.xscreentime.data.GameStats
import com.maiconerick.xscreentime.data.History
import com.maiconerick.xscreentime.data.HistoryApp
import com.maiconerick.xscreentime.data.Period
import com.maiconerick.xscreentime.data.PlaySyncStatus
import com.maiconerick.xscreentime.data.Session
import com.maiconerick.xscreentime.data.XpChanges
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Black, surface = Surface)) {
                Root(vm)
            }
        }
    }
}

private enum class Tab(val label: String, val ico: Ico) {
    Overview("Today", Ico.Overview),
    Timeline("Timeline", Ico.Timeline),
    Trends("Trends", Ico.Trends),
    Games("Games", Ico.Games),
    History("History", Ico.History),
}

/** State of the PlayTime upload, shared by the top-bar button and the Games tab. */
private data class UploadUi(val busy: Boolean, val status: PlaySyncStatus?, val hasGames: Boolean, val changes: XpChanges = XpChanges())

@Composable
private fun Root(vm: MainViewModel) {
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }
    val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        if (!hasPermission) PermissionScreen() else MainScreen(vm)
    }
}

@Composable
private fun PermissionScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Wordmark()
        Spacer(Modifier.height(28.dp))
        GlassCard {
            Label("One permission")
            Spacer(Modifier.height(8.dp))
            Text("Allow usage access", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Text(
                "Android already logs which app is on screen. xScreenTime reads that log when you open it and four times " +
                    "a day to archive it. Nothing runs in the background in between.",
                color = TextDim, fontSize = 14.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(20.dp))
            GradientButton("Open settings", Ico.Right, modifier = Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("x", color = Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "ScreenTime", fontSize = 22.sp, fontWeight = FontWeight.Light,
            style = TextStyle(brush = Brush.horizontalGradient(listOf(TextMain, Violet))),
        )
    }
}

@Composable
private fun MainScreen(vm: MainViewModel) {
    val date by vm.date.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val gamePkgs by vm.gamePkgs.collectAsStateWithLifecycle()
    val playSyncing by vm.playSyncing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }
    var jumpToHour by remember { mutableStateOf<Int?>(null) }
    val upload = UploadUi(playSyncing, games?.status, gamePkgs.isNotEmpty(), games?.changes ?: XpChanges())

    Column(Modifier.fillMaxSize()) {
        TopBar(syncing, upload, onUpload = { vm.exportGames(force = true) })
        Box(Modifier.weight(1f)) {
            val d = day
            when (tab) {
                Tab.Overview -> if (d != null) OverviewTab(d, apps, date, vm::setDate, onApp = vm::openApp, onHour = { h ->
                    jumpToHour = h
                    tab = Tab.Timeline
                })
                Tab.Timeline -> if (d != null) TimelineTab(d, apps, date, vm::setDate, jumpToHour, onJumped = { jumpToHour = null }, onApp = vm::openApp)
                Tab.Trends -> trends?.let { tr ->
                    TrendsTab(tr, apps, onDays = vm::setTrendDays, onDay = { picked ->
                        vm.setDate(picked)
                        tab = Tab.Overview
                    }, onApp = vm::openApp)
                }
                Tab.Games -> games?.let { g ->
                    GamesTab(
                        g, apps, gamePkgs, upload,
                        onToggle = vm::toggleGame, onRename = vm::renameGame,
                        onUpload = { vm.exportGames(force = true) }, onApp = vm::openApp,
                    )
                }
                Tab.History -> history?.let { h ->
                    HistoryTab(h, apps, onInterval = { vm.loadHistory(it) }, onSelect = vm::selectPeriod, onApp = vm::openApp)
                }
            }
        }
        NavBar(Tab.entries.map { it.label to it.ico }, tab.ordinal) { tab = Tab.entries[it] }
    }

    detail?.let { dt ->
        BackHandler { vm.closeApp() }
        AppDetailScreen(
            dt, apps, isGame = dt.pkg in gamePkgs,
            onBack = vm::closeApp, onDay = vm::setDate, onToggleGame = { vm.toggleGame(dt.pkg) },
        )
    }
}

@Composable
private fun TopBar(syncing: Boolean, upload: UploadUi, onUpload: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
        Spacer(Modifier.width(8.dp))
        Text(if (syncing) "syncing…" else "v${version.orEmpty()}", color = TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        if (upload.hasGames) UploadPill(upload, onUpload)
    }
}

/** Always-visible "Upload now": the dot shows how the last upload went. */
@Composable
private fun UploadPill(upload: UploadUi, onClick: () -> Unit) {
    val st = upload.status
    val dot = when {
        upload.busy -> Amber
        st == null -> TextFaint
        st.ok -> Green
        else -> Red
    }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(SurfaceHi).border(1.dp, Violet.copy(alpha = 0.35f), shape)
            .clickable(enabled = !upload.busy, onClick = onClick).padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (upload.busy) Spinner(Violet, 14.dp) else Icon(Ico.Upload, Violet, 16.dp)
        Spacer(Modifier.width(7.dp))
        Text(if (upload.busy) "Uploading" else "Upload", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (upload.changes.any) {
            Spacer(Modifier.width(6.dp))
            Icon(Ico.Warning, Amber, 14.dp)
        }
        Spacer(Modifier.width(7.dp))
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
    }
}

@Composable
private fun DateSwitcher(date: LocalDate, onDate: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Ico.Left, size = 34.dp) { onDate(date.minusDays(1)) }
        Text(
            Fmt.day(date),
            color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                DatePickerDialog(ctx, { _, y, m, d -> onDate(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }.padding(vertical = 8.dp),
        )
        RoundButton(Ico.Right, size = 34.dp, enabled = date < LocalDate.now()) { onDate(date.plusDays(1)) }
    }
}

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)

// ---------------------------------------------------------------- Overview

@Composable
private fun OverviewTab(
    day: DayState,
    apps: Map<String, AppInfo>,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    onApp: (String) -> Unit,
    onHour: (Int) -> Unit,
) {
    val s = day.summary
    val top = s.apps.firstOrNull()?.total ?: 1L
    LazyColumn(contentPadding = ListPadding) {
        item(key = "summary", contentType = "summary") {
            GlassCard(padding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 18.dp)) {
                DateSwitcher(date, onDate)
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Spacer(Modifier.height(10.dp))
                    Label("Screen time")
                    BigValue(Fmt.dur(s.total))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("${s.unlocks} unlocks", Cyan, Ico.Lock)
                        if (!s.imported) Chip("${s.sessions.size} sessions", Violet, Ico.Layers)
                        Chip("${s.apps.size} apps", Orange)
                    }
                    Spacer(Modifier.height(18.dp))
                    if (s.imported) {
                        Text("Imported from StayFree: daily totals per app only, no hourly breakdown.", color = Magenta, fontSize = 12.sp)
                    } else {
                        HourlyChart(s.hourly, onHour)
                        Text("Tap an hour to see what was on screen", color = TextFaint, fontSize = 11.sp)
                    }
                }
            }
        }
        item(key = "label", contentType = "label") { SectionLabel("Most used") }
        if (s.apps.isEmpty()) item(key = "empty") { Empty("No usage recorded for this day") }
        items(s.apps, key = { it.pkg }, contentType = { "app" }) { a ->
            AppRow(info = apps[a.pkg], label = apps[a.pkg]?.label ?: a.pkg, value = Fmt.dur(a.total), fraction = a.total.toFloat() / top,
                sub = when {
                    a.longest > 0 -> "${a.sessions}× · longest ${Fmt.dur(a.longest)}"
                    a.sessions > 0 -> "%,d opens".format(a.sessions)
                    else -> null
                }, onClick = { onApp(a.pkg) })
        }
    }
}

/** Icon, name + value, colored bar, small sub line. Used by every list. */
@Composable
private fun AppRow(info: AppInfo?, label: String, value: String, fraction: Float, sub: String?, onClick: () -> Unit) {
    val color = info?.let { Color(it.color) } ?: Violet
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(info, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(value, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            UsageBar(fraction, color)
            sub?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = TextFaint, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Timeline

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineTab(
    day: DayState,
    apps: Map<String, AppInfo>,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    jumpToHour: Int?,
    onJumped: () -> Unit,
    onApp: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var highlight by remember { mutableStateOf<String?>(null) }
    val rows = day.timeline

    fun jumpTo(epochMs: Long) {
        // The session covering that moment, else the first one after it, else the last one.
        val idx = rows.indexOfFirst { it is TimelineRow.Item && it.session.end > epochMs }.takeIf { it >= 0 } ?: rows.lastIndex
        if (idx < 0) return
        highlight = (rows[idx] as? TimelineRow.Item)?.key
        scope.launch { listState.animateScrollToItem((idx - 1).coerceAtLeast(0)) }
    }

    LaunchedEffect(jumpToHour, rows) {
        val h = jumpToHour ?: return@LaunchedEffect
        jumpTo(day.summary.date.atTime(h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        onJumped()
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            GlassCard(padding = PaddingValues(8.dp)) { DateSwitcher(date, onDate) }
        }
        Box(Modifier.weight(1f)) {
            if (rows.isEmpty()) Empty(if (day.summary.imported) "Imported day: StayFree's export has no timeline" else "Nothing on screen this day")
            LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)) {
                rows.forEach { row ->
                    when (row) {
                        is TimelineRow.Hour -> stickyHeader(key = row.key, contentType = "hour") {
                            Row(Modifier.fillMaxWidth().background(Black).padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("%02d:00".format(row.hour), color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                                Spacer(Modifier.width(10.dp))
                                Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Violet.copy(alpha = 0.35f), Color.Transparent))))
                            }
                        }
                        is TimelineRow.Gap -> item(key = row.key, contentType = "gap") {
                            Row(Modifier.padding(start = 58.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(4.dp).clip(CircleShape).background(TextFaint))
                                Spacer(Modifier.width(8.dp))
                                Text("Screen off or home · ${Fmt.dur(row.to - row.from)}", color = TextFaint, fontSize = 11.sp)
                            }
                        }
                        is TimelineRow.Item -> item(key = row.key, contentType = "session") {
                            SessionRow(row.session, apps[row.session.pkg], highlighted = row.key == highlight) { onApp(row.session.pkg) }
                        }
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
                GradientButton("Jump to time", Ico.Clock) {
                    val now = java.time.LocalTime.now()
                    TimePickerDialog(ctx, { _, h, m ->
                        jumpTo(day.summary.date.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }, now.hour, now.minute, true).show()
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, info: AppInfo?, highlighted: Boolean, onClick: () -> Unit) {
    val color = info?.let { Color(it.color) } ?: Violet
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (highlighted) Modifier.background(color.copy(alpha = 0.14f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(46.dp)) {
            Text(Fmt.time(s.start), color = TextMain, fontSize = 12.5.sp)
            Text(if (s.live) "now" else Fmt.time(s.end), color = TextFaint, fontSize = 11.sp)
        }
        Box(
            Modifier.width(3.dp).height((24 + (s.duration / 60_000).coerceAtMost(30).toInt()).dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.3f)))),
        )
        Spacer(Modifier.width(12.dp))
        AppIcon(info, 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(info?.label ?: s.pkg, color = TextMain, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s.live) Chip("live", Green) else Text(Fmt.dur(s.duration), color = TextDim, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- Trends

private val TrendRanges = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "Year")

@Composable
private fun TrendsTab(tr: TrendsState, apps: Map<String, AppInfo>, onDays: (Int) -> Unit, onDay: (LocalDate) -> Unit, onApp: (String) -> Unit) {
    val tracked = tr.totals.filter { it.total > 0 }
    val avg = if (tracked.isEmpty()) 0L else tracked.sumOf { it.total } / tracked.size
    val top = tr.apps.firstOrNull()?.total ?: 1L
    LazyColumn(contentPadding = ListPadding) {
        item(key = "chart", contentType = "chart") {
            GlassCard {
                Segmented(TrendRanges.map { it.second }, TrendRanges.indexOfFirst { it.first == tr.days }.coerceAtLeast(0), Modifier.fillMaxWidth()) {
                    onDays(TrendRanges[it].first)
                }
                Spacer(Modifier.height(16.dp))
                Label("Daily average")
                BigValue(Fmt.dur(avg))
                Spacer(Modifier.height(4.dp))
                Text("Total ${Fmt.dur(tracked.sumOf { it.total })} over ${tracked.size} days", color = TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                DayBars(tr.totals, selected = LocalDate.now(), onDay = onDay)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(2.dp).background(Orange.copy(alpha = 0.7f)))
                    Spacer(Modifier.width(6.dp))
                    Text("average · tap a day to open it", color = TextFaint, fontSize = 11.sp)
                }
            }
        }
        item(key = "label", contentType = "label") { SectionLabel("Top apps", TrendRanges.firstOrNull { it.first == tr.days }?.second?.lowercase()) }
        items(tr.apps, key = { it.pkg }, contentType = { "app" }) { a ->
            AppRow(apps[a.pkg], apps[a.pkg]?.label ?: a.pkg, Fmt.dur(a.total), a.total.toFloat() / top,
                "${Fmt.dur(a.total / tr.days)}/day · ${a.sessions} sessions") { onApp(a.pkg) }
        }
    }
}

// ---------------------------------------------------------------- Games

@Composable
private fun GamesTab(
    g: GamesState,
    apps: Map<String, AppInfo>,
    gamePkgs: Set<String>,
    upload: UploadUi,
    onToggle: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onUpload: () -> Unit,
    onApp: (String) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<GameStats?>(null) }
    val top = g.games.firstOrNull()?.total?.coerceAtLeast(1L) ?: 1L
    if (picking) {
        BackHandler { picking = false }
        GamePicker(g.candidates, apps, gamePkgs, onToggle, onDone = { picking = false })
        return
    }
    LazyColumn(contentPadding = ListPadding) {
        item(key = "head", contentType = "head") {
            GlassCard {
                Label("Play time · all time")
                BigValue(Fmt.dur(g.games.sumOf { it.total }))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("${g.games.size} games", Orange, Ico.Games)
                    Chip("${g.games.sumOf { it.plays }} plays", Violet, Ico.Layers)
                }
                if (g.changes.any) {
                    Spacer(Modifier.height(14.dp))
                    EditsNotice(g.changes)
                }
                Spacer(Modifier.height(if (g.changes.any) 10.dp else 18.dp))
                UploadStatus(upload)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton(if (upload.busy) "Uploading…" else "Upload now", Ico.Upload, upload.busy, Modifier.weight(1f), onUpload)
                    GhostButton("Choose", Ico.Plus) { picking = true }
                }
            }
        }
        item(key = "label", contentType = "label") { SectionLabel("Your games", "tap to rename") }
        if (g.games.isEmpty()) item(key = "empty") { Empty("No games yet. Tap “Games” to pick them, or mark one from an app's page.") }
        items(g.games, key = { it.pkg }, contentType = { "game" }) { gs ->
            GameRow(gs, apps[gs.pkg], gs.total.toFloat() / top, g.changes.byPkg[gs.pkg], onClick = { renaming = gs }, onIcon = { onApp(gs.pkg) })
        }
    }
    renaming?.let { gs ->
        var text by remember(gs.pkg) { mutableStateOf(gs.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Name in PlayTime", fontWeight = FontWeight.Light) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Text("Leave empty to use the app's own name", color = TextFaint, fontSize = 11.sp)
                    g.changes.byPkg[gs.pkg]?.let { (e, d) ->
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Ico.Warning, Amber, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                changeText(e, d) + " in xPlayTime. Your version is kept and xScreenTime won't overwrite it. " +
                                    "Totals here still use xScreenTime's own numbers.",
                                color = TextDim, fontSize = 12.sp, lineHeight = 16.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton({ onRename(gs.pkg, text); renaming = null }) { Text("Save", color = Violet) } },
            dismissButton = { TextButton({ onToggle(gs.pkg); renaming = null }) { Text("Not a game", color = Red) } },
            containerColor = SurfaceHi,
        )
    }
}

private fun changeText(edited: Int, deleted: Int): String = listOfNotNull(
    edited.takeIf { it > 0 }?.let { "$it ${if (it == 1) "session was" else "sessions were"} edited" },
    deleted.takeIf { it > 0 }?.let { "$it ${if (it == 1) "session was" else "sessions were"} deleted" },
).joinToString(" and ").replaceFirstChar { it.uppercase() }

/** Amber card: the PlayTime file has changes made in xPlayTime that the merge is keeping. */
@Composable
private fun EditsNotice(c: XpChanges) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Amber.copy(alpha = 0.08f)).border(1.dp, Amber.copy(alpha = 0.35f), shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ico.Warning, Amber, 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Changed in xPlayTime", color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("${c.summary()} · kept, never overwritten", color = TextDim, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun UploadStatus(upload: UploadUi) {
    val st = upload.status
    val (color, title) = when {
        upload.busy -> Amber to "Uploading to Dropbox…"
        st == null -> TextFaint to "Not uploaded yet"
        st.ok -> Green to "Uploaded ${Fmt.ago(st.at)}"
        else -> Red to "Upload failed ${Fmt.ago(st.at)}"
    }
    val next = remember { Schedule.next() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            st?.message?.takeIf { it.isNotBlank() && !upload.busy }?.let {
                Text(it, color = TextDim, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("Next automatic upload ${Fmt.ago(next.toInstant().toEpochMilli())}", color = TextFaint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GameRow(gs: GameStats, info: AppInfo?, fraction: Float, changed: Pair<Int, Int>?, onClick: () -> Unit, onIcon: () -> Unit) {
    val color = info?.let { Color(it.color) } ?: Violet
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onIcon)) { AppIcon(info, 48.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(gs.name, color = TextMain, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (changed != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Ico.Warning, Amber, 15.dp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(Fmt.dur(gs.total), color = color, fontSize = 17.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.height(6.dp))
            UsageBar(fraction, color)
            Spacer(Modifier.height(4.dp))
            Text(
                "${gs.plays} plays" + if (gs.lastPlayed > 0) " · last played ${Fmt.ago(gs.lastPlayed)}" else "",
                color = TextFaint, fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun GamePicker(candidates: List<AppTotal>, apps: Map<String, AppInfo>, gamePkgs: Set<String>, onToggle: (String) -> Unit, onDone: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(candidates, query, apps) {
        if (query.isBlank()) candidates else candidates.filter { (apps[it.pkg]?.label ?: it.pkg).contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pick your games", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Light)
                Text("${gamePkgs.size} selected · apps used this year", color = TextDim, fontSize = 12.sp)
            }
            GradientButton("Done", Ico.Check, onClick = onDone)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SurfaceHi).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ico.Search, TextDim, 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search apps", color = TextFaint, fontSize = 14.sp)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = TextMain, fontSize = 14.sp), cursorBrush = SolidColor(Violet), modifier = Modifier.fillMaxWidth())
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            items(shown, key = { it.pkg }, contentType = { "candidate" }) { a ->
                val info = apps[a.pkg]
                val marked = a.pkg in gamePkgs
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                        .then(if (marked) Modifier.background(Violet.copy(alpha = 0.10f)).border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(16.dp)) else Modifier)
                        .clickable { onToggle(a.pkg) }
                        .padding(vertical = 9.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(info, 38.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info?.label ?: a.pkg, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Fmt.dur(a.total)} this year", color = TextFaint, fontSize = 11.5.sp)
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .then(if (marked) Modifier.background(AccentBrush) else Modifier.border(1.5.dp, TextFaint, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (marked) Icon(Ico.Check, Black, 16.dp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- History

@Composable
private fun HistoryTab(h: HistoryState, apps: Map<String, AppInfo>, onInterval: (Int) -> Unit, onSelect: (Int) -> Unit, onApp: (String) -> Unit) {
    val sel = h.periods.getOrNull(h.selected)
    val top = sel?.apps?.firstOrNull()?.foreground ?: 1L
    val months = h.interval == History.MONTHS
    LazyColumn(contentPadding = ListPadding) {
        item(key = "chart", contentType = "chart") {
            GlassCard {
                Segmented(listOf("Months", "Years"), if (months) 0 else 1, Modifier.fillMaxWidth()) {
                    onInterval(if (it == 0) History.MONTHS else History.YEARS)
                }
                Spacer(Modifier.height(16.dp))
                Label(sel?.let { periodLabel(it) } ?: if (months) "Monthly totals" else "Yearly totals")
                BigValue(sel?.let { Fmt.dur(it.total) } ?: "—")
                sel?.let { p ->
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.screenOnTime?.let { Chip("screen on ${Fmt.dur(it)}", Cyan) }
                        p.unlockCount?.let { Chip("%,d unlocks".format(it), Violet, Ico.Lock) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                PeriodBars(h.periods.map { it.total }, h.periods.map { shortLabel(it, months) }, h.selected, Violet, onSelect)
                Spacer(Modifier.height(4.dp))
                Text("Android's own long-term totals · per app, no sessions", color = TextFaint, fontSize = 11.sp)
            }
        }
        item(key = "label", contentType = "label") { SectionLabel("Apps") }
        if (sel == null) item(key = "empty") { Empty("Android has no long-term totals yet") }
        else items(sel.apps, key = { it.pkg }, contentType = { "app" }) { a ->
            AppRow(apps[a.pkg], apps[a.pkg]?.label ?: a.pkg, Fmt.dur(a.foreground), a.foreground.toFloat() / top, historySub(a)) { onApp(a.pkg) }
        }
    }
}

private fun historySub(a: HistoryApp): String = buildList {
    a.launches?.let { add("%,d launches".format(it)) }
    if (a.lastUsed > 0) add("last used ${Fmt.ago(a.lastUsed)}")
    if (a.foregroundService > 60_000) add("background ${Fmt.dur(a.foregroundService)}")
}.joinToString(" · ")

private val rangeFmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
private val monthShort = java.time.format.DateTimeFormatter.ofPattern("MMM", java.util.Locale.ENGLISH)
private val yearShort = java.time.format.DateTimeFormatter.ofPattern("yyyy", java.util.Locale.ENGLISH)

private fun zdt(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())

private fun periodLabel(p: Period): String = "${rangeFmt.format(zdt(p.start))} – ${rangeFmt.format(zdt(p.end))}"

private fun shortLabel(p: Period, months: Boolean): String = (if (months) monthShort else yearShort).format(zdt(p.start))

// ---------------------------------------------------------------- App detail

@Composable
private fun AppDetailScreen(
    dt: AppDetailState,
    apps: Map<String, AppInfo>,
    isGame: Boolean,
    onBack: () -> Unit,
    onDay: (LocalDate) -> Unit,
    onToggleGame: () -> Unit,
) {
    val info = apps[dt.pkg]
    val color = info?.let { Color(it.color) } ?: Violet
    val dayTotal = dt.sessions.sumOf { it.duration }
    val used = dt.totals.filter { it.total > 0 }
    val avg = if (used.isEmpty()) 0L else used.sumOf { it.total } / used.size
    Box(Modifier.fillMaxSize().background(Black).systemBarsPadding()) {
        LazyColumn(contentPadding = ListPadding) {
            item(key = "head", contentType = "head") {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Ico.Back, onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    if (!dt.pkg.startsWith("stayfree:")) {
                        Chip(if (isGame) "Game · tracked" else "Mark as game", if (isGame) Green else Violet, if (isGame) Ico.Check else Ico.Games, onToggleGame)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(info, 64.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(info?.label ?: dt.pkg, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dt.pkg, color = TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                GlassCard {
                    Label(Fmt.day(dt.date))
                    BigValue(Fmt.dur(dayTotal), color)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("${dt.sessions.size} sessions", Violet, Ico.Layers)
                        Chip("avg ${Fmt.dur(avg)}/day", Orange)
                    }
                    Spacer(Modifier.height(16.dp))
                    DayBars(dt.totals, selected = dt.date, color = color, height = 110.dp, onDay = onDay)
                    Text("Last 14 days · tap a day", color = TextFaint, fontSize = 11.sp)
                }
                SectionLabel("Sessions")
            }
            if (dt.sessions.isEmpty()) item(key = "empty") { Empty("Not used this day") }
            items(dt.sessions.asReversed(), key = { it.start }, contentType = { "session" }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Fmt.time(s.start)} – ${if (s.live) "now" else Fmt.time(s.end)}", color = TextMain, fontSize = 14.sp)
                        EndReasonLine(s, apps)
                    }
                    Text(Fmt.dur(s.duration), color = TextDim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EndReasonLine(s: Session, apps: Map<String, AppInfo>) {
    val next = s.nextPkg?.let { apps[it] }
    val text = when {
        s.live -> "Still open"
        else -> when (s.endReason) {
            EndReason.SCREEN_OFF -> "Screen turned off"
            EndReason.HOME -> "Went to home screen"
            EndReason.SYSTEM_UI -> "Recents / notification shade"
            EndReason.SHUTDOWN -> "Phone shut down"
            EndReason.CONTINUES -> "Continues after midnight"
            EndReason.APP -> "Switched to ${next?.label ?: s.nextPkg}"
            else -> "Left (reason not logged)"
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
        if (s.endReason == EndReason.APP && !s.live) {
            AppIcon(next, 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (s.live) Green else TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
