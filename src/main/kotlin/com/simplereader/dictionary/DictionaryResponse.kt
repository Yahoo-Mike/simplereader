package com.simplereader.dictionary

data class DictionaryResponse(
    val word: String,
    val phonetic: String?,
    val origin: String?,
    val meanings: List<Meaning>?
)

data class Meaning(
    val partOfSpeech: String?,
    val definitions: List<Definition>?
)

data class Definition(
    val definition: String?,
    val example: String?,
    val synonyms: List<String>?,
    val antonyms: List<String>?
)
