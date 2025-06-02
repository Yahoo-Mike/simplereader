package com.simplereader.dictionary

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.simplereader.databinding.DictionaryBottomSheetBinding
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

class DictionaryBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DictionaryBottomSheetBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_WORD = "arg_word"

        fun newInstance(word: String): DictionaryBottomSheet {
            val fragment = DictionaryBottomSheet()
            val args = Bundle()
            args.putString(ARG_WORD, word)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DictionaryBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val word = arguments?.getString(ARG_WORD)

        if (word == null) {
            binding.definitionContent.text = "An unexpected error occurred: no word to define."
        } else {
            // Start loading definition from dictionaryapi.dev
            lifecycleScope.launch {
                // show progress spinner
                binding.loadingSpinner.visibility = View.VISIBLE
                binding.definitionContent.visibility = View.GONE

                // get the definition
                try {
                    val apiResponse = DictionaryApi.apiService.getDefinitions(word)
                    if (apiResponse.isSuccessful) {
                        val response = apiResponse.body()

                        if (!response.isNullOrEmpty()) {
                            binding.definitionContent.text = buildDefinitionText(response)
                        } else {
                            binding.definitionContent.text = "No definition found."
                        }
                    } else {
                        //parse error
                        val errorBody = apiResponse.errorBody()?.string()
                        binding.definitionContent.text = buildErrorResponse(errorBody)
                    }
                } catch (e: IOException) {
                    binding.definitionContent.text = "Network error. Please check your internet connection."
                } catch (e: HttpException) {
                    binding.definitionContent.text = "Server error. Please try again later."
                } catch (e: JsonSyntaxException) {
                    binding.definitionContent.text = "Unexpected data format received from dictionary server."
                } catch (e: Exception) {
                    binding.definitionContent.text = "An unexpected error occurred: ${e.message}"
                }

                // hide progress spinner
                binding.loadingSpinner.visibility = View.GONE
                binding.definitionContent.visibility = View.VISIBLE
            }
        }
        binding.definitionTitle.text = word ?: "No word"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

//    responses from dictionaryapi.dev have this structure:
//
//    "word": "hello",
//    "phonetic": "həˈləʊ",
//    "phonetics":
//        |
//        +--- "text": "həˈləʊ",
//        |    "audio": "//ssl.gstatic.com/dictionary/static/sounds/20200429/hello--_gb_1.mp3"
//        +--- "text": "hɛˈləʊ"
//
//    "origin": "early 19th century: variant of earlier hollo ; related to holla.",
//    "meanings":
//        |
//        +--- "partOfSpeech": "noun",
//        |    "definitions":
//        |        |
//        |        +--- "definition": "an utterance of ‘hello’; a greeting.",
//        |        |    "example": "she was getting polite nods and hellos from people",
//        |        |    "synonyms": [],
//        |        |    "antonyms": []
//        |
//        +--- "partOfSpeech": "verb",
//        |    "definitions":
//        |        |
//        |        +--- "definition": "an utterance of ‘hello’; a greeting.",
//        |        |    "synonyms": [],
//        |        |    "antonyms": []

    private fun buildDefinitionText( responses: List<DictionaryResponse>) : SpannableString {
        if (responses.isEmpty()) return SpannableString("No definition found.")

        var entry = 1       // number of responses
        var builder = SpannableStringBuilder() // full text of all definitions to display
        var text : SpannableString
        var line = SpannableStringBuilder()

        for (response in responses) {

            // 1. word /phonetic/ [origin]
            //    noun
            //    a. definition
            //       example: example
            //       antonym:
            //       synonym:
            //    b. definition
            //
            //    adj
            //    a. definition

            // 2. word /phonetic/ [origin]
            // ...

            // first line:   1. word /phonetic/ [origin]
            val boldSpan = StyleSpan(Typeface.BOLD)
            text = SpannableString("${entry}. ${response.word} ")
            text.setSpan(boldSpan,0,text.length,Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
            line.append(text)

            response.phonetic?.let { line.append("${it} ") }
            response.origin?.let { line.append("[${it}]") }
            builder.appendLineWithIndent(line,NO_INDENT)
            line.clear()

            if (!response.meanings.isNullOrEmpty()) {
                for (meaning in response.meanings) {

                    // second line:   noun
                    meaning.partOfSpeech?.let {
                        val italicSpan = StyleSpan(Typeface.ITALIC)
                        text = SpannableString(it)
                        text.setSpan(italicSpan,0,text.length,Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                        line.append(text)
                        builder.appendLineWithIndent(line,INDENT_1)
                        line.clear()
                    }

                    if (!meaning.definitions.isNullOrEmpty()) {

                        for ((index, definition) in meaning.definitions.withIndex()) {

                            // third line:  a. definition
                            val listLetter = 'a' + index
                            line.append("${listLetter}. ")

                            definition.definition?.let {
                                line.append(it)
                                builder.appendLineWithIndent(line,INDENT_1,INDENT_2)
                                line.clear()
                            }

                            // example (optional single line)
                            definition.example?.let {
                                line.append("example: $it")
                                val italicSpan = StyleSpan(Typeface.ITALIC)
                                line.setSpan(italicSpan,0,line.length, SpannableString.SPAN_INCLUSIVE_EXCLUSIVE)
                                builder.appendLineWithIndent(line,INDENT_2,INDENT_3)
                                line.clear()
                            }

                            // synonym list (optional)
                            if (!definition.synonyms.isNullOrEmpty()) {
                                line.append("synonyms: ")

                                for ( (index, synonym) in definition.synonyms.withIndex()) {
                                    if (index > 0)
                                        line.append(", ")
                                    line.append(synonym)
                                }
                                val italicSpan = StyleSpan(Typeface.ITALIC)
                                line.setSpan(italicSpan,0,line.length, SpannableString.SPAN_INCLUSIVE_EXCLUSIVE)

                                builder.appendLineWithIndent(line,INDENT_2,INDENT_3)
                                line.clear()
                            }

                            //antonym list (optional)
                            if (!definition.antonyms.isNullOrEmpty()) {
                                line.append("antonyms: ")

                                for ((index, antonym) in definition.antonyms.withIndex()) {
                                    if (index > 0)
                                        line.append(", ")
                                    line.append(antonym)
                                }
                                val italicSpan = StyleSpan(Typeface.ITALIC)
                                line.setSpan(italicSpan,0,line.length, SpannableString.SPAN_INCLUSIVE_EXCLUSIVE)

                                builder.appendLineWithIndent(line,INDENT_2,INDENT_3)
                                line.clear()
                            }
                        }

                        builder.appendLineWithIndent(line,NO_INDENT) // finished part of speech, leave a gap
                    }
                }
            }

            entry++  // prepare for next response
        }

        return SpannableString(builder)
    }

    private val NO_INDENT = 0
    private val INDENT_1 = 50
    private val INDENT_2 = 100
    private val INDENT_3 = 250

    private fun SpannableStringBuilder.appendLineWithIndent(text: SpannableStringBuilder, pxIndent: Int) {
        this.appendLineWithIndent(text,pxIndent,pxIndent)
    }
    private fun SpannableStringBuilder.appendLineWithIndent(text: SpannableStringBuilder, pxIndent1: Int, pxIndent2: Int) {
        val start = length
        append(text)
        val end = length
        setSpan(LeadingMarginSpan.Standard(pxIndent1,pxIndent2), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("\n")
    }

    fun buildErrorResponse(errorBody: String?) : SpannableString {
        val errorResponse = Gson().fromJson(errorBody, DictionaryErrorResponse::class.java)

        var builder = SpannableStringBuilder()
        var line = SpannableStringBuilder()

        if (!errorResponse.title.isNullOrEmpty()) {
            val boldSpan = StyleSpan(Typeface.BOLD)
            line.append(errorResponse.title)
            line.setSpan(boldSpan, 0, line.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
            builder.appendLineWithIndent(line, NO_INDENT)
            line.clear()
        }

        if (!errorResponse.title.isNullOrEmpty()) {
            line.append(errorResponse.message)
            builder.appendLineWithIndent(line, NO_INDENT)
            line.clear()
        }

        if (builder.isEmpty())
            builder.append("An unspecified error occurred.")

        return SpannableString(builder)
    }

}

