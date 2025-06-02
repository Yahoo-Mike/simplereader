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

// Error messages in format like:
//  {"title":"No Definitions Found",
//   "message":"Sorry pal, we couldn't find definitions for the word you were looking for.",
//   "resolution":"You can try the search again at later time or head to the web instead."}
data class DictionaryErrorResponse(
    val title: String?,
    val message: String?,
    val resolution: String?
)