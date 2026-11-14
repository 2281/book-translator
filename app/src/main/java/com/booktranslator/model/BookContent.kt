package com.booktranslator.model

/**
 * Единое внутреннее представление книги, не зависящее от формата файла.
 * Все парсеры (txt, fb2, epub, pdf) приводят книгу к этому виду.
 */
data class BookContent(
    val title: String,
    val paragraphs: List<String>
)
