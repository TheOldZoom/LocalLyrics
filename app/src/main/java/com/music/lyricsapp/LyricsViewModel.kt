package com.music.lyricsapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.lyricsapp.core.LyricsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Room invalidation drives the library and provider views without polling. */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = LyricsRoomDatabase.get(application).lyricsDao()
    private val selectedUri = MutableStateFlow<String?>(null)

    val tracks: StateFlow<List<LocalTrack>> = dao.observeTracks()
        .map { rows -> rows.map(TrackEntity::track) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val candidates: StateFlow<List<ProviderCandidate>> = selectedUri.flatMapLatest { uri ->
        if (uri == null) flowOf(emptyList()) else dao.observeCandidates(uri).map { rows ->
            val found = rows.mapNotNull(ProviderResultEntity::candidate)
            LyricsSource.offered.mapNotNull { source -> found.firstOrNull { it.source == source } }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun select(uri: String?) { selectedUri.value = uri }

    suspend fun setReview(uri: String, review: String) = dao.setReview(uri, review)

    suspend fun markCorrect(uris: Set<String>): Int = dao.markCorrect(uris)
}
