package com.music.lyricsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Plays the selected local document only while its review screen is open. */
class LocalAudioPlayer(context: Context, uri: Uri) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, Handler(Looper.getMainLooper()))
        .build()
    private var player: MediaPlayer? = null
    private var receiverRegistered = false
    private var released = false

    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var error by mutableStateOf("")
        private set
    var speed by mutableStateOf(1f)
        private set

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    init {
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(attributes)
            media.setOnPreparedListener {
                if (!released) {
                    durationMs = it.duration.toLong().coerceAtLeast(0L)
                    ready = true
                }
            }
            media.setOnCompletionListener {
                playing = false
                positionMs = durationMs
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
            media.setOnErrorListener { _, _, _ ->
                ready = false
                playing = false
                error = "This file could not be played on this device."
                audioManager.abandonAudioFocusRequest(focusRequest)
                true
            }
            media.setDataSource(appContext, uri)
            media.prepareAsync()
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(noisyReceiver, filter)
            }
            receiverRegistered = true
        } catch (failure: Exception) {
            error = failure.message ?: "This file could not be opened for playback."
            player?.release()
            player = null
        }
    }

    fun toggle() {
        if (!ready || released) return
        if (playing) {
            pause()
        } else {
            if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                error = "Audio is in use by another app."
                return
            }
            runCatching {
                if (durationMs > 0 && positionMs >= durationMs) player?.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                player?.start()
                playing = true
                error = ""
            }.onFailure {
                error = it.message ?: "Playback could not start."
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    fun pause() {
        if (!playing || released) return
        runCatching { player?.pause() }
        playing = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun seekTo(milliseconds: Long) {
        if (!ready || released) return
        val target = milliseconds.coerceIn(0L, durationMs.coerceAtLeast(0L))
        runCatching { player?.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
            .onSuccess { positionMs = target }
            .onFailure { error = it.message ?: "Could not seek in this file." }
    }

    fun changeSpeed(value: Float) {
        if (!ready || released) return
        val target = value.coerceIn(0.5f, 1f)
        runCatching {
            val wasPlaying = playing
            player?.playbackParams = player!!.playbackParams.setSpeed(target)
            if (!wasPlaying) player?.pause()
            speed = target
        }.onFailure { error = it.message ?: "Could not change playback speed." }
    }

    fun tick() {
        if (!ready || !playing || released) return
        runCatching { player?.currentPosition?.toLong() }
            .getOrNull()?.let { positionMs = it.coerceAtLeast(0L) }
    }

    fun release() {
        if (released) return
        pause()
        released = true
        ready = false
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(noisyReceiver) }
        player?.release()
        player = null
    }
}
