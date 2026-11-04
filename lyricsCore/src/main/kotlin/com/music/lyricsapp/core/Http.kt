package com.music.lyricsapp.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** HTTP client shared by the extracted lyric providers. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 12
            })
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
