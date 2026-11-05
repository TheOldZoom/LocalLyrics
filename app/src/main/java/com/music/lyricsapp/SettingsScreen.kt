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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var saveError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.library)) }
            Text(stringResource(R.string.ui_settings), style = MaterialTheme.typography.headlineMedium)
        }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { onlineLookup = !onlineLookup },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_online_lyric_lookup), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui_sends_title_artist_and_available_album_duration),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = onlineLookup, onCheckedChange = { onlineLookup = it })
                }
                Row(Modifier.fillMaxWidth().clickable { autoAdd = !autoAdd },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_write_lyrics_automatically), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ui_off_by_default_writes_only_when_two_providers_ag),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoAdd, onCheckedChange = { autoAdd = it })
                }
                OutlinedTextField(value = key, onValueChange = { key = it },
                    label = { Text(stringResource(R.string.paxsenix_api_key_optional)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.ui_keep_original_backups_for), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Forever", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
                        FilterChip(selected = backupDays == days, onClick = { backupDays = days },
                            label = { Text(label) })
                    }
                }
                Text(stringResource(R.string.ui_expired_backups_are_removed_only_when_you_choose),
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable { wifiOnly = !wifiOnly },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_background_lookups_on_wi_fi_only), modifier = Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                Text(stringResource(R.string.ui_appearance), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeChoice.entries.forEach { choice ->
                        FilterChip(selected = themeChoice == choice,
                            onClick = { themeChoice = choice },
                            label = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                if (saveError.isNotBlank()) Text(saveError, color = SoftCoral)
                Text(stringResource(R.string.ui_providers), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.ui_every_enabled_provider_is_checked_for_each_suppo),
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                order.forEachIndexed { index, source ->
                    Row(Modifier.fillMaxWidth().clickable {
                        chosen = if (source in chosen) chosen - source else chosen + source
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.label)
                            Text(source.riskLevel, color = MutedBlue,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            if (index > 0) order = order.toMutableList().apply {
                                add(index - 1, removeAt(index))
                            }
                        }, enabled = index > 0,
                            modifier = Modifier.semantics { contentDescription = "Move ${source.label} up" }) {
                            Text(stringResource(R.string.move_up_arrow)) }
                        TextButton(onClick = {
                            if (index < order.lastIndex) order = order.toMutableList().apply {
                                add(index + 1, removeAt(index))
                            }
                        }, enabled = index < order.lastIndex,
                            modifier = Modifier.semantics { contentDescription = "Move ${source.label} down" }) {
                            Text(stringResource(R.string.move_down_arrow)) }
                        Switch(checked = source in chosen, onCheckedChange = {
                            chosen = if (it) chosen + source else chosen - source
                        })
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
