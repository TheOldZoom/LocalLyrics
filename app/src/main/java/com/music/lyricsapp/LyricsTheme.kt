package com.music.lyricsapp

import android.content.Context
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal enum class ThemeChoice { DARK, LIGHT, SYSTEM, DYNAMIC }

internal object ThemePreference {
    private const val NAME = "appearance"
    fun get(context: Context): ThemeChoice = runCatching {
        ThemeChoice.valueOf(context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("theme", ThemeChoice.SYSTEM.name) ?: ThemeChoice.SYSTEM.name)
    }.getOrDefault(ThemeChoice.SYSTEM)

    fun set(context: Context, choice: ThemeChoice) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("theme", choice.name).apply()
    }
}

private val darkPalette = darkColorScheme(
    primary = Color(0xFF8BD4C6), onPrimary = Color(0xFF142B3A),
    secondary = Color(0xFFFFCA81), background = Color(0xFF142B3A),
    surface = Color(0xFF203B4C), onSurface = Color(0xFFE7F2F0),
    onSurfaceVariant = Color(0xFFAEC5CC), error = Color(0xFFFFA99D),
)
private val lightPalette = lightColorScheme(
    primary = Color(0xFF006B5D), onPrimary = Color.White,
    secondary = Color(0xFF765800), background = Color(0xFFF6FAF8),
    surface = Color(0xFFE5F1EE), onSurface = Color(0xFF162B29),
    onSurfaceVariant = Color(0xFF465D59), error = Color(0xFFB3261E),
)

@Composable
internal fun LyricsAppTheme(context: Context, choice: ThemeChoice, content: @Composable () -> Unit) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val palette = when (choice) {
        ThemeChoice.DARK -> darkPalette
        ThemeChoice.LIGHT -> lightPalette
        ThemeChoice.SYSTEM -> if (systemDark) darkPalette else lightPalette
        ThemeChoice.DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) {
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (systemDark) darkPalette else lightPalette
    }
    MaterialTheme(colorScheme = palette, content = content)
}

internal val DeepBlue: Color @Composable get() = MaterialTheme.colorScheme.background
internal val CardBlue: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val PaleBlue: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val SeaGlass: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val WarmGold: Color @Composable get() = MaterialTheme.colorScheme.secondary
internal val SoftCoral: Color @Composable get() = MaterialTheme.colorScheme.error
internal val MutedBlue: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
