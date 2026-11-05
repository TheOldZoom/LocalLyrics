package com.music.lyricsapp

/** Shifts portable LRC line stamps and optional enhanced word stamps together. */
object LyricTiming {
    private val STAMP = Regex("""(\[|<)(\d{1,3}):(\d{2})[.:](\d{2,3})(]|>)""")

    fun hasTimestamps(text: String): Boolean = STAMP.containsMatchIn(text)

    fun shift(text: String, offsetMs: Int): String = STAMP.replace(text) { match ->
        val open = match.groupValues[1]
        val close = match.groupValues[5]
        if ((open == "[" && close != "]") || (open == "<" && close != ">")) return@replace match.value
        val fraction = match.groupValues[4]
        val originalMs = match.groupValues[2].toLong() * 60_000 +
            match.groupValues[3].toLong() * 1_000 + fraction.toLong() *
            if (fraction.length == 2) 10 else 1
        val shifted = (originalMs + offsetMs).coerceAtLeast(0)
        val minutes = shifted / 60_000
        val seconds = shifted % 60_000 / 1_000
        val fractionValue = shifted % 1_000
        val fractionText = if (fraction.length == 2) (fractionValue / 10).toString().padStart(2, '0')
            else fractionValue.toString().padStart(3, '0')
        "$open${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.$fractionText$close"
    }
}
