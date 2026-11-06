package com.music.lyricsapp

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer
import java.util.Locale

/** Drafts and reusable lookup results deliberately live outside the scanned library. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val linesJson: String = "[]",
    val audioUri: String = "",
    val origin: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "lookup_cache", primaryKeys = ["queryKey", "provider"])
data class LookupCacheEntity(
    val queryKey: String,
    val provider: String,
    val state: String,
    val plain: String = "",
    val enhanced: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    fun fresh(now: Long = System.currentTimeMillis()): Boolean = now - fetchedAt < when (state) {
        "found" -> 30L * 24 * 60 * 60 * 1000
        "missing" -> 6L * 60 * 60 * 1000
        else -> 15L * 60 * 1000
    }
}

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeDrafts(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id LIMIT 1")
    fun observeDraft(id: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE id = :id LIMIT 1")
    suspend fun draft(id: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)

    @Query("SELECT * FROM lookup_cache WHERE queryKey = :key")
    fun observeCache(key: String): Flow<List<LookupCacheEntity>>

    @Query("SELECT * FROM lookup_cache WHERE queryKey = :key")
    suspend fun cached(key: String): List<LookupCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCache(row: LookupCacheEntity)
}

@Database(entities = [DraftEntity::class, LookupCacheEntity::class], version = 1, exportSchema = true)
abstract class WorkspaceDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        @Volatile private var instance: WorkspaceDatabase? = null
        fun get(context: Context): WorkspaceDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WorkspaceDatabase::class.java,
                "workspace.db").build().also { instance = it }
        }
    }
}

internal fun lookupKey(title: String, artist: String, album: String = ""): String =
    listOf(title, artist, album).joinToString("|") { value ->
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
