package com.booktranslator.parse

import com.booktranslator.model.BookContent
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * Разбор PDF с текстовым слоем в [BookContent] через PDFBox-Android.
 *
 * PDF не хранит абзацы — только строки. Эвристика: `PDFTextStripper`
 * собирает текст постранично, строки склеиваем в абзацы; разрыв абзаца —
 * по пустым строкам и концу страницы, переносы слов склеиваем.
 * Скан без текстового слоя даст пустой результат — об этом сообщаем пользователю.
 */
object PdfParser {

    fun parse(file: File): BookContent {
        val paragraphs = mutableListOf<String>()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                // Помечаем конец страницы пустой строкой — это разрыв абзаца.
                pageEnd = "\n\n"
                lineSeparator = "\n"
            }
            val raw = stripper.getText(doc)
            collectParagraphs(raw, paragraphs)
        }
        require(paragraphs.isNotEmpty()) {
            "В PDF нет текстового слоя (возможно, это скан). Поддерживаются только PDF с текстом"
        }
        return BookContent(title = file.nameWithoutExtension, paragraphs = paragraphs)
    }

    private fun collectParagraphs(raw: String, out: MutableList<String>) {
        val sb = StringBuilder()

        fun flush() {
            val text = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (text.length > 1) out.add(text)
            sb.setLength(0)
        }

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                // Пустая строка (или конец страницы) — разрыв абзаца.
                trimmed.isEmpty() -> flush()
                // Перенос слова на следующую строку: «пере-\nвод» → «перевод».
                sb.isNotEmpty() && sb[sb.length - 1] == '-' -> {
                    sb.deleteCharAt(sb.length - 1)
                    sb.append(trimmed)
                }
                sb.isNotEmpty() -> sb.append(' ').append(trimmed)
                else -> sb.append(trimmed)
            }
        }
        flush()
    }
}
