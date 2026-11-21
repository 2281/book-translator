package com.booktranslator.model

/** Результат словарного перевода тапнутого слова. */
data class DictTranslation(
    /** Готовый текст перевода для подстрочника. */
    val text: String,
    /** true — глагол прошедшего времени, род по контексту не найден:
     * вызывающий может уточнить перевод моделью с контекстом предложения. */
    val genderUnresolved: Boolean
)

/** Грамматический род/число для выбора формы прошедшего времени (м|ж|с|мн). */
private enum class Gender(val variantIndex: Int) {
    MASC(0), FEM(1), NEUT(2), PLUR(3)
}

private val PRONOUNS = mapOf(
    "he" to Gender.MASC, "him" to Gender.MASC, "his" to Gender.MASC,
    "she" to Gender.FEM, "her" to Gender.FEM, "hers" to Gender.FEM,
    "it" to Gender.NEUT, "its" to Gender.NEUT,
    "they" to Gender.PLUR, "them" to Gender.PLUR, "their" to Gender.PLUR,
    "theirs" to Gender.PLUR, "we" to Gender.PLUR, "us" to Gender.PLUR
)

private const val MAX_PRONOUN_LOOKBACK = 6
private const val MAX_VARIANTS = 3

/**
 * Форматирует перевод слова [spans] с индексом [tappedIndex] по статьям
 * [entries] из словаря книги [dict]. Учитывает грамматику словоформы
 * (время, число), род по ближайшему местоимению или существительному-
 * подлежащему слева, а для прилагательных — род/число/падеж
 * существительного справа.
 */
fun formatFromDictionary(
    entries: List<WordEntry>,
    paragraph: String,
    spans: List<WordSpan>,
    tappedIndex: Int,
    dict: BookDictionary
): DictTranslation {
    val pastEntry = entries.firstOrNull {
        it.pos == "verb" && (it.tags == "past" || it.tags == "pastpart")
    }
    if (pastEntry != null) {
        val gender = findContextGender(paragraph, spans, tappedIndex)
            ?: findSubjectGender(paragraph, spans, tappedIndex, dict)
        val first = pastEntry.translations.firstOrNull() ?: ""
        if (gender != null) {
            return DictTranslation(
                first.pastForm(gender.variantIndex),
                genderUnresolved = false
            )
        }
        // Род неизвестен — показываем все формы, но сигналим вызывающему:
        // можно уточнить моделью с контекстом
        return DictTranslation(first.allPastForms(), genderUnresolved = true)
    }

    // Прилагательное: согласование с существительным справа
    val adjEntry = entries.firstOrNull { it.pos == "adj" }
    if (adjEntry != null) {
        val agreed = agreeAdjective(adjEntry, paragraph, spans, tappedIndex, dict)
        if (agreed != null) return DictTranslation(agreed, genderUnresolved = false)
    }

    // Показываем один самый подходящий вариант (первый в статье);
    // у парадигмы "м|ж|с|мн" без контекста — первая (мужская) форма
    val text = entries.firstOrNull()?.translations?.firstOrNull() ?: ""
    return DictTranslation(text.pastForm(0), genderUnresolved = false)
}

/** Максимум слов вперёд при поиске существительного для согласования. */
private const val MAX_NOUN_LOOKAHEAD = 3
/** Максимум слов назад при поиске глагола (признак винительного падежа). */
private const val MAX_VERB_LOOKBACK = 8

/**
 * Согласует прилагательное с существительным справа («old wooden door» →
 * «старую», «деревянную»): род и число — по русскому переводу
 * существительного; если слева в предложении есть глагол (позиция
 * прямого дополнения) и род женский — винительный падеж («-ую»).
 */
private fun agreeAdjective(
    adjEntry: WordEntry,
    paragraph: String,
    spans: List<WordSpan>,
    tappedIndex: Int,
    dict: BookDictionary
): String? {
    val sentenceEnd = sentenceEndAfter(paragraph, spans[tappedIndex].end)

    // существительное справа (пропуская другие прилагательные)
    var nounEntry: WordEntry? = null
    for (i in tappedIndex + 1..minOf(tappedIndex + MAX_NOUN_LOOKAHEAD, spans.lastIndex)) {
        val span = spans[i]
        if (span.start >= sentenceEnd) break
        val list = dict.lookup(span.word.lowercase()) ?: break
        val noun = list.firstOrNull { it.pos == "noun" }
        if (noun != null) {
            nounEntry = noun
            break
        }
        if (list.any { it.pos == "adj" }) continue
        break
    }
    if (nounEntry == null) return null

    val ruNoun = nounEntry.translations.firstOrNull()?.substringBefore("|") ?: return null
    val gender = dict.ruGender(ruNoun) ?: return null
    val isPlural = nounEntry.tags == "plural"

    // винительный падеж: слева в предложении есть глагол (дополнение)
    val sentenceStart = sentenceStartBefore(paragraph, spans[tappedIndex].start)
    var accusative = false
    var looked = 0
    for (i in tappedIndex - 1 downTo 0) {
        val span = spans[i]
        if (span.start < sentenceStart) break
        if (++looked > MAX_VERB_LOOKBACK) break
        val list = dict.lookup(span.word.lowercase()) ?: continue
        if (list.any { it.pos == "verb" }) {
            accusative = true
            break
        }
    }

    val genderIndex = when (gender) {
        "m" -> 0
        "f" -> 1
        else -> 2
    }
    for (variant in adjEntry.translations) {
        val parts = variant.split("|")
        if (parts.size < 4) continue
        if (isPlural) return parts[3]
        if (accusative && genderIndex == 1 && parts.size >= 5) return parts[4]
        return parts[genderIndex]
    }
    return null
}

/**
 * Род подлежащего без местоимения: ближайшее существительное слева в
 * текущей клаузе («The traveler walked» → «путешественник» → м.р.).
 * На границе клаузы (запятая, and/but/or) поиск останавливается,
 * чтобы не подхватить подлежащее соседней клаузы.
 */
private fun findSubjectGender(
    paragraph: String,
    spans: List<WordSpan>,
    tappedIndex: Int,
    dict: BookDictionary
): Gender? {
    val tapped = spans.getOrNull(tappedIndex) ?: return null
    val sentenceStart = sentenceStartBefore(paragraph, tapped.start)

    var looked = 0
    for (i in tappedIndex - 1 downTo 0) {
        val span = spans[i]
        if (span.start < sentenceStart) break
        if (++looked > MAX_PRONOUN_LOOKBACK) break
        val word = span.word.lowercase()
        if (word in CLAUSE_BREAKERS) break
        if (span.start > 0 && paragraph[span.start - 1] == ',') break
        val entries = dict.lookup(word) ?: continue
        val noun = entries.firstOrNull { it.pos == "noun" } ?: continue
        val ruNoun = noun.translations.firstOrNull()?.substringBefore("|") ?: continue
        when (dict.ruGender(ruNoun)) {
            "m" -> return Gender.MASC
            "f" -> return Gender.FEM
            "n" -> return Gender.NEUT
        }
    }
    return null
}

private val CLAUSE_BREAKERS = setOf("and", "but", "or")

/** Вариант "м|ж|с|мн" → форма нужного рода/числа; обычный вариант — как есть. */
private fun String.pastForm(index: Int): String {
    val parts = split("|")
    return if (parts.size == 4) parts[index] else this
}

/** Вариант "м|ж|с|мн" → "м/ж/с" (все формы единственного числа). */
private fun String.allPastForms(): String {
    val parts = split("|")
    return if (parts.size == 4) parts.take(3).joinToString("/") else this
}

/**
 * Ищет местоимение-подлежащее слева от тапнутого слова в пределах
 * текущего предложения (не далее [MAX_PRONOUN_LOOKBACK] слов).
 */
private fun findContextGender(
    paragraph: String,
    spans: List<WordSpan>,
    tappedIndex: Int
): Gender? {
    val tapped = spans.getOrNull(tappedIndex) ?: return null
    val sentenceStart = sentenceStartBefore(paragraph, tapped.start)

    var looked = 0
    for (i in tappedIndex - 1 downTo 0) {
        val span = spans[i]
        if (span.start < sentenceStart) break
        if (++looked > MAX_PRONOUN_LOOKBACK) break
        PRONOUNS[span.word.lowercase()]?.let { return it }
    }
    return null
}

/** Индекс рода/числа по местоимению слева от слова [spans] с индексом
 * [wordIndex] (0 — м, 1 — ж, 2 — ср, 3 — мн), null — местоимения нет.
 * Публичная обёртка для выбора родовых форм (слова и идиомы). */
fun contextGenderIndex(
    paragraph: String,
    spans: List<WordSpan>,
    wordIndex: Int
): Int? = findContextGender(paragraph, spans, wordIndex)?.variantIndex

/** Начало текущего предложения: позиция после последнего терминатора
 * (. ! ? … перевод строки) перед [charIndex], пробелы пропущены. */
fun sentenceStartBefore(paragraph: String, charIndex: Int): Int {
    val before = paragraph.substring(0, charIndex)
    var start = maxOf(
        before.lastIndexOf('.'), before.lastIndexOf('!'),
        before.lastIndexOf('?'), before.lastIndexOf('…'),
        before.lastIndexOf('\n')
    ) + 1
    while (start < charIndex && paragraph[start].isWhitespace()) start++
    return start
}

/** Конец текущего предложения: позиция ближайшего терминатора
 * (. ! ? … перевод строки) после [charIndex] или конец абзаца. */
fun sentenceEndAfter(paragraph: String, charIndex: Int): Int {
    for (i in charIndex until paragraph.length) {
        when (paragraph[i]) {
            '.', '!', '?', '…', '\n' -> return i
        }
    }
    return paragraph.length
}
