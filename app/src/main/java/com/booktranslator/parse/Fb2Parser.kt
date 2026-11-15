package com.booktranslator.parse

import android.util.Xml
import com.booktranslator.model.BookContent
import com.booktranslator.model.Chapter
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Разбор FB2 (FictionBook 2) в [BookContent].
 *
 * FB2 — это XML: метаданные в `<description>`, текст книги в `<body>`
 * (секции `<section>`, абзацы `<p>`, заголовки `<title>`, стихи `<stanza>/<v>`).
 * `<binary>` (картинки) и служебные body (notes/comments) пропускаем.
 */
object Fb2Parser {

    fun parse(file: File): BookContent {
        val parser = Xml.newPullParser().apply {
            // В FB2 встречаются объявления сущностей и «грязные» DOCTYPE — не строгоим.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        }
        val state = ParseState()
        file.inputStream().buffered().use { input ->
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> onStartTag(parser, state)
                    XmlPullParser.TEXT -> onText(parser, state)
                    XmlPullParser.END_TAG -> onEndTag(parser, state)
                }
                event = parser.next()
            }
        }
        state.currentParagraph.flushTo(state.paragraphs)
        val paragraphs = state.paragraphs.toList()
        require(paragraphs.isNotEmpty()) { "В FB2-файле нет текста (пустой <body>)" }
        return BookContent(
            title = state.bookTitle.ifBlank { file.nameWithoutExtension },
            paragraphs = paragraphs,
            chapters = state.chapters
        )
    }

    private class ParagraphBuffer {
        private val sb = StringBuilder()
        var active = false

        fun append(text: String) {
            if (active) sb.append(text)
        }

        /** Пустая строка в тексте книги — разделитель абзацев. */
        fun flushTo(out: MutableList<String>) {
            val text = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) out.add(text)
            sb.setLength(0)
        }
    }

    private class ParseState {
        val paragraphs = mutableListOf<String>()
        val currentParagraph = ParagraphBuffer()
        var bookTitle = ""
        var bodyDepth = 0          // >0 — внутри основного <body>
        var skipDepth = 0          // >0 — внутри <binary>/<description>/чужого body
        var inBookTitle = false    // <description>/<title-info>/<book-title>
        var sawBody = false
        // Оглавление: секции верхнего уровня (прямые дети <body>)
        val chapters = mutableListOf<Chapter>()
        var pendingChapterStart: Int? = null  // индекс абзаца начала текущей секции
        var capturingTitle = false           // внутри <title> верхнеуровневой секции
        val titleBuf = StringBuilder()
    }

    private fun localName(parser: XmlPullParser): String =
        parser.name?.substringAfter(':')?.lowercase() ?: ""

    private fun onStartTag(parser: XmlPullParser, state: ParseState) {
        val tag = localName(parser)
        // <book-title> живёт внутри <description>, который мы не пропускаем целиком,
        // чтобы успеть прочитать название книги.
        if (tag == "book-title" && state.bodyDepth == 0) {
            state.inBookTitle = true
            return
        }
        when {
            state.skipDepth > 0 -> state.skipDepth++
            tag == "binary" -> state.skipDepth = 1
            tag == "description" -> Unit // внутри интересен только <book-title>
            tag == "body" && !state.sawBody -> {
                // Первый <body> без name= (или name!="notes"/"comments") — основной текст.
                val name = (0 until parser.attributeCount)
                    .firstOrNull { parser.getAttributeName(it).equals("name", true) }
                    ?.let { parser.getAttributeValue(it) }
                if (name.isNullOrBlank()) {
                    state.sawBody = true
                    state.bodyDepth = 1
                } else {
                    state.skipDepth = 1
                }
            }
            state.bodyDepth > 0 -> {
                // Секция верхнего уровня — глава для оглавления
                if (tag == "section" && state.bodyDepth == 1) {
                    state.pendingChapterStart = state.paragraphs.size
                }
                // <title> этой секции — название главы
                if (tag == "title" && state.bodyDepth == 2 && state.pendingChapterStart != null) {
                    state.capturingTitle = true
                    state.titleBuf.setLength(0)
                }
                state.bodyDepth++
                when (tag) {
                    "p", "v", "subtitle" -> state.currentParagraph.active = true
                    "empty-line" -> state.currentParagraph.flushTo(state.paragraphs)
                }
            }
        }
    }

    private fun onText(parser: XmlPullParser, state: ParseState) {
        when {
            state.skipDepth > 0 -> Unit
            state.inBookTitle -> state.bookTitle += parser.text
            state.bodyDepth > 0 -> {
                state.currentParagraph.append(parser.text)
                if (state.capturingTitle) state.titleBuf.append(parser.text)
            }
        }
    }

    private fun onEndTag(parser: XmlPullParser, state: ParseState) {
        val tag = localName(parser)
        when {
            state.skipDepth > 0 -> state.skipDepth--
            state.inBookTitle && tag == "book-title" -> state.inBookTitle = false
            state.bodyDepth > 0 -> {
                when (tag) {
                    // Закрылись «абзацные» элементы — выгружаем накопленный текст.
                    // Вложенные инлайн-теги (<emphasis> и т.п.) абзац не закрывают.
                    "p", "v", "subtitle" -> {
                        state.currentParagraph.flushTo(state.paragraphs)
                        state.currentParagraph.active = false
                    }
                    "title" -> {
                        state.currentParagraph.flushTo(state.paragraphs)
                        if (state.capturingTitle) {
                            val title = state.titleBuf.toString()
                                .replace(Regex("\\s+"), " ").trim()
                            val start = state.pendingChapterStart
                            if (title.isNotEmpty() && start != null) {
                                state.chapters += Chapter(title, start)
                            }
                            state.capturingTitle = false
                            state.pendingChapterStart = null
                        }
                    }
                    // Блочные контейнеры тоже разрывают абзац (на случай пустых строк).
                    "epigraph", "section", "poem", "cite", "stanza" -> {
                        state.currentParagraph.flushTo(state.paragraphs)
                        // Секция без заголовка — всё равно даём главу в оглавление
                        if (tag == "section" && state.bodyDepth == 2 &&
                            state.pendingChapterStart != null
                        ) {
                            state.chapters += Chapter(
                                "Раздел ${state.chapters.size + 1}",
                                state.pendingChapterStart!!
                            )
                            state.pendingChapterStart = null
                        }
                    }
                }
                state.bodyDepth--
            }
        }
    }
}
