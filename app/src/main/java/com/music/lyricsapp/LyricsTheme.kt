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
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    primary = Color(0xFF91DACC), onPrimary = Color(0xFF073B35),
    secondary = Color(0xFFF0C783), background = Color(0xFF101E25),
    surface = Color(0xFF101E25), surfaceContainer = Color(0xFF1B2C34),
    surfaceContainerLow = Color(0xFF17262E), surfaceContainerHigh = Color(0xFF253941),
    onSurface = Color(0xFFF1F5F3), onSurfaceVariant = Color(0xFFBDCBC9),
    error = Color(0xFFFFB4A9),
)
private val lightPalette = lightColorScheme(
    primary = Color(0xFF006B5C), onPrimary = Color.White,
    secondary = Color(0xFF76591E), background = Color(0xFFF6F9F7),
    surface = Color(0xFFF6F9F7), surfaceContainer = Color(0xFFE8F0ED),
    surfaceContainerLow = Color(0xFFEEF4F1), surfaceContainerHigh = Color(0xFFDDEAE5),
    onSurface = Color(0xFF172C29), onSurfaceVariant = Color(0xFF455F58),
    error = Color(0xFFB3261E),
)

internal object Space {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp
}

private val appShapes = Shapes(
    small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)
private val appTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
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
    MaterialTheme(colorScheme = palette, typography = appTypography, shapes = appShapes, content = content)
}

internal val DeepBlue: Color @Composable get() = MaterialTheme.colorScheme.background
internal val CardBlue: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val PaleBlue: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val SeaGlass: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val WarmGold: Color @Composable get() = MaterialTheme.colorScheme.secondary
internal val SoftCoral: Color @Composable get() = MaterialTheme.colorScheme.error
internal val MutedBlue: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
