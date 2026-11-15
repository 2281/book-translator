package com.booktranslator.model

/**
 * Единое внутреннее представление книги, не зависящее от формата файла.
 * Все парсеры (txt, fb2, epub, pdf) приводят книгу к этому виду.
 */
data class BookContent(
    val title: String,
    val paragraphs: List<String>,
    /** Оглавление: название главы → индекс абзаца, с которого она начинается. */
    val chapters: List<Chapter> = emptyList()
)

/** Глава/раздел книги для оглавления. */
data class Chapter(
    val title: String,
    val paragraphIndex: Int
)
