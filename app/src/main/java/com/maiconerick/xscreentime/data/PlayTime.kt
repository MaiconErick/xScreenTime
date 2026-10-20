package com.maiconerick.xscreentime.data

import android.app.usage.UsageStatsManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One play session as written to the PlayTime db. [played] is foreground time only, so it can be < end - start. */
@Immutable
data class PlayRow(
    val pkg: String,
    val name: String,
    val start: Long,
    val end: Long,
    val played: Long,
    val endReason: String?,
    /** Source: 's' own/Tasker sessions, 'd' StayFree day, 'g' Android monthly gap. */
    val kind: Char = 's',
) {
    /** Stable identity across exports, so edits made in xPlayTime can be recognised. */
    val key: String get() = "$kind:$pkg:$start"
}

@Immutable
data class GameStats(val pkg: String, val name: String, val total: Long, val plays: Int, val lastPlayed: Long)

@Immutable
data class PlaySyncStatus(val at: Long, val ok: Boolean, val message: String)

/** What the last merge found changed in xPlayTime: keys of edited / deleted rows, and rows it added. */
@Immutable
data class XpChanges(val edited: Set<String> = emptySet(), val deleted: Set<String> = emptySet(), val added: Int = 0) {
    val any get() = edited.isNotEmpty() || deleted.isNotEmpty() || added > 0

    /** Per package: (edited, deleted). Keys look like "s:<pkg>:<start>". */
    val byPkg: Map<String, Pair<Int, Int>> by lazy {
        val e = edited.groupingBy { it.split(':')[1] }.eachCount()
        val d = deleted.groupingBy { it.split(':')[1] }.eachCount()
        (e.keys + d.keys).associateWith { (e[it] ?: 0) to (d[it] ?: 0) }
    }

    fun summary(): String = buildList {
        if (edited.isNotEmpty()) add("${edited.size} edited")
        if (deleted.isNotEmpty()) add("${deleted.size} deleted")
        if (added > 0) add("$added added")
    }.joinToString(" · ")
}

/**
 * Turns the time spent in apps marked as games into the PlayTime sqlite format shared by the other
 * devices' trackers (xPlayTimeTracker / Tasker) and uploads it to Dropbox for the xPlayTime viewer.
 *
 * Our rows come from our own sessions where we have them, StayFree's daily totals before that, and
 * the old Tasker sessions before StayFree; each day comes from exactly one source.
 *
 * The file is shared with xPlayTime, which can edit, delete and add rows. So every export first
 * downloads the current file and merges into it (see [merge]) instead of overwriting it, and the
 * upload only goes through if nobody changed the file in between.
 */
object PlayTime {
    /** Short trips out of the game (a WhatsApp reply mid-match) don't split a play session. */
    private const val MERGE_GAP_MS = 120_000L
    private const val MIN_PLAY_MS = 10_000L
    private const val PREFS = "playtime"
    private const val KEY_HASH = "hash"
    private const val KEY_AT = "sync_at"
    private const val KEY_OK = "sync_ok"
    private const val KEY_MSG = "sync_msg"
    private const val KEY_XP_EDITED = "xp_edited"
    private const val KEY_XP_DELETED = "xp_deleted"
    private const val KEY_XP_ADDED = "xp_added"
    const val USER = "MaiconErick"

    private val mutex = Mutex()
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** The S23 Ultra keeps the file name the Tasker profile used; other devices get their own file. */
    private val isS23U get() = Build.MODEL.startsWith("SM-S918")
    val device: String get() = if (isS23U) "S23U" else Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "")
    val remotePath: String
        get() = if (isS23U) "/PlayTime/DB/playtime_s23u.db" else "/PlayTime/DB/PlayTime_${device}_xScreenTime.db"

    private data class Seg(val pkg: String, val start: Long, val end: Long, val reason: String?)

    suspend fun rows(ctx: Context): List<PlayRow> = withContext(Dispatchers.IO) {
        val dao = UsageDb.get(ctx).dao()
        val games = dao.games()
        if (games.isEmpty()) return@withContext emptyList()
        val pkgs = games.map { it.pkg }
        val names = games.associate { it.pkg to (it.name ?: AppInfoCache.load(ctx, it.pkg).label) }

        val firstSessionDay = Repo.firstTrackedDay(ctx)?.toEpochDay() ?: Long.MAX_VALUE
        val firstImportedDay = minOf(dao.firstImportedDay() ?: Long.MAX_VALUE, firstSessionDay)

        val sessions = dao.sessionsForApps(pkgs)
        val segs = ArrayList<Seg>()
        for (s in sessions) splitAtMidnight(s.pkg, s.start, s.end, reasonText(ctx, s), segs)
        for (p in dao.legacyPlays(pkgs)) {
            if (dayOf(p.start) < firstImportedDay) splitAtMidnight(p.pkg, p.start, p.end, null, segs)
        }

        val rows = ArrayList<PlayRow>()
        segs.sortWith(compareBy({ it.pkg }, { it.start }))
        var cur: PlayRow? = null
        for (s in segs) {
            val c = cur
            if (c != null && c.pkg == s.pkg && s.start - c.end <= MERGE_GAP_MS && dayOf(s.start) == dayOf(c.start)) {
                cur = c.copy(end = maxOf(c.end, s.end), played = c.played + (s.end - s.start), endReason = s.reason)
            } else {
                c?.let(rows::add)
                cur = PlayRow(s.pkg, names.getValue(s.pkg), s.start, s.end, s.end - s.start, s.reason)
            }
        }
        cur?.let(rows::add)

        // StayFree only knows the daily total, so each day becomes one row starting at midnight.
        for (d in dao.importedForApps(pkgs, firstSessionDay)) {
            val start = LocalDate.ofEpochDay(d.day).atStartOfDay(zone).toInstant().toEpochMilli()
            rows.add(PlayRow(d.pkg, names.getValue(d.pkg), start, start + d.ms, d.ms, "StayFree daily total", 'd'))
        }
        rows += gapRows(ctx, pkgs, names, sessions)
        rows.filter { it.played >= MIN_PLAY_MS }.sortedBy { it.start }
    }

    /**
     * Days between the end of the StayFree export and our first session have no daily data, but
     * Android's monthly bucket covering them still has per-app totals. What that bucket holds
     * beyond our sessions and the imported days inside it is the gap's play time.
     */
    private suspend fun gapRows(
        ctx: Context,
        pkgs: List<String>,
        names: Map<String, String>,
        sessions: List<SessionEntity>,
    ): List<PlayRow> {
        val dao = UsageDb.get(ctx).dao()
        val lastImported = dao.lastImportedDay() ?: return emptyList()
        val gapEnd = dao.firstStart() ?: return emptyList()
        val gapStart = LocalDate.ofEpochDay(lastImported + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (gapEnd - gapStart < 3_600_000L) return emptyList()

        val imported = dao.importedForApps(pkgs, Long.MAX_VALUE).groupBy { it.pkg }
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val buckets = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, gapStart, gapEnd) }
            .getOrDefault(emptyList())
            .filter { it.packageName in pkgs && it.firstTimeStamp < gapEnd && it.lastTimeStamp > gapStart }
        return buckets.groupBy { it.packageName to it.firstTimeStamp }.mapNotNull { (key, stats) ->
            val (pkg, begin) = key
            val end = stats.maxOf { it.lastTimeStamp }
            val known = sessions.filter { it.pkg == pkg }.sumOf { (minOf(it.end, end) - maxOf(it.start, begin)).coerceAtLeast(0L) } +
                imported[pkg].orEmpty().sumOf { d ->
                    // A day only partly inside the bucket counts by the share of it that is.
                    val ds = LocalDate.ofEpochDay(d.day).atStartOfDay(zone).toInstant().toEpochMilli()
                    val overlap = (minOf(ds + 86_400_000L, end) - maxOf(ds, begin)).coerceAtLeast(0L)
                    d.ms * overlap / 86_400_000L
                }
            val rest = stats.sumOf { it.totalTimeInForeground } - known
            if (rest < MIN_PLAY_MS) return@mapNotNull null
            val start = maxOf(begin, gapStart)
            PlayRow(pkg, names.getValue(pkg), start, start + rest, rest, "Android monthly total (${fmt(start).take(10)} – ${fmt(gapEnd).take(10)})", 'g')
        }
    }

    fun stats(rows: List<PlayRow>, games: List<GameEntity>, names: Map<String, String>): List<GameStats> {
        val byPkg = rows.groupBy { it.pkg }
        return games.map { g ->
            val list = byPkg[g.pkg].orEmpty()
            GameStats(g.pkg, names[g.pkg] ?: g.pkg, list.sumOf { it.played }, list.size, list.maxOfOrNull { it.end } ?: 0L)
        }.sortedByDescending { it.total }
    }

    /** From the last successful merge; only refreshed when an upload happens. */
    fun changes(ctx: Context): XpChanges {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return XpChanges(
            p.getStringSet(KEY_XP_EDITED, null).orEmpty().toSet(),
            p.getStringSet(KEY_XP_DELETED, null).orEmpty().toSet(),
            p.getInt(KEY_XP_ADDED, 0),
        )
    }

    fun status(ctx: Context): PlaySyncStatus? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0L).takeIf { it > 0 } ?: return null
        return PlaySyncStatus(at, p.getBoolean(KEY_OK, false), p.getString(KEY_MSG, "").orEmpty())
    }

    /** Columns xPlayTime can change. A row whose fingerprint over them no longer matches was edited there. */
    private val EDITABLE = listOf("GameName", "TimeStarted_UNIX", "TimeEnded_UNIX", "TimePlayed", "Platform", "Emulator", "User", "Device")
    private val COLUMNS = setOf(
        "GameName", "TimeStarted", "TimeEnded", "TimeStarted_UNIX", "TimeEnded_UNIX", "TimePlayed", "Platform", "Emulator",
        "Pkg", "User", "Device", "IsOpen", "EndReason", "ArtPath", "XstKey", "XstHash",
    )

    private fun fingerprint(v: Map<String, Any?>): String = EDITABLE.joinToString("\u001F") { v[it]?.toString().orEmpty() }.hashCode().toString()

    private class Merged(
        val rows: List<Map<String, Any?>>,
        /** Keys of every row of ours in the file, so a missing one next time means it was deleted. */
        val written: Set<String>,
        /** Keys deleted in xPlayTime; never written again. */
        val deleted: Set<String>,
        val edited: Set<String>,
        val added: Int,
    )

    /** Merges our rows into the file on Dropbox and uploads it when our rows changed (or always, when [force]). */
    suspend fun sync(ctx: Context, force: Boolean = false): PlaySyncStatus? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val rows = rows(ctx)
            if (rows.isEmpty()) return@withContext status(ctx)
            val hash = (rows.hashCode().toLong() * 31 + remotePath.hashCode()).toString()
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!force && prefs.getString(KEY_HASH, null) == hash) return@withContext status(ctx)

            val remote = File(ctx.cacheDir, "playtime_remote.db")
            var status: PlaySyncStatus? = null
            var merged: Merged? = null
            for (attempt in 1..3) {
                remote.delete()
                val dl = Dropbox.download(remotePath, remote)
                if (dl is Dropbox.Download.Failed) {
                    status = PlaySyncStatus(System.currentTimeMillis(), false, dl.error)
                    break
                }
                val found = dl as? Dropbox.Download.Found
                val m = merge(rows, if (found != null) remote else null)
                val file = write(ctx, m)
                when (val up = Dropbox.upload(file, remotePath, found?.rev)) {
                    Dropbox.Upload.Ok -> {
                        status = PlaySyncStatus(System.currentTimeMillis(), true, okMessage(m))
                        merged = m
                        break
                    }
                    // xPlayTime saved the file after our download: fetch it again and re-merge.
                    Dropbox.Upload.Conflict -> Log.i("PlayTime", "conflict on attempt $attempt, merging again")
                    is Dropbox.Upload.Failed -> {
                        status = PlaySyncStatus(System.currentTimeMillis(), false, up.error)
                        break
                    }
                }
            }
            val result = status ?: PlaySyncStatus(System.currentTimeMillis(), false, "The file kept changing on Dropbox; will retry")
            prefs.edit().apply {
                if (result.ok) putString(KEY_HASH, hash)
                merged?.let {
                    putStringSet(KEY_XP_EDITED, it.edited).putStringSet(KEY_XP_DELETED, it.deleted).putInt(KEY_XP_ADDED, it.added)
                }
                putLong(KEY_AT, result.at).putBoolean(KEY_OK, result.ok).putString(KEY_MSG, result.message)
            }.apply()
            Log.i("PlayTime", "sync: ${result.message}")
            result
        }
    }

    private fun okMessage(m: Merged): String {
        val hours = m.rows.sumOf { (it["TimePlayed"] as? Long) ?: 0L } / 3600.0
        val kept = buildList {
            if (m.edited.isNotEmpty()) add("${m.edited.size} edited")
            if (m.added > 0) add("${m.added} added")
            if (m.deleted.isNotEmpty()) add("${m.deleted.size} deleted")
        }
        return "%d plays · %.1f h → %s".format(m.rows.size, hours, remotePath.substringAfterLast('/')) +
            if (kept.isEmpty()) "" else " · kept xPlayTime changes: ${kept.joinToString(", ")}"
    }

    /**
     * Three cases decide each row of the downloaded file:
     * - no XstKey: added in xPlayTime → kept as is;
     * - XstKey but its fingerprint no longer matches: edited in xPlayTime → kept, and ours for that key is dropped;
     * - one of ours that was in the file last time but is gone now: deleted in xPlayTime → remembered, not re-added.
     * Every other row is replaced by our fresh version.
     */
    private fun merge(ours: List<PlayRow>, remote: File?): Merged {
        val fresh = ours.map(::toValues)
        val all = fresh.map { it["XstKey"] as String }.toSet()
        if (remote == null) return Merged(fresh, all, emptySet(), emptySet(), 0)
        SQLiteDatabase.openDatabase(remote.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            val cols = db.rawQuery("PRAGMA table_info(PlayTime)", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
            // Files written before the merge existed (v0.4 and older) can't hold edits: S23U wasn't in xPlayTime yet.
            if ("XstKey" !in cols) return Merged(fresh, all, emptySet(), emptySet(), 0)
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            val written = if ("XstWritten" in tables) keys(db, "XstWritten") else emptySet()
            val deleted = (if ("XstDeleted" in tables) keys(db, "XstDeleted") else emptySet()).toMutableSet()

            val keep = ArrayList<Map<String, Any?>>()
            val present = HashSet<String>()
            val edited = HashSet<String>()
            var added = 0
            db.rawQuery("SELECT * FROM PlayTime", null).use { c ->
                while (c.moveToNext()) {
                    val v = HashMap<String, Any?>()
                    for (i in 0 until c.columnCount) {
                        val n = c.getColumnName(i)
                        if (n !in COLUMNS) continue
                        v[n] = when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                    }
                    val key = v["XstKey"] as? String
                    if (key == null) {
                        keep += v
                        added++
                        continue
                    }
                    present += key
                    // The stale XstHash stays on the row, so it keeps counting as edited on every later export.
                    if (fingerprint(v) != v["XstHash"]) {
                        keep += v
                        edited += key
                    }
                }
            }
            deleted += written - present
            val mine = fresh.filter { val k = it["XstKey"] as String; k !in edited && k !in deleted }
            return Merged(keep + mine, mine.map { it["XstKey"] as String }.toSet() + edited, deleted, edited, added)
        }
    }

    private fun keys(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("SELECT Key FROM $table", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun toValues(r: PlayRow): Map<String, Any?> {
        val v = linkedMapOf<String, Any?>(
            "GameName" to r.name,
            "TimeStarted" to fmt(r.start),
            "TimeEnded" to fmt(r.end),
            "TimeStarted_UNIX" to r.start / 1000,
            "TimeEnded_UNIX" to r.end / 1000,
            "TimePlayed" to r.played / 1000,
            "Platform" to "Android",
            "Emulator" to null,
            "Pkg" to r.pkg,
            "User" to USER,
            "Device" to device,
            "IsOpen" to 0L,
            "EndReason" to r.endReason,
            "XstKey" to r.key,
        )
        v["XstHash"] = fingerprint(v)
        return v
    }

    /**
     * Same PlayTime layout as xPlayTimeTracker's db (which xPlayTime and xGameLauncher read), plus
     * XstKey/XstHash columns and two key tables that xPlayTime never looks at.
     */
    private fun write(ctx: Context, m: Merged): File {
        val file = File(ctx.cacheDir, "playtime_export.db")
        file.delete()
        File(file.path + "-journal").delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """CREATE TABLE PlayTime(
                    ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    GameName TEXT, TimeStarted TEXT, TimeEnded TEXT,
                    TimeStarted_UNIX INTEGER, TimeEnded_UNIX INTEGER, TimePlayed INTEGER,
                    Platform TEXT, Emulator TEXT, Pkg TEXT, User TEXT, Device TEXT,
                    IsOpen INTEGER DEFAULT 1, EndReason TEXT, ArtPath TEXT,
                    XstKey TEXT, XstHash TEXT)""",
            )
            db.execSQL("CREATE TABLE XstWritten(Key TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE XstDeleted(Key TEXT PRIMARY KEY)")
            db.beginTransaction()
            try {
                // IDs follow start time, as the other trackers' dbs do.
                for (v in m.rows.sortedBy { (it["TimeStarted_UNIX"] as? Long) ?: 0L }) {
                    db.insert("PlayTime", null, ContentValues().apply {
                        v.forEach { (k, x) ->
                            when (x) {
                                null -> putNull(k)
                                is Long -> put(k, x)
                                is Double -> put(k, x)
                                else -> put(k, x.toString())
                            }
                        }
                    })
                }
                m.written.forEach { db.insert("XstWritten", null, ContentValues().apply { put("Key", it) }) }
                m.deleted.forEach { db.insert("XstDeleted", null, ContentValues().apply { put("Key", it) }) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return file
    }

    private fun splitAtMidnight(pkg: String, start: Long, end: Long, reason: String?, out: MutableList<Seg>) {
        var t = start
        while (t < end) {
            val nextDay = LocalDate.ofEpochDay(dayOf(t) + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (end <= nextDay) {
                out.add(Seg(pkg, t, end, reason))
                break
            }
            out.add(Seg(pkg, t, nextDay, "Midnight split"))
            t = nextDay
        }
    }

    private fun reasonText(ctx: Context, s: SessionEntity): String? = when (s.endReason) {
        EndReason.SCREEN_OFF -> "Screen off"
        EndReason.HOME -> "Home"
        EndReason.SYSTEM_UI -> "Recents / shade"
        EndReason.SHUTDOWN -> "Shutdown"
        EndReason.APP -> "Switched to ${s.nextPkg?.let { AppInfoCache.load(ctx, it).label } ?: "another app"}"
        else -> null
    }

    private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
    private fun fmt(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms).atZone(zone))
}
