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
    val backupUri: String = "",
)

data class ProviderCandidate(
    val source: LyricsSource,
    val state: String,
    val plain: String = "",
    val enhanced: String = "",
    val review: String = "",
    val fetchedAt: Long = 0L,
    val lookupKey: String = "",
)

class LyricStore(context: Context) : SQLiteOpenHelper(context, "lyrics.db", null, 6) {
    internal data class ScanState(val signature: String, val name: String, val status: String, val hasExisting: Boolean)

    private var batching = false
    private var batchDirty = false
    private fun changed() {
        if (batching) batchDirty = true
        else LyricsRoomDatabase.current()?.invalidationTracker?.refreshAsync()
    }

    /** Keep each group of scan updates atomic and notify Room once per committed group. */
    @Synchronized fun <T> scanBatch(block: () -> T): T {
        check(!batching)
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        batching = true
        batchDirty = false
        var committed = false
        try {
            val result = block()
            db.setTransactionSuccessful()
            committed = true
            return result
        } finally {
            db.endTransaction()
            batching = false
            if (committed && batchDirty) LyricsRoomDatabase.current()?.invalidationTracker?.refreshAsync()
            batchDirty = false
        }
    }

    @Synchronized internal fun scanStates(indexed: Boolean): Map<String, ScanState> = readableDatabase.rawQuery(
        "SELECT uri, signature, name, status, existing != '' AS has_existing FROM tracks WHERE uri " +
            if (indexed) "LIKE 'content://media/%'" else "NOT LIKE 'content://media/%'", null,
    ).use { cursor -> buildMap {
        while (cursor.moveToNext()) put(cursor.getString(0), ScanState(cursor.getString(1),
            cursor.getString(2), cursor.getString(3), cursor.getInt(4) != 0))
    } }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tracks (
            uri TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
            artist TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL,
            signature TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
            proposed TEXT NOT NULL DEFAULT '', enhanced TEXT NOT NULL DEFAULT '',
            existing TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT '', selected_provider TEXT NOT NULL DEFAULT '',
            provider_scan TEXT NOT NULL DEFAULT '', backup_uri TEXT NOT NULL DEFAULT ''
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
        if (oldVersion < 4) db.execSQL("ALTER TABLE tracks ADD COLUMN backup_uri TEXT NOT NULL DEFAULT ''")
        if (oldVersion in 3..4) {
            db.execSQL("ALTER TABLE provider_results ADD COLUMN fetched_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE provider_results ADD COLUMN lookup_key TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL(TRACKS_REBUILD_CREATE)
        db.execSQL(TRACKS_REBUILD_COPY)
        db.execSQL("DROP TABLE tracks")
        db.execSQL("ALTER TABLE tracks_room RENAME TO tracks")
    }

    private fun createProviderTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE provider_results (
            track_uri TEXT NOT NULL, provider TEXT NOT NULL, state TEXT NOT NULL,
            plain TEXT NOT NULL DEFAULT '', enhanced TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT '', fetched_at INTEGER NOT NULL DEFAULT 0,
            lookup_key TEXT NOT NULL DEFAULT '', PRIMARY KEY (track_uri, provider)
        )""")
    }

    companion object {
        internal const val TRACKS_REBUILD_CREATE = """CREATE TABLE tracks_room (
            uri TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
            artist TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL,
            signature TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
            proposed TEXT NOT NULL DEFAULT '', enhanced TEXT NOT NULL DEFAULT '',
            existing TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT '', selected_provider TEXT NOT NULL DEFAULT '',
            provider_scan TEXT NOT NULL DEFAULT '', backup_uri TEXT NOT NULL DEFAULT ''
        )"""
        internal const val TRACKS_REBUILD_COPY = """INSERT INTO tracks_room (
            uri, name, title, artist, album, duration_ms, signature, status, source,
            proposed, enhanced, existing, error, review, selected_provider, provider_scan, backup_uri
        ) SELECT uri, name, title, artist, album, duration_ms, signature, status, source,
            proposed, enhanced, existing, error, review, selected_provider, provider_scan, backup_uri
            FROM tracks"""
    }

    @Synchronized fun scanned(track: LocalTrack): Boolean {
        val old = get(track.uri)
        if (old != null && old.signature == track.signature && old.name == track.name &&
            old.existing == track.existing && old.status !in setOf("discovered", "inspecting")) return false
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(track.uri))
        val row = ContentValues().apply {
            put("uri", track.uri); put("name", track.name); put("title", track.title)
            put("artist", track.artist); put("album", track.album); put("duration_ms", track.durationMs)
            put("signature", track.signature); put("status", track.status)
            put("source", ""); put("proposed", ""); put("enhanced", "")
            put("existing", track.existing); put("error", track.error); put("review", track.review)
            put("selected_provider", track.selectedProvider); put("provider_scan", track.providerScan)
            put("backup_uri", track.backupUri.ifBlank { old?.backupUri.orEmpty() })
        }
        writableDatabase.insertWithOnConflict("tracks", null, row, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
        return true
    }

    @Synchronized fun discovered(uri: String, name: String, signature: String) {
        val old = get(uri)
        if (old == null) {
            writableDatabase.insertWithOnConflict("tracks", null, ContentValues().apply {
                put("uri", uri); put("name", name); put("title", name.substringBeforeLast('.'))
                put("artist", ""); put("album", ""); put("duration_ms", 0)
                put("signature", signature); put("status", "discovered")
            }, SQLiteDatabase.CONFLICT_IGNORE)
            changed()
        } else if (old.signature != signature || old.status == "metadata_error") {
            writableDatabase.update("tracks", ContentValues().apply { put("status", "inspecting") },
                "uri=?", arrayOf(uri))
            changed()
        }
    }

    @Synchronized fun update(uri: String, status: String, source: String = "", proposed: String = "", enhanced: String = "", error: String = "") {
        writableDatabase.update("tracks", ContentValues().apply {
            put("status", status); put("source", source); put("proposed", proposed)
            put("enhanced", enhanced); put("error", error)
        }, "uri=?", arrayOf(uri))
        changed()
    }

    @Synchronized fun editMatch(uri: String, title: String, artist: String) {
        writableDatabase.update("tracks", ContentValues().apply {
            put("title", title.trim()); put("artist", artist.trim())
            put("status", "new"); put("error", "")
            put("source", ""); put("proposed", ""); put("enhanced", ""); put("review", "")
            put("selected_provider", ""); put("provider_scan", "")
        }, "uri=?", arrayOf(uri))
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(uri))
        changed()
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
        changed()
    }

    @Synchronized fun clearCandidateLookup(uri: String) {
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(uri))
        writableDatabase.update("tracks", ContentValues().apply {
            put("provider_scan", ""); put("selected_provider", "")
            put("source", ""); put("proposed", ""); put("enhanced", "")
            put("review", ""); put("status", "new"); put("error", "")
        }, "uri=?", arrayOf(uri))
        changed()
    }

    @Synchronized fun setCandidate(uri: String, candidate: ProviderCandidate) {
        writableDatabase.insertWithOnConflict("provider_results", null, ContentValues().apply {
            put("track_uri", uri); put("provider", candidate.source.name)
            put("state", candidate.state); put("plain", candidate.plain)
            put("enhanced", candidate.enhanced); put("review", candidate.review)
            put("fetched_at", candidate.fetchedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            put("lookup_key", candidate.lookupKey)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
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
                        cursor.getString(cursor.getColumnIndexOrThrow("review")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("fetched_at")),
                        cursor.getString(cursor.getColumnIndexOrThrow("lookup_key"))))
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
        changed()
        return get(uri) ?: error("Could not load selected lyrics")
    }

    @Synchronized fun get(uri: String): LocalTrack? = readableDatabase.query(
        "tracks", null, "uri=?", arrayOf(uri), null, null, null,
    ).use { if (it.moveToFirst()) it.track() else null }

    @Synchronized fun delete(uri: String) {
        writableDatabase.delete("tracks", "uri=?", arrayOf(uri))
        writableDatabase.delete("provider_results", "track_uri=?", arrayOf(uri))
        changed()
    }

    @Synchronized fun clearBackup(uri: String) {
        writableDatabase.update("tracks", ContentValues().apply { put("backup_uri", "") },
            "uri=?", arrayOf(uri))
        changed()
    }

    @Synchronized internal fun recordMetadata(uri: String, values: MetadataValues) {
        val displayTitle = values.title.ifBlank { get(uri)?.name?.substringBeforeLast('.').orEmpty() }
        writableDatabase.update("tracks", ContentValues().apply {
            put("title", displayTitle); put("artist", values.artist); put("album", values.album)
            put("existing", values.lyrics)
            if (MediaLibrary.isIndexed(uri)) put("status", "indexed")
        }, "uri=?", arrayOf(uri))
        changed()
    }

    @Synchronized fun editProposed(uri: String, plain: String, enhanced: String) {
        StandardLyricsField.requireLyrics(plain)
        writableDatabase.update("tracks", ContentValues().apply {
            put("proposed", plain); put("enhanced", enhanced)
            put("source", "Edited"); put("selected_provider", "")
            put("review", ""); put("status", "ready"); put("error", "")
        }, "uri=?", arrayOf(uri))
        changed()
    }

    @Synchronized fun recordWrite(track: LocalTrack, outcome: ApprovedWriter.Outcome) {
        if (!outcome.replaced) {
            update(track.uri, "ready", track.source, track.proposed, track.enhanced, outcome.message)
            return
        }
        val saved = candidates(track.uri)
        scanned(track.copy(uri = outcome.uri, name = outcome.name, signature = outcome.signature,
            status = "applied", existing = track.proposed, backupUri = outcome.backupUri))
        saved.forEach { setCandidate(outcome.uri, it) }
        update(outcome.uri, "applied", track.source, track.proposed, track.enhanced)
        if (outcome.uri != track.uri) delete(track.uri)
    }

    @Synchronized fun clear() {
        writableDatabase.delete("tracks", null, null)
        writableDatabase.delete("provider_results", null, null)
        changed()
    }

    @Synchronized fun removeMissing(seen: Set<String>) {
        missingUris(seen, indexed = false).forEach { delete(it) }
    }

    @Synchronized fun removeMissingIndexed(seen: Set<String>) {
        missingUris(seen, indexed = true).forEach { delete(it) }
    }

    private fun missingUris(seen: Set<String>, indexed: Boolean): List<String> = readableDatabase.rawQuery(
        "SELECT uri FROM tracks WHERE uri " +
            if (indexed) "LIKE 'content://media/%'" else "NOT LIKE 'content://media/%'", null,
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in seen }?.let(::add)
    } }

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
        backupUri = getString(getColumnIndexOrThrow("backup_uri")),
    )
}
