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
 * [entries] из словаря книги. Учитывает грамматику словоформы (время,
 * число) и род по ближайшему местоимению слева в предложении.
 */
fun formatFromDictionary(
    entries: List<WordEntry>,
    paragraph: String,
    spans: List<WordSpan>,
    tappedIndex: Int
): DictTranslation {
    val pastEntry = entries.firstOrNull {
        it.pos == "verb" && (it.tags == "past" || it.tags == "pastpart")
    }
    if (pastEntry != null) {
        val gender = findContextGender(paragraph, spans, tappedIndex)
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

    // Показываем один самый подходящий вариант (первый в статье)
    val text = entries.firstOrNull()?.translations?.firstOrNull() ?: ""
    return DictTranslation(text, genderUnresolved = false)
}

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
