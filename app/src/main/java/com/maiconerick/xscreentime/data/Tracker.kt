package com.maiconerick.xscreentime.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Converts the system's own usage event log into foreground sessions and archives them.
 *
 * Android already records every activity resume/pause, so nothing of ours has to run in the
 * background to "watch" the screen. The system only keeps raw events for roughly a week, so we
 * copy them into Room whenever the app is opened and from a twice-daily worker.
 */
object Tracker {
    /** Two foreground stretches of the same app separated by less than this count as one session. */
    private const val MERGE_GAP_MS = 3_000L
    private const val MIN_SESSION_MS = 1_000L
    private const val FIRST_IMPORT_LOOKBACK_MS = 30L * 24 * 3600_000
    private const val PREFS = "tracker"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_IMPORT_VERSION = "import_version"
    /** Bump when session-building logic changes; stored sessions are rebuilt from the system log. */
    private const val IMPORT_VERSION = 3
    private const val PENDING_WINDOW_MS = 10_000L

    private val mutex = Mutex()

    /** The app currently in the foreground as of the last sync (not yet persisted). */
    @Volatile var openSession: SessionEntity? = null
        private set

    fun hasPermission(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun sync(ctx: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasPermission(ctx)) return@withContext
            Importer.importPending(ctx)
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val dao = UsageDb.get(ctx).dao()
            val now = System.currentTimeMillis()
            if (prefs.getInt(KEY_IMPORT_VERSION, 1) < IMPORT_VERSION) {
                // v1 dropped sessions of apps that switch between their own activities; v2 had no end
                // reasons. Rebuild everything the system log still has.
                dao.clearSessions()
                prefs.edit().remove(KEY_CURSOR).putInt(KEY_IMPORT_VERSION, IMPORT_VERSION).apply()
            }
            val cursor = prefs.getLong(KEY_CURSOR, now - FIRST_IMPORT_LOOKBACK_MS)

            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val events = usm.queryEvents(cursor, now)
            val e = UsageEvents.Event()

            val closed = ArrayList<SessionEntity>()
            val unlocks = ArrayList<UnlockEntity>()
            val home = AppInfoCache.ignoredPackages(ctx) - setOf("com.android.systemui", "android")
            var last: SessionEntity? = dao.lastSession()
            var curPkg: String? = null
            // Track every class currently resumed for the active package. Apps like MLBB switch
            // between multiple activities (splash -> Unity -> overlay) while staying in the
            // foreground; only when ALL of them have paused is the session actually over.
            val activeClasses = HashSet<String>()
            var curStart = 0L
            // A session closed by PAUSED doesn't know yet what replaced it; the next event decides.
            var pendingKey: Pair<String, Long>? = null
            var pendingAt = 0L

            fun setReason(key: Pair<String, Long>, reason: Int, next: String?) {
                val idx = closed.indexOfLast { it.pkg == key.first && it.start == key.second }
                if (idx >= 0) closed[idx] = closed[idx].copy(endReason = reason, nextPkg = next)
                if (last?.pkg == key.first && last?.start == key.second) last = last?.copy(endReason = reason, nextPkg = next)
            }

            fun reasonFor(nextPkg: String): Int = when (nextPkg) {
                in home -> EndReason.HOME
                "com.android.systemui" -> EndReason.SYSTEM_UI
                else -> EndReason.APP
            }

            fun resolvePending(t: Long, reason: Int, next: String?) {
                val key = pendingKey ?: return
                pendingKey = null
                val inTime = t - pendingAt <= PENDING_WINDOW_MS
                // The same app coming back means something transient (dialog, shade) covered it.
                if (!inTime || next == key.first) setReason(key, EndReason.UNKNOWN, null)
                else setReason(key, reason, next)
            }

            fun close(at: Long, reason: Int, next: String?) {
                val pkg = curPkg ?: return
                curPkg = null
                if (at - curStart < MIN_SESSION_MS) return
                val prev = last
                val entity = if (prev != null && prev.pkg == pkg && curStart - prev.end in 0..MERGE_GAP_MS) {
                    // Continuation of the previous stretch (e.g. switching activities inside one app).
                    prev.copy(end = at, endReason = reason, nextPkg = next).also { merged ->
                        val idx = closed.indexOfLast { it.pkg == prev.pkg && it.start == prev.start }
                        if (idx >= 0) closed[idx] = merged else closed.add(merged)
                    }
                } else {
                    SessionEntity(pkg = pkg, start = curStart, end = at, endReason = reason, nextPkg = next).also { closed.add(it) }
                }
                last = entity
                if (reason == EndReason.PENDING) {
                    pendingKey = entity.pkg to entity.start
                    pendingAt = at
                }
            }

            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val t = e.timeStamp
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        resolvePending(t, reasonFor(e.packageName), e.packageName)
                        if (curPkg != e.packageName) {
                            close(t, reasonFor(e.packageName), e.packageName)
                            curPkg = e.packageName
                            activeClasses.clear()
                            curStart = t
                        }
                        activeClasses.add(e.className)
                    }
                    // Only close when ALL resumed activities for this package have paused. Apps like
                    // MLBB switch between multiple activities (splash -> Unity -> overlay) while
                    // staying in the foreground; a single PAUSED only removes that class from the
                    // active set. The session ends when the set is empty.
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (curPkg == e.packageName) {
                            activeClasses.remove(e.className)
                            if (activeClasses.isEmpty()) close(t, EndReason.PENDING, null)
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> {
                        resolvePending(t, EndReason.SCREEN_OFF, null)
                        close(t, EndReason.SCREEN_OFF, null)
                    }
                    UsageEvents.Event.DEVICE_SHUTDOWN -> {
                        resolvePending(t, EndReason.SHUTDOWN, null)
                        close(t, EndReason.SHUTDOWN, null)
                    }
                    UsageEvents.Event.KEYGUARD_HIDDEN -> unlocks.add(UnlockEntity(t))
                }
            }
            pendingKey?.let { key -> if (now - pendingAt > PENDING_WINDOW_MS) setReason(key, EndReason.UNKNOWN, null) }

            // Upserting by (pkg, start) makes a re-imported, now-longer session replace its earlier copy.
            if (closed.isNotEmpty()) dao.upsertSessions(closed)
            if (unlocks.isNotEmpty()) dao.insertUnlocks(unlocks)

            val open = curPkg?.let { SessionEntity(pkg = it, start = curStart, end = now) }
            openSession = open
            // Resume from the still-open session's start so it is re-read (and closed) next time.
            prefs.edit().putLong(KEY_CURSOR, open?.start ?: now).apply()
        }
    }
}
