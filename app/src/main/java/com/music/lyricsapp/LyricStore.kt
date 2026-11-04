package com.music.lyricsapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.music.lyricsapp.core.LyricsSource

data class LocalTrack(
    val uri: String, val name: String, val title: String, val artist: String,
    val album: String, val durationMs: Long, val signature: String,
    val status: String, val source: String, val proposed: String,
    val enhanced: String, val existing: String, val error: String,
    val review: String = "",
    val selectedProvider: String = "",
    val providerScan: String = "",
)

data class ProviderCandidate(
    val source: LyricsSource,
    val state: String,
    val plain: String = "",
    val enhanced: String = "",
    val review: String = "",
)

class LyricStore(context: Context) : SQLiteOpenHelper(context, "lyrics.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tracks (
            uri TEXT PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
            artist TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL,
            signature TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
            proposed TEXT NOT NULL DEFAULT '', enhanced TEXT NOT NULL DEFAULT '',
            existing TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT '', selected_provider TEXT NOT NULL DEFAULT '',
            provider_scan TEXT NOT NULL DEFAULT ''
        )""")
        createProviderTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE tracks ADD COLUMN review TEXT NOT NULL DEFAULT ''")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN selected_provider TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tracks ADD COLUMN provider_scan TEXT NOT NULL DEFAULT ''")
            createProviderTable(db)
        }
    }

    private fun createProviderTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE provider_results (
            track_uri TEXT NOT NULL, provider TEXT NOT NULL, state TEXT NOT NULL,
            plain TEXT NOT NULL DEFAULT '', enhanced TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT '', PRIMARY KEY (track_uri, provider)
        )""")
    }

    @Synchronized fun scanned(track: LocalTrack): Boolean {
        val old = get(track.uri)
        if (old != null && old.signature == track.signature && old.name == track.name &&
            old.existing == track.existing) return false
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(track.uri))
        val row = ContentValues().apply {
            put("uri", track.uri); put("name", track.name); put("title", track.title)
            put("artist", track.artist); put("album", track.album); put("duration_ms", track.durationMs)
            put("signature", track.signature); put("status", track.status)
            put("source", ""); put("proposed", ""); put("enhanced", "")
            put("existing", track.existing); put("error", track.error); put("review", track.review)
            put("selected_provider", track.selectedProvider); put("provider_scan", track.providerScan)
        }
        writableDatabase.insertWithOnConflict("tracks", null, row, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    @Synchronized fun update(uri: String, status: String, source: String = "", proposed: String = "", enhanced: String = "", error: String = "") {
        writableDatabase.update("tracks", ContentValues().apply {
            put("status", status); put("source", source); put("proposed", proposed)
            put("enhanced", enhanced); put("error", error)
        }, "uri=?", arrayOf(uri))
    }

    @Synchronized fun editMatch(uri: String, title: String, artist: String) {
        writableDatabase.update("tracks", ContentValues().apply {
            put("title", title.trim()); put("artist", artist.trim())
            put("status", "new"); put("error", "")
            put("source", ""); put("proposed", ""); put("enhanced", ""); put("review", "")
            put("selected_provider", ""); put("provider_scan", "")
        }, "uri=?", arrayOf(uri))
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(uri))
    }

    @Synchronized fun setReview(uri: String, review: String) {
        require(review == "correct" || review == "wrong" || review.isEmpty())
        val provider = get(uri)?.selectedProvider.orEmpty()
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("tracks", ContentValues().apply { put("review", review) },
                "uri=?", arrayOf(uri))
            if (provider.isNotBlank()) writableDatabase.update("provider_results",
                ContentValues().apply { put("review", review) },
                "track_uri=? AND provider=?", arrayOf(uri, provider))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized fun clearCandidateLookup(uri: String) {
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(uri))
        writableDatabase.update("tracks", ContentValues().apply {
            put("provider_scan", ""); put("selected_provider", "")
            put("source", ""); put("proposed", ""); put("enhanced", "")
            put("review", ""); put("status", "new"); put("error", "")
        }, "uri=?", arrayOf(uri))
    }

    @Synchronized fun setProviderScan(uri: String, key: String) {
        writableDatabase.update("tracks", ContentValues().apply { put("provider_scan", key) },
            "uri=?", arrayOf(uri))
    }

    @Synchronized fun invalidateProviderScans() {
        writableDatabase.update("tracks", ContentValues().apply { put("provider_scan", "") }, null, null)
    }

    @Synchronized fun setCandidate(uri: String, candidate: ProviderCandidate) {
        writableDatabase.insertWithOnConflict("provider_results", null, ContentValues().apply {
            put("track_uri", uri); put("provider", candidate.source.name)
            put("state", candidate.state); put("plain", candidate.plain)
            put("enhanced", candidate.enhanced); put("review", candidate.review)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun candidates(uri: String): List<ProviderCandidate> {
        val found = readableDatabase.query("provider_results", null, "track_uri=?",
            arrayOf(uri), null, null, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val source = LyricsSource.entries.firstOrNull {
                        it.name == cursor.getString(cursor.getColumnIndexOrThrow("provider"))
                    } ?: continue
                    add(ProviderCandidate(source,
                        cursor.getString(cursor.getColumnIndexOrThrow("state")),
                        cursor.getString(cursor.getColumnIndexOrThrow("plain")),
                        cursor.getString(cursor.getColumnIndexOrThrow("enhanced")),
                        cursor.getString(cursor.getColumnIndexOrThrow("review"))))
                }
            }
        }
        return LyricsSource.offered.mapNotNull { source -> found.firstOrNull { it.source == source } }
    }

    @Synchronized fun selectCandidate(uri: String, source: LyricsSource): LocalTrack {
        val candidate = candidates(uri).firstOrNull { it.source == source && it.state == "found" && it.plain.isNotBlank() }
            ?: error("This provider has no lyrics to select")
        val track = get(uri) ?: error("File is no longer in the library")
        val inFile = track.existing.isNotBlank() && track.existing.trim() == candidate.plain.trim()
        writableDatabase.update("tracks", ContentValues().apply {
            put("status", if (inFile) "applied" else "ready")
            put("source", source.label); put("proposed", candidate.plain)
            put("enhanced", candidate.enhanced); put("review", candidate.review)
            put("selected_provider", source.name); put("error", "")
        }, "uri=?", arrayOf(uri))
        return get(uri) ?: error("Could not load selected lyrics")
    }

    @Synchronized fun get(uri: String): LocalTrack? = readableDatabase.query(
        "tracks", null, "uri=?", arrayOf(uri), null, null, null,
    ).use { if (it.moveToFirst()) it.track() else null }

    @Synchronized fun delete(uri: String) {
        writableDatabase.delete("tracks", "uri=?", arrayOf(uri))
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(uri))
    }

    @Synchronized fun clear() {
        writableDatabase.delete("tracks", null, null)
        writableDatabase.delete("provider_results", null, null)
    }

    @Synchronized fun removeMissing(seen: Set<String>) {
        all().filter { it.uri !in seen }.forEach { delete(it.uri) }
    }

    @Synchronized fun all(): List<LocalTrack> = readableDatabase.query(
        "tracks", null, null, null, null, null, "name COLLATE NOCASE ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.track()) } }

    private fun android.database.Cursor.track() = LocalTrack(
        uri = getString(getColumnIndexOrThrow("uri")),
        name = getString(getColumnIndexOrThrow("name")),
        title = getString(getColumnIndexOrThrow("title")),
        artist = getString(getColumnIndexOrThrow("artist")),
        album = getString(getColumnIndexOrThrow("album")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        signature = getString(getColumnIndexOrThrow("signature")),
        status = getString(getColumnIndexOrThrow("status")),
        source = getString(getColumnIndexOrThrow("source")),
        proposed = getString(getColumnIndexOrThrow("proposed")),
        enhanced = getString(getColumnIndexOrThrow("enhanced")),
        existing = getString(getColumnIndexOrThrow("existing")),
        error = getString(getColumnIndexOrThrow("error")),
        review = getString(getColumnIndexOrThrow("review")),
        selectedProvider = getString(getColumnIndexOrThrow("selected_provider")),
        providerScan = getString(getColumnIndexOrThrow("provider_scan")),
    )
}
