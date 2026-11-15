package com.booktranslator.parse

import android.util.Xml
import com.booktranslator.model.BookContent
import com.booktranslator.model.Chapter
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Разбор EPUB в [BookContent].
 *
 * EPUB — это ZIP: `META-INF/container.xml` указывает на OPF-пакет, в OPF —
 * `<manifest>` (id → файл) и `<spine>` (порядок чтения). Текст берём из
 * XHTML-документов в порядке spine: абзацы `<p>`, заголовки `<h1>–<h6>`.
 * Название книги — из `<dc:title>` в метаданных OPF.
 */
object EpubParser {

    private val PARAGRAPH_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote")

    fun parse(file: File): BookContent {
        ZipFile(file).use { zip ->
            val opfPath = readOpfPath(zip)
            val opf = parseOpf(zip, opfPath)
            val opfDir = opfPath.substringBeforeLast('/', "")

            val paragraphs = mutableListOf<String>()
            val chapters = mutableListOf<Chapter>()
            for (idRef in opf.spine) {
                val href = opf.manifest[idRef] ?: continue
                val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val entry = zip.getEntry(path.normalizeZipPath()) ?: continue
                // Каждый документ spine — глава; название — первый заголовок в нём
                val chapterStart = paragraphs.size
                val heading = zip.getInputStream(entry).use { input ->
                    extractParagraphs(input, paragraphs)
                }
                if (paragraphs.size > chapterStart) {
                    chapters += Chapter(
                        title = heading ?: "Глава ${chapters.size + 1}",
                        paragraphIndex = chapterStart
                    )
                }
            }
            require(paragraphs.isNotEmpty()) { "В EPUB не найден текст (пустой spine)" }
            return BookContent(
                title = opf.title.ifBlank { file.nameWithoutExtension },
                paragraphs = paragraphs,
                chapters = chapters
            )
        }
    }

    /** Убирает `..` и `./` из относительных путей внутри ZIP. */
    private fun String.normalizeZipPath(): String {
        val parts = mutableListOf<String>()
        for (part in split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
        return parts.joinToString("/")
    }

    private fun newParser(): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    }

    /** container.xml → путь к OPF-файлу (атрибут full-path первого rootfile). */
    private fun readOpfPath(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml")
            ?: throw IllegalArgumentException("Это не EPUB: нет META-INF/container.xml")
        zip.getInputStream(entry).use { input ->
            val parser = newParser()
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG &&
                    parser.name?.substringAfter(':').equals("rootfile", true)
                ) {
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i).equals("full-path", true)) {
                            return parser.getAttributeValue(i)
                        }
                    }
                }
                event = parser.next()
            }
        }
        throw IllegalArgumentException("В container.xml нет rootfile с full-path")
    }

    private class Opf(
        val title: String,
        val manifest: Map<String, String>, // id → href (только xhtml/html)
        val spine: List<String>            // idref в порядке чтения
    )

    private fun parseOpf(zip: ZipFile, opfPath: String): Opf {
        val entry = zip.getEntry(opfPath)
            ?: throw IllegalArgumentException("В EPUB нет OPF-файла: $opfPath")
        zip.getInputStream(entry).use { input ->
            val parser = newParser()
            parser.setInput(input, null)

            var title = ""
            var inDcTitle = false
            val manifest = mutableMapOf<String, String>()
            val spine = mutableListOf<String>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name?.substringAfter(':')?.lowercase()) {
                        "title" -> inDcTitle = true // dc:title (без namespace — просто title)
                        "item" -> {
                            var id: String? = null
                            var href: String? = null
                            var mediaType: String? = null
                            for (i in 0 until parser.attributeCount) {
                                when (parser.getAttributeName(i).lowercase()) {
                                    "id" -> id = parser.getAttributeValue(i)
                                    "href" -> href = parser.getAttributeValue(i)
                                    "media-type" -> mediaType = parser.getAttributeValue(i)
                                }
                            }
                            if (id != null && href != null &&
                                (mediaType == null || mediaType.contains("html", true))
                            ) {
                                manifest[id!!] = href!!
                            }
                        }
                        "itemref" -> {
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).equals("idref", true)) {
                                    spine.add(parser.getAttributeValue(i))
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inDcTitle) title += parser.text
                    XmlPullParser.END_TAG ->
                        if (parser.name?.substringAfter(':').equals("title", true)) {
                            inDcTitle = false
                        }
                }
                event = parser.next()
            }
            return Opf(title.trim(), manifest, spine)
        }
    }

    /**
     * Вытаскивает абзацы из XHTML/HTML-документа.
     * Возвращает текст первого заголовка h1–h6 (для оглавления) или null.
     * При битой разметке — грубый regex-фолбэк.
     */
    private fun extractParagraphs(input: InputStream, out: MutableList<String>): String? {
        val bytes = input.readBytes()
        var firstHeading: String? = null
        runCatching {
            val parser = newParser()
            parser.setInput(bytes.inputStream(), null)

            val sb = StringBuilder()
            var inParagraph = false
            var currentTag: String? = null
            var skipDepth = 0 // <head>/<script>/<style>

            fun flush() {
                val text = sb.toString().decodeHtmlEntities().replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) {
                    out.add(text)
                    if (firstHeading == null && currentTag in HEADING_TAGS) {
                        firstHeading = text
                    }
                }
                sb.setLength(0)
            }

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name?.substringAfter(':')?.lowercase()
                        when {
                            skipDepth > 0 -> skipDepth++
                            // <head> (с <title> документа), скрипты и стили — не текст книги.
                            tag == "head" || tag == "script" || tag == "style" -> skipDepth = 1
                            tag in PARAGRAPH_TAGS -> {
                                flush() // незакрытый предыдущий абзац
                                inParagraph = true
                                currentTag = tag
                            }
                            tag == "br" && inParagraph -> sb.append(' ')
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                        if (skipDepth == 0) sb.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name?.substringAfter(':')?.lowercase()
                        when {
                            skipDepth > 0 -> skipDepth--
                            tag in PARAGRAPH_TAGS -> {
                                flush()
                                inParagraph = false
                                currentTag = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
            flush()
        }.onFailure {
            // Невалидный XHTML — вырезаем абзацы грубым способом.
            val html = String(bytes, Charsets.UTF_8)
            Regex("<(p|h[1-6])[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(html)
                .map { it.groupValues[2] }
                .map { it.replace(Regex("<[^>]+>"), " ") }
                .map { it.decodeHtmlEntities().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .forEach { out.add(it) }
        }
        return firstHeading
    }

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private fun String.decodeHtmlEntities(): String =
        replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: " " }
            .replace(Regex("&#x([0-9a-fA-F]+);")) {
                it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: " "
            }
}
