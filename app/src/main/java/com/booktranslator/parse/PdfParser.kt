package com.booktranslator.parse

import com.booktranslator.model.BookContent
import com.booktranslator.model.Chapter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
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
        val chapters = mutableListOf<Chapter>()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                // Помечаем конец страницы пустой строкой — это разрыв абзаца.
                pageEnd = "\n\n"
                lineSeparator = "\n"
            }
            // Извлекаем постранично, чтобы знать, с какого абзаца начинается страница
            // (нужно для привязки закладок-аутлайна к оглавлению).
            val pageParagraphStart = IntArray(doc.numberOfPages)
            for (page in 1..doc.numberOfPages) {
                pageParagraphStart[page - 1] = paragraphs.size
                stripper.startPage = page
                stripper.endPage = page
                collectParagraphs(stripper.getText(doc), paragraphs)
            }

            // Оглавление из закладок (outline), если они есть в документе
            val outline = doc.documentCatalog.documentOutline
            if (outline != null) {
                collectOutline(outline, doc, pageParagraphStart, chapters)
                chapters.sortBy { it.paragraphIndex }
            }
        }
        require(paragraphs.isNotEmpty()) {
            "В PDF нет текстового слоя (возможно, это скан). Поддерживаются только PDF с текстом"
        }
        return BookContent(
            title = file.nameWithoutExtension,
            paragraphs = paragraphs,
            chapters = chapters.distinctBy { it.paragraphIndex }
        )
    }

    /** Рекурсивно собирает закладки PDF-аутлайна в оглавление. */
    private fun collectOutline(
        node: PDOutlineNode,
        doc: PDDocument,
        pageParagraphStart: IntArray,
        out: MutableList<Chapter>
    ) {
        var item = node.firstChild
        while (item != null) {
            runCatching {
                val page = item.findDestinationPage(doc)
                val pageIndex = doc.pages.indexOf(page)
                if (pageIndex >= 0) {
                    val title = item.title?.trim().orEmpty()
                    if (title.isNotEmpty()) {
                        out += Chapter(title, pageParagraphStart[pageIndex])
                    }
                }
            }
            collectOutline(item, doc, pageParagraphStart, out)
            item = item.nextSibling
        }
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
