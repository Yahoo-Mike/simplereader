package com.simplereader.dictionary

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// API client for dictionaryapi.dev

object DictionaryApi {
    private const val BASE_URL = "https://api.dictionaryapi.dev/"

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val apiService: DictionaryApiService by lazy {
        retrofit.create(DictionaryApiService::class.java)
    }
}