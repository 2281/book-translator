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
 * Словарь устойчивых выражений (идиом, фразовых глаголов, разговорных клише).
 * Источники: таблица phrases словарной базы (Викисловарь) + ручной
 * assets/idioms.json (приоритетнее при совпадении).
 *
 * Совпадение ищется по ЛЕММАМ (а не точным словоформам), поэтому
 * "spilled the beans" находит "spill the beans". Лемматизация слов
 * абзаца — через словарь книги [BookDictionary].
 */
class IdiomDictionary private constructor(
    /** Индекс фраз по первой лемме. */
    private val byFirstLemma: Map<String, List<PhraseEntry>>
) {

    /**
     * Ищет самое длинное выражение, содержащее слово [spans] с индексом
     * [tappedIndex]. Возвращает диапазон в тексте [paragraph] или null.
     */
    fun find(
        paragraph: String,
        spans: List<WordSpan>,
        tappedIndex: Int,
        dict: BookDictionary
    ): IdiomMatch? {
        if (tappedIndex !in spans.indices) return null
        // Леммы слов абзаца вычисляем один раз
        val lemmas = spans.map { dict.lemma(it.word.lowercase()) }

        var best: IdiomMatch? = null
        // Фраза может начинаться не дальше, чем на MAX_PHRASE_LEN слов левее тапнутого
        val minStart = (tappedIndex - MAX_PHRASE_LEN + 1).coerceAtLeast(0)
        for (startIdx in tappedIndex downTo minStart) {
            val candidates = byFirstLemma[lemmas[startIdx]] ?: continue
            for (phrase in candidates) {
                val k = phrase.lemmas.size
                if (best != null && k <= bestLength(best, spans)) continue
                if (startIdx + k > spans.size) continue
                if (tappedIndex >= startIdx + k) continue // фраза не накрывает тапнутое слово
                val matches = (0 until k).all { j -> lemmas[startIdx + j] == phrase.lemmas[j] }
                if (matches) {
                    val start = spans[startIdx].start
                    val end = spans[startIdx + k - 1].end
                    best = IdiomMatch(
                        start = start,
                        end = end,
                        phrase = paragraph.substring(start, end),
                        translation = phrase.translation
                    )
                }
            }
        }
        return best
    }

    private fun bestLength(match: IdiomMatch, spans: List<WordSpan>): Int {
        // длина совпадения в словах: число spans внутри диапазона
        var n = 0
        for (s in spans) {
            if (s.start >= match.end) break
            if (s.start >= match.start) n++
        }
        return n
    }

    companion object {
        private const val MAX_PHRASE_LEN = 8

        /** Загружает фразы из базы и ручного idioms.json, строит индекс. */
        suspend fun load(provider: WordDictionaryProvider, context: Context): IdiomDictionary {
            val merged = LinkedHashMap<String, PhraseEntry>()

            // Ручной словарь — в приоритете; слова лемматизируем через базу
            val manual = context.assets.open("idioms.json").bufferedReader().readText()
            val array = JSONArray(manual)
            val manualWords = HashSet<String>()
            val raw = List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                val words = WordSplitter.split(obj.getString("en")).map { it.word.lowercase() }
                manualWords += words
                Triple(obj.getString("en"), words, obj.getString("ru"))
            }
            val lemmaMap = provider.lookupForms(manualWords)
                .mapValues { (_, v) -> v.minByOrNull { it.lemma.length }?.lemma }
            for ((en, words, ru) in raw) {
                val lemmas = words.map { w -> lemmaMap[w] ?: w }
                merged.putIfAbsent(
                    lemmas.joinToString(" "),
                    PhraseEntry(lemmas = lemmas, translation = ru, source = en)
                )
            }

            // Фразы из словарной базы (уже лемматизированы при сборке)
            for (p in provider.loadPhrases()) {
                merged.putIfAbsent(p.lemmas.joinToString(" "), p)
            }

            val index = HashMap<String, MutableList<PhraseEntry>>()
            for (p in merged.values) {
                if (p.lemmas.size in 2..MAX_PHRASE_LEN) {
                    index.getOrPut(p.lemmas.first()) { mutableListOf() }.add(p)
                }
            }
            // Длинные фразы проверяем первыми
            index.values.forEach { list -> list.sortByDescending { it.lemmas.size } }
            return IdiomDictionary(index)
        }
    }
}
