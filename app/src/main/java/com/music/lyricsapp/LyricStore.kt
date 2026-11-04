package com.music.lyricsapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalTrack(
    val uri: String, val name: String, val title: String, val artist: String,
    val album: String, val durationMs: Long, val signature: String,
    val status: String, val source: String, val proposed: String,
    val enhanced: String, val existing: String, val error: String,
    val review: String = "",
)

class LyricStore(context: Context) : SQLiteOpenHelper(context, "lyrics.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tracks (
            uri TEXT PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
            artist TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL,
            signature TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL DEFAULT '',
            proposed TEXT NOT NULL DEFAULT '', enhanced TEXT NOT NULL DEFAULT '',
            existing TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            review TEXT NOT NULL DEFAULT ''
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE tracks ADD COLUMN review TEXT NOT NULL DEFAULT ''")
    }

    @Synchronized fun scanned(track: LocalTrack): Boolean {
        val old = get(track.uri)
        if (old?.signature == track.signature) return false
        val row = ContentValues().apply {
            put("uri", track.uri); put("name", track.name); put("title", track.title)
            put("artist", track.artist); put("album", track.album); put("duration_ms", track.durationMs)
            put("signature", track.signature); put("status", track.status)
            put("source", ""); put("proposed", ""); put("enhanced", "")
            put("existing", track.existing); put("error", track.error); put("review", track.review)
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
        }, "uri=?", arrayOf(uri))
    }

    @Synchronized fun setReview(uri: String, review: String) {
        require(review == "correct" || review == "wrong" || review.isEmpty())
        writableDatabase.update("tracks", ContentValues().apply { put("review", review) },
            "uri=?", arrayOf(uri))
    }

    @Synchronized fun get(uri: String): LocalTrack? = readableDatabase.query(
        "tracks", null, "uri=?", arrayOf(uri), null, null, null,
    ).use { if (it.moveToFirst()) it.track() else null }

    @Synchronized fun delete(uri: String) {
        writableDatabase.delete("tracks", "uri=?", arrayOf(uri))
    }

    @Synchronized fun clear() {
        writableDatabase.delete("tracks", null, null)
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
    )
}
