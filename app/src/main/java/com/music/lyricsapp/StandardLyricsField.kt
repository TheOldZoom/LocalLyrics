package com.music.lyricsapp

/** The interoperable field every successful audio write must contain. */
enum class StandardLyricsField(val extensions: Set<String>, val fieldName: String) {
    ID3(setOf("mp3", "aac"), "USLT"),
    VORBIS(setOf("flac", "ogg", "opus"), "LYRICS"),
    ITUNES(setOf("m4a", "mp4"), "©lyr"),
    MATROSKA(setOf("webm", "mkv"), "LYRICS");

    companion object {
        fun forExtension(extension: String): StandardLyricsField =
            entries.firstOrNull { extension.lowercase() in it.extensions }
                ?: throw IllegalArgumentException("No standard lyrics field for $extension")

        fun requireLyrics(lyrics: String): String {
            require(lyrics.isNotBlank() && lyrics.length <= 64_000) { "Invalid standard lyrics" }
            return lyrics
        }
    }
}
