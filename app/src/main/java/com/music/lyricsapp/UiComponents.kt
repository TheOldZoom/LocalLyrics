package com.music.lyricsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun SettingsGroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
internal fun EmptyState(title: String, guidance: String, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    Surface(modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(guidance, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}

@Composable
internal fun ConfirmDialog(title: String, body: String, confirmLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun LyricsTextBlock(raw: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        raw.lineSequence().forEach { line ->
            val marker = line.trim().let { it.startsWith("[") && it.endsWith("]") }
            Text(line.ifBlank { " " },
                style = (if (marker) MaterialTheme.typography.labelLarge
                    else MaterialTheme.typography.bodyLarge).copy(
                    fontFamily = if (marker) FontFamily.SansSerif else FontFamily.Serif,
                    lineHeight = if (marker) 22.sp else 28.sp,
                    textDirection = TextDirection.ContentOrRtl),
                color = if (marker) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Preview(name = "Lyrics, large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun LyricsTextBlockPreview() {
    LyricsAppTheme(androidx.compose.ui.platform.LocalContext.current, ThemeChoice.LIGHT) {
        SettingsGroupCard {
            LyricsTextBlock("[Verse]\nThe city is quiet\n\n[Chorus]\nيا ليل")
        }
    }
}
