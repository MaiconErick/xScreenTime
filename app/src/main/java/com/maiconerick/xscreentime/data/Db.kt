package com.maiconerick.xscreentime.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** One continuous stretch of an app being in the foreground. */
@Entity(tableName = "sessions", primaryKeys = ["pkg", "start"], indices = [Index("start"), Index("end")])
data class SessionEntity(
    val pkg: String,
    val start: Long,
    val end: Long,
    /** One of [EndReason]. */
    val endReason: Int = EndReason.UNKNOWN,
    /** The app that took over the screen, when [endReason] is APP / HOME / SYSTEM_UI. */
    val nextPkg: String? = null,
)

object EndReason {
    const val UNKNOWN = 0
    const val SCREEN_OFF = 1
    const val APP = 2
    const val HOME = 3
    const val SYSTEM_UI = 4
    const val SHUTDOWN = 5
    /** Clipped at midnight for display; the session continues on the next day. */
    const val CONTINUES = 6
    /** Internal: closed by PAUSED, waiting for the next event to tell what happened. */
    const val PENDING = 7
}

@Entity(tableName = "unlocks")
data class UnlockEntity(@PrimaryKey val ts: Long)

/** Per-app daily total from an external export (StayFree): no sessions, just the day's sum. */
@Entity(tableName = "imported_days", primaryKeys = ["day", "pkg"], indices = [Index("pkg")])
data class ImportedDayEntity(
    /** LocalDate.toEpochDay(). */
    val day: Long,
    /** Real package when the label matched an installed app, else "stayfree:<label>". */
    val pkg: String,
    val ms: Long,
    val launches: Int,
)

@Entity(tableName = "imported_unlocks")
data class ImportedUnlocksEntity(@PrimaryKey val day: Long, val count: Int)

/** An app the user marked as a game; its time is exported as play time. */
@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val pkg: String,
    /** Name written to the PlayTime db; null = the app's label. */
    val name: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Play sessions recorded before our own data starts (the old Tasker PlayTime db). */
@Entity(tableName = "legacy_plays", primaryKeys = ["pkg", "start"])
data class LegacyPlayEntity(val pkg: String, val start: Long, val end: Long)

@Dao
interface UsageDao {
    @Query("SELECT * FROM sessions WHERE `end` > :from AND start < :to ORDER BY start")
    suspend fun sessionsOverlapping(from: Long, to: Long): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE pkg = :pkg AND `end` > :from AND start < :to ORDER BY start")
    suspend fun sessionsForApp(pkg: String, from: Long, to: Long): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY `end` DESC LIMIT 1")
    suspend fun lastSession(): SessionEntity?

    @Query("SELECT MIN(start) FROM sessions")
    suspend fun firstStart(): Long?

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Upsert
    suspend fun upsertSessions(sessions: List<SessionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlocks(unlocks: List<UnlockEntity>)

    @Query("SELECT COUNT(*) FROM unlocks WHERE ts >= :from AND ts < :to")
    suspend fun unlockCount(from: Long, to: Long): Int

    @Upsert
    suspend fun upsertImportedDays(rows: List<ImportedDayEntity>)

    @Upsert
    suspend fun upsertImportedUnlocks(rows: List<ImportedUnlocksEntity>)

    @Query("SELECT * FROM imported_days WHERE day = :day")
    suspend fun importedDay(day: Long): List<ImportedDayEntity>

    @Query("SELECT * FROM imported_days WHERE day >= :from AND day <= :to")
    suspend fun importedRange(from: Long, to: Long): List<ImportedDayEntity>

    @Query("SELECT * FROM imported_days WHERE pkg = :pkg AND day >= :from AND day <= :to")
    suspend fun importedRangeForApp(pkg: String, from: Long, to: Long): List<ImportedDayEntity>

    @Query("SELECT count FROM imported_unlocks WHERE day = :day")
    suspend fun importedUnlocks(day: Long): Int?

    @Query("SELECT * FROM sessions WHERE pkg IN (:pkgs) ORDER BY start")
    suspend fun sessionsForApps(pkgs: List<String>): List<SessionEntity>

    @Query("SELECT * FROM imported_days WHERE pkg IN (:pkgs) AND day < :before")
    suspend fun importedForApps(pkgs: List<String>, before: Long): List<ImportedDayEntity>

    @Query("SELECT MIN(day) FROM imported_days")
    suspend fun firstImportedDay(): Long?

    @Query("SELECT MAX(day) FROM imported_days")
    suspend fun lastImportedDay(): Long?

    @Query("SELECT * FROM games ORDER BY addedAt")
    suspend fun games(): List<GameEntity>

    @Upsert
    suspend fun upsertGame(game: GameEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGames(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE pkg = :pkg")
    suspend fun deleteGame(pkg: String)

    @Upsert
    suspend fun upsertLegacyPlays(rows: List<LegacyPlayEntity>)

    @Query("SELECT * FROM legacy_plays WHERE pkg IN (:pkgs) ORDER BY start")
    suspend fun legacyPlays(pkgs: List<String>): List<LegacyPlayEntity>
}

@Database(
    entities = [
        SessionEntity::class, UnlockEntity::class, ImportedDayEntity::class, ImportedUnlocksEntity::class,
        GameEntity::class, LegacyPlayEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class UsageDb : RoomDatabase() {
    abstract fun dao(): UsageDao

    companion object {
        @Volatile private var instance: UsageDb? = null

        fun get(ctx: Context): UsageDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, UsageDb::class.java, "usage.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // v1 only held sessions, which Tracker rebuilds from the system log (IMPORT_VERSION 3).
                .fallbackToDestructiveMigrationFrom(1)
                .build().also { instance = it }
        }
    }
}

/** Archived sessions can't be rebuilt once the system log has rotated, so this one must be a real migration. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `imported_days` (`day` INTEGER NOT NULL, `pkg` TEXT NOT NULL, " +
                "`ms` INTEGER NOT NULL, `launches` INTEGER NOT NULL, PRIMARY KEY(`day`, `pkg`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_imported_days_pkg` ON `imported_days` (`pkg`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `imported_unlocks` (`day` INTEGER NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`day`))")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `games` (`pkg` TEXT NOT NULL, `name` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`pkg`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `legacy_plays` (`pkg` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                "`end` INTEGER NOT NULL, PRIMARY KEY(`pkg`, `start`))",
        )
    }
}
