package com.music.lyricsapp

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.lyricsapp.core.LrcLib
import com.music.lyricsapp.core.LyricsSource
import com.music.lyricsapp.core.toEnhancedLrc
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SourceSettings(onClose: () -> Unit, onSaved: (Boolean) -> Unit) {
    val context = LocalContext.current
    val initialSources = remember { LibrarySettings.sources(context) }
    val initialOnline = remember { LibrarySettings.privacyIntroSeen(context) }
    val initialKey = remember { runCatching { LibrarySettings.paxKey(context) }.getOrDefault("") }
    var chosen by remember { mutableStateOf(initialSources) }
    var order by remember { mutableStateOf(LibrarySettings.sourceOrder(context)) }
    var key by remember { mutableStateOf(initialKey) }
    var autoAdd by remember { mutableStateOf(LibrarySettings.autoAdd(context)) }
    var onlineLookup by remember { mutableStateOf(initialOnline) }
    var backupDays by remember { mutableStateOf(LibrarySettings.backupDays(context)) }
    var wifiOnly by remember { mutableStateOf(LibrarySettings.wifiOnly(context)) }
    var themeChoice by remember { mutableStateOf(ThemePreference.get(context)) }
    var backupMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }
    val rowHeightPx = with(LocalDensity.current) { 56.dp.toPx() }
    fun moveProvider(source: com.music.lyricsapp.core.LyricsSource, offset: Int): Boolean {
        val from = order.indexOf(source)
        val to = (from + offset).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return false
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }
    Column(Modifier.fillMaxSize()
        .padding(horizontal = 20.dp, vertical = 12.dp)) {
        CenterAlignedTopAppBar(title = { Text(stringResource(R.string.ui_settings),
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.library)) } })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.section_lookup))
                SettingsGroupCard {
                Row(Modifier.fillMaxWidth().clickable { onlineLookup = !onlineLookup },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_online_lyric_lookup), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui_sends_title_artist_and_available_album_duration),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = onlineLookup, onCheckedChange = { onlineLookup = it })
                }
                Row(Modifier.fillMaxWidth().clickable { wifiOnly = !wifiOnly },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_background_lookups_on_wi_fi_only), modifier = Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                OutlinedTextField(value = key, onValueChange = { key = it },
                    label = { Text(stringResource(R.string.paxsenix_api_key_optional)) }, modifier = Modifier.fillMaxWidth())
                }
                SectionHeader(stringResource(R.string.section_writing))
                SettingsGroupCard {
                Row(Modifier.fillMaxWidth().clickable { autoAdd = !autoAdd },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_write_lyrics_automatically), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui_off_by_default_writes_only_when_two_providers_ag),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoAdd, onCheckedChange = { autoAdd = it })
                }
                }
                SectionHeader(stringResource(R.string.section_backups))
                SettingsGroupCard {
                Text(stringResource(R.string.ui_keep_original_backups_for), style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { backupMenu = true }) {
                        Text(when (backupDays) {
                            7 -> stringResource(R.string.backup_7_days)
                            30 -> stringResource(R.string.backup_30_days)
                            90 -> stringResource(R.string.backup_90_days)
                            else -> stringResource(R.string.backup_forever)
                        })
                    }
                    DropdownMenu(expanded = backupMenu, onDismissRequest = { backupMenu = false }) {
                        listOf(0 to R.string.backup_forever, 7 to R.string.backup_7_days,
                            30 to R.string.backup_30_days, 90 to R.string.backup_90_days).forEach { (days, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                onClick = { backupDays = days; backupMenu = false })
                        }
                    }
                }
                Text(stringResource(R.string.ui_expired_backups_are_removed_only_when_you_choose),
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                }
                SectionHeader(stringResource(R.string.ui_appearance))
                SettingsGroupCard {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { themeMenu = true }) {
                        Text(stringResource(when (themeChoice) {
                            ThemeChoice.SYSTEM -> R.string.theme_system
                            ThemeChoice.DYNAMIC -> R.string.theme_dynamic
                            ThemeChoice.DARK -> R.string.theme_dark
                            ThemeChoice.LIGHT -> R.string.theme_light
                        }))
                    }
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        ThemeChoice.entries.forEach { choice ->
                            val label = when (choice) {
                                ThemeChoice.SYSTEM -> R.string.theme_system
                                ThemeChoice.DYNAMIC -> R.string.theme_dynamic
                                ThemeChoice.DARK -> R.string.theme_dark
                                ThemeChoice.LIGHT -> R.string.theme_light
                            }
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                onClick = { themeChoice = choice; themeMenu = false })
                        }
                    }
                }
                }
                if (saveError.isNotBlank()) Text(saveError, color = SoftCoral)
                SectionHeader(stringResource(R.string.ui_providers))
                Text(stringResource(R.string.ui_every_enabled_provider_is_checked_for_each_suppo),
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                order.forEachIndexed { index, source ->
                    var dragY by remember(source) { mutableFloatStateOf(0f) }
                    val reorderLabel = stringResource(R.string.reorder_provider, source.label)
                    val moveUpLabel = stringResource(R.string.move_up)
                    val moveDownLabel = stringResource(R.string.move_down)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().clickable {
                        chosen = if (source in chosen) chosen - source else chosen + source
                    }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.label)
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small) {
                                Text(stringResource(when (source) {
                                    com.music.lyricsapp.core.LyricsSource.LRCLIB -> R.string.trust_lower
                                    com.music.lyricsapp.core.LyricsSource.UNISON,
                                    com.music.lyricsapp.core.LyricsSource.MEGALOBIZ -> R.string.trust_medium
                                    else -> R.string.trust_higher
                                }), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("⠿", modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .padding(12.dp)
                            .pointerInput(source, order) {
                                detectDragGesturesAfterLongPress(
                                    onDragEnd = {
                                        val steps = (dragY / rowHeightPx).toInt().let {
                                            if (it == 0 && kotlin.math.abs(dragY) > rowHeightPx / 3)
                                                if (dragY > 0) 1 else -1 else it
                                        }
                                        moveProvider(source, steps)
                                        dragY = 0f
                                    },
                                    onDragCancel = { dragY = 0f },
                                    onDrag = { change, amount -> change.consume(); dragY += amount.y })
                            }
                            .semantics {
                                contentDescription = reorderLabel
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUpLabel) { moveProvider(source, -1) },
                                    CustomAccessibilityAction(moveDownLabel) { moveProvider(source, 1) })
                            })
                        Switch(checked = source in chosen, onCheckedChange = {
                            chosen = if (it) chosen + source else chosen - source
                        })
                    }
                    }
                }
            }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
            Button(onClick = {
            try {
                LibrarySettings.setPaxKey(context, key)
                LibrarySettings.setSources(context, chosen)
                LibrarySettings.setSourceOrder(context, order)
                LibrarySettings.setAutoAdd(context, autoAdd)
                LibrarySettings.setOnlineLookup(context, onlineLookup)
                LibrarySettings.setBackupDays(context, backupDays)
                LibrarySettings.setWifiOnly(context, wifiOnly)
                ThemePreference.set(context, themeChoice)
                onSaved(chosen != initialSources || key != initialKey || onlineLookup != initialOnline)
                onClose()
            } catch (e: Exception) {
                saveError = e.message ?: "Could not save settings"
            }
            }) { Text(stringResource(R.string.save)) }
        }
    }
}
