package com.booktranslator.parse

import com.booktranslator.model.BookContent
import java.io.File

/**
 * Разбор файлов книг в единое представление [BookContent].
 * Поддерживаемые форматы: txt, fb2, epub, pdf (с текстовым слоем).
 */
object BookParser {

    /** Расширения файлов, которые умеем импортировать. */
    val SUPPORTED_FORMATS = setOf("txt", "fb2", "epub", "pdf")

    fun parse(file: File, format: String): BookContent = when (format.lowercase()) {
        "txt" -> parseTxt(file)
        "fb2" -> Fb2Parser.parse(file)
        "epub" -> EpubParser.parse(file)
        "pdf" -> PdfParser.parse(file)
        else -> throw UnsupportedFormatException(format)
    }

    private fun parseTxt(file: File): BookContent {
        val text = file.readText(Charsets.UTF_8)
        val paragraphs = text
            .replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
        return BookContent(title = file.nameWithoutExtension, paragraphs = paragraphs)
    }
}

class UnsupportedFormatException(format: String) :
    Exception("Формат не поддерживается: $format. Доступны: txt, fb2, epub, pdf")
