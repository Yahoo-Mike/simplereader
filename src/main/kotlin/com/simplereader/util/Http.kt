package com.simplereader.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    // 1) Base: normal API calls (fast fail)
    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // 2) Upload/download: allow long reads & writes
    val largefile: OkHttpClient = api.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout( 5, TimeUnit.MINUTES)     // how long a single read() can block
        .writeTimeout(5, TimeUnit.MINUTES)     // how long a single write() can block
        .callTimeout(30, TimeUnit.MINUTES)     // how long to upload a whole file
        .retryOnConnectionFailure(true)
        .build()
}