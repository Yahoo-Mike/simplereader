package com.simplereader.dictionary

import retrofit2.http.GET
import retrofit2.http.Path

interface DictionaryApiService {
    @GET("api/v2/entries/en/{word}")
    suspend fun getDefinitions(
        @Path("word") word: String
    ): List<DictionaryResponse>
}

