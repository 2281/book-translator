package com.booktranslator.parse

import com.booktranslator.model.BookContent
import java.io.File

/**
 * Разбор файлов книг в единое представление [BookContent].
 * Этап 1: только txt. fb2/epub/pdf добавляются на этапе 4.
 */
object BookParser {

    fun parse(file: File, format: String): BookContent = when (format.lowercase()) {
        "txt" -> parseTxt(file)
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
    Exception("Формат пока не поддерживается: $format")
