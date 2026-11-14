package com.booktranslator.model

/** Слово в тексте абзаца: позиции [start, end) в исходной строке и само слово. */
data class WordSpan(val start: Int, val end: Int, val word: String)

object WordSplitter {
    private val WORD = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")

    fun split(text: String): List<WordSpan> =
        WORD.findAll(text)
            .map { WordSpan(it.range.first, it.range.last + 1, it.value) }
            .toList()
}
