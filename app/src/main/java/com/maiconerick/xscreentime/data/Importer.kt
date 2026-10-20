package com.maiconerick.xscreentime.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Imports daily per-app totals exported from StayFree (converted by tools/stayfree_to_json.py).
 * Drop the JSON into <external files>/import/ and it is picked up on the next sync, then renamed
 * to *.imported so it only runs once.
 */
object Importer {
    const val PREFIX = "stayfree:"

    fun importDir(ctx: Context): File? = ctx.getExternalFilesDir("import")

    suspend fun importPending(ctx: Context) {
        val files = importDir(ctx)?.listFiles { f -> f.extension == "json" }.orEmpty()
        if (files.isEmpty()) return
        val labels = labelIndex(ctx)
        val dao = UsageDb.get(ctx).dao()
        for (file in files) {
            runCatching {
                val root = JSONObject(file.readText())
                if (root.has("plays")) {
                    importLegacyPlays(dao, root)
                    file.renameTo(File(file.parentFile, file.name + ".imported"))
                    return@runCatching
                }
                val rows = ArrayList<ImportedDayEntity>()
                val days = root.getJSONArray("days")
                for (i in 0 until days.length()) {
                    val d = days.getJSONObject(i)
                    val epochDay = LocalDate.parse(d.getString("date")).toEpochDay()
                    val apps = d.getJSONArray("apps")
                    // Several labels can map to one package; sum them.
                    val perPkg = HashMap<String, Pair<Long, Int>>()
                    for (j in 0 until apps.length()) {
                        val a = apps.getJSONArray(j)
                        val label = a.getString(0)
                        val pkg = labels[label.lowercase()] ?: PREFIX + label
                        val prev = perPkg[pkg] ?: (0L to 0)
                        perPkg[pkg] = (prev.first + a.getLong(1) * 1000) to (prev.second + a.getInt(2))
                    }
                    perPkg.forEach { (pkg, v) -> rows.add(ImportedDayEntity(epochDay, pkg, v.first, v.second)) }
                }
                rows.chunked(2_000).forEach { dao.upsertImportedDays(it) }

                val unlocks = root.optJSONObject("unlocks")
                if (unlocks != null) {
                    dao.upsertImportedUnlocks(unlocks.keys().asSequence().map {
                        ImportedUnlocksEntity(LocalDate.parse(it).toEpochDay(), unlocks.getInt(it))
                    }.toList())
                }
                file.renameTo(File(file.parentFile, file.name + ".imported"))
                Log.i("Importer", "imported ${rows.size} app-days from ${file.name}")
            }.onFailure { Log.e("Importer", "failed to import ${file.name}", it) }
        }
    }

    /**
     * Old PlayTime sessions (tools/tasker_playtime_to_json.py): {"plays": [{"pkg", "start", "end"}]}, epoch ms.
     * Their apps were tracked as games already, so they are marked as games too.
     */
    private suspend fun importLegacyPlays(dao: UsageDao, root: JSONObject) {
        val plays = root.getJSONArray("plays")
        val rows = (0 until plays.length()).map {
            val p = plays.getJSONObject(it)
            LegacyPlayEntity(p.getString("pkg"), p.getLong("start"), p.getLong("end"))
        }
        dao.upsertLegacyPlays(rows)
        dao.insertGames(rows.map { it.pkg }.distinct().map { GameEntity(it) })
        Log.i("Importer", "imported ${rows.size} legacy plays")
    }

    /** Lower-cased app label -> package, for every installed app (launchable ones win on clashes). */
    private fun labelIndex(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val map = HashMap<String, String>()
        for (ai in pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
            val label = pm.getApplicationLabel(ai).toString().lowercase()
            val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
            if (launchable || label !in map) map[label] = ai.packageName
        }
        return map
    }
}
