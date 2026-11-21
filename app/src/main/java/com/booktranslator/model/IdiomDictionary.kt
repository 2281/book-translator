package com.booktranslator.model

/** Совпавшее устойчивое выражение: диапазон в тексте абзаца и русский смысл. */
data class IdiomMatch(
    val start: Int,
    val end: Int,
    val phrase: String,
    val translation: String
)

/**
 * Словарь устойчивых выражений (идиом, фразовых глаголов, разговорных клише).
 * Источник: таблица phrases словарной базы (Викисловарь + ручной idioms.json,
 * влитый в базу на этапе сборки).
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
                        translation = chooseTranslation(
                            phrase, paragraph, spans, startIdx, dict
                        )
                    )
                }
            }
        }
        return best
    }

    /**
     * Выбирает родовую форму перевода фразы: если фраза содержит глагол
     * прошедшего времени и слева от её начала есть местоимение, берём
     * вариант нужного рода/числа (ruM/ruF/ruN/ruPl). Иначе — обычный перевод.
     */
    private fun chooseTranslation(
        phrase: PhraseEntry,
        paragraph: String,
        spans: List<WordSpan>,
        startIdx: Int,
        dict: BookDictionary
    ): String {
        val hasPastVerb = (0 until phrase.lemmas.size).any { j ->
            val word = spans[startIdx + j].word.lowercase()
            dict.lookup(word)?.any {
                it.pos == "verb" && (it.tags == "past" || it.tags == "pastpart")
            } == true
        }
        if (!hasPastVerb) return phrase.translation
        val gender = contextGenderIndex(paragraph, spans, startIdx) ?: return phrase.translation
        val variant = when (gender) {
            0 -> phrase.ruM
            1 -> phrase.ruF
            2 -> phrase.ruN
            else -> phrase.ruPl
        }
        return variant ?: phrase.translation
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

        /** Загружает фразы из словарной базы, строит индекс по первой лемме. */
        suspend fun load(provider: WordDictionaryProvider): IdiomDictionary {
            val index = HashMap<String, MutableList<PhraseEntry>>()
            for (p in provider.loadPhrases()) {
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
