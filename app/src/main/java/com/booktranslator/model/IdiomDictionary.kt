package com.booktranslator.model

import android.content.Context
import org.json.JSONArray

/** Совпавшее устойчивое выражение: диапазон в тексте абзаца и русский смысл. */
data class IdiomMatch(
    val start: Int,
    val end: Int,
    val phrase: String,
    val translation: String
)

/**
 * Словарь устойчивых выражений (идиом, фразовых клише) из `assets/idioms.json`.
 * При тапе по слову ищет самое длинное выражение, в которое это слово входит
 * в данном абзаце. Совпадение — по точной словоформе (идиомы фиксированы),
 * регистр игнорируется.
 */
class IdiomDictionary(context: Context) {

    /** Разобранные выражения: список слов (lowercase) → русский перевод. */
    private val idioms: List<Pair<List<String>, String>>

    init {
        val json = context.assets.open("idioms.json").bufferedReader().readText()
        val array = JSONArray(json)
        idioms = List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            val words = WordSplitter.split(obj.getString("en"))
                .map { it.word.lowercase() }
            words to obj.getString("ru")
        }
    }

    /**
     * Ищет идиому, содержащую слово [spans] с индексом [tappedIndex].
     * Возвращает самое длинное совпадение или null.
     */
    fun find(paragraph: String, spans: List<WordSpan>, tappedIndex: Int): IdiomMatch? {
        val tappedWord = spans.getOrNull(tappedIndex)?.word?.lowercase() ?: return null
        var best: IdiomMatch? = null
        var bestLength = 0

        for ((tokens, translation) in idioms) {
            if (tokens.size <= bestLength) continue // интересуют только более длинные
            if (tappedWord !in tokens) continue
            val k = tokens.size
            // Пробуем все позиции тапнутого слова внутри выражения
            for (offset in 0 until k) {
                val startIdx = tappedIndex - offset
                if (startIdx < 0 || startIdx + k > spans.size) continue
                val matches = (0 until k).all { j ->
                    spans[startIdx + j].word.equals(tokens[j], ignoreCase = true)
                }
                if (matches) {
                    val start = spans[startIdx].start
                    val end = spans[startIdx + k - 1].end
                    best = IdiomMatch(
                        start = start,
                        end = end,
                        phrase = paragraph.substring(start, end),
                        translation = translation
                    )
                    bestLength = k
                    break // более длинного с этим же словарным входом не будет
                }
            }
        }
        return best
    }
}
