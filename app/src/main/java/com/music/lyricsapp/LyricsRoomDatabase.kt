package com.music.lyricsapp

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.music.lyricsapp.core.LyricsSource
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val signature: String,
    val status: String,
    @ColumnInfo(defaultValue = "''") val source: String,
    @ColumnInfo(defaultValue = "''") val proposed: String,
    @ColumnInfo(defaultValue = "''") val enhanced: String,
    @ColumnInfo(defaultValue = "''") val existing: String,
    @ColumnInfo(defaultValue = "''") val error: String,
    @ColumnInfo(defaultValue = "''") val review: String,
    @ColumnInfo(name = "selected_provider", defaultValue = "''") val selectedProvider: String,
    @ColumnInfo(name = "provider_scan", defaultValue = "''") val providerScan: String,
    @ColumnInfo(name = "backup_uri", defaultValue = "''") val backupUri: String,
) {
    fun track(): LocalTrack = LocalTrack(uri, name, title, artist, album, durationMs,
        signature, status, source, proposed, enhanced, existing, error, review,
        selectedProvider, providerScan, backupUri)
}

@Entity(tableName = "provider_results", primaryKeys = ["track_uri", "provider"])
data class ProviderResultEntity(
    @ColumnInfo(name = "track_uri") val trackUri: String,
    val provider: String,
    val state: String,
    @ColumnInfo(defaultValue = "''") val plain: String,
    @ColumnInfo(defaultValue = "''") val enhanced: String,
    @ColumnInfo(defaultValue = "''") val review: String,
    @ColumnInfo(name = "fetched_at", defaultValue = "0") val fetchedAt: Long,
    @ColumnInfo(name = "lookup_key", defaultValue = "''") val lookupKey: String,
) {
    fun candidate(): ProviderCandidate? = LyricsSource.entries.firstOrNull { it.name == provider }
        ?.let { ProviderCandidate(it, state, plain, enhanced, review, fetchedAt, lookupKey) }
}

@Dao
interface LyricsRoomDao {
    @Query("SELECT * FROM tracks ORDER BY name COLLATE NOCASE ASC")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM provider_results WHERE track_uri = :uri")
    fun observeCandidates(uri: String): Flow<List<ProviderResultEntity>>
}

@Database(entities = [TrackEntity::class, ProviderResultEntity::class], version = 6, exportSchema = true)
abstract class LyricsRoomDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsRoomDao

    companion object {
        @Volatile private var instance: LyricsRoomDatabase? = null
        fun current(): LyricsRoomDatabase? = instance

        fun get(context: Context): LyricsRoomDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LyricsRoomDatabase::class.java,
                "lyrics.db")
                .addMigrations(MIGRATION_3_6, MIGRATION_4_6, MIGRATION_5_6)
                .build().also { instance = it }
        }

        private val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN backup_uri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE provider_results ADD COLUMN fetched_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_results ADD COLUMN lookup_key TEXT NOT NULL DEFAULT ''")
                rebuildTracks(db)
            }
        }
        private val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_results ADD COLUMN fetched_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_results ADD COLUMN lookup_key TEXT NOT NULL DEFAULT ''")
                rebuildTracks(db)
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) { rebuildTracks(db) }
        }

        private fun rebuildTracks(db: SupportSQLiteDatabase) {
            db.execSQL(LyricStore.TRACKS_REBUILD_CREATE)
            db.execSQL(LyricStore.TRACKS_REBUILD_COPY)
            db.execSQL("DROP TABLE tracks")
            db.execSQL("ALTER TABLE tracks_room RENAME TO tracks")
        }
    }
}
