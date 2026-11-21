package com.booktranslator.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.booktranslator.model.WordSpan
import com.booktranslator.model.WordSplitter

/** Перевод фрагмента абзаца: диапазон [start, end) в тексте абзаца и перевод. */
data class SpanTranslation(
    val start: Int,
    val end: Int,
    val original: String,
    val translation: String,
    val fromIdiom: Boolean,
    /** true — перевод нейросетевой моделью (подсвечивается отдельным цветом,
     * т.к. менее надёжен при переводе одиночного слова без контекста). */
    val fromModel: Boolean = false
)

/** Атом раскладки: слово, переведённая фраза или «клей» (пробелы/пунктуация). */
private class Atom(
    val text: String,
    val span: WordSpan?,                 // null для «клея»
    val translation: SpanTranslation?,
    val layout: TextLayoutResult,
    val translationLayout: TextLayoutResult?
) {
    val width: Int get() = layout.size.width
}

private class PlacedAtom(val atom: Atom, val x: Float) {
    /** X подстрочного перевода (после разрешения пересечений на строке). */
    var subX: Float = 0f
}

private class Line(val y: Float) {
    val atoms = mutableListOf<PlacedAtom>()
    val hasTranslation: Boolean get() = atoms.any { it.atom.translation != null }
}

private class ParagraphLayout(
    val lines: List<Line>,
    val mainHeight: Float,
    val subHeight: Float,
    val heightPx: Int,
    val widthPx: Int
) {
    /** Слово (или переведённая фраза) под точкой тапа, если попали. */
    fun hitTest(point: Offset): WordSpan? {
        for (line in lines) {
            if (point.y !in line.y..(line.y + mainHeight)) continue
            for (placed in line.atoms) {
                val atom = placed.atom
                if (atom.span == null) continue
                if (point.x >= placed.x && point.x <= placed.x + atom.width) {
                    return atom.span
                }
            }
        }
        return null
    }
}

/**
 * Абзац с подстрочным переводом (интерлинер): английский текст не меняется,
 * перевод рисуется мелким шрифтом под переведённым словом или фразой.
 * Тап по слову — [onWordTapped] с исходным [WordSpan].
 */
@Composable
fun InterlinearParagraph(
    paragraph: String,
    paragraphIndex: Int,
    translations: List<SpanTranslation>,
    style: TextStyle,
    accentColor: Color,
    modelColor: Color,
    onWordTapped: (Int, WordSpan) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val subStyle = style.copy(
        fontSize = style.fontSize * 0.72f,
        color = accentColor,
        fontWeight = FontWeight.Medium
    )
    val modelSubStyle = subStyle.copy(color = modelColor)
    val translatedStyle = style.copy(color = accentColor, fontWeight = FontWeight.Bold)

    var layout by remember { mutableStateOf<ParagraphLayout?>(null) }

    Layout(
        content = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .pointerInput(paragraph, translations) {
                detectTapGestures { offset ->
                    layout?.hitTest(offset)?.let { onWordTapped(paragraphIndex, it) }
                }
            }
            .drawBehind {
                val l = layout ?: return@drawBehind
                for (line in l.lines) {
                    for (placed in line.atoms) {
                        val atom = placed.atom
                        drawText(atom.layout, topLeft = Offset(placed.x, line.y))
                        if (atom.translation != null && atom.translationLayout != null) {
                            drawText(
                                atom.translationLayout,
                                topLeft = Offset(placed.subX, line.y + l.mainHeight)
                            )
                        }
                    }
                }
            }
    ) { _, constraints ->
        val built = buildParagraphLayout(
            paragraph, translations, textMeasurer,
            style, translatedStyle, subStyle, modelSubStyle, constraints.maxWidth
        )
        layout = built
        layout(constraints.maxWidth, built.heightPx) {}
    }
}

private fun buildParagraphLayout(
    paragraph: String,
    translations: List<SpanTranslation>,
    measurer: TextMeasurer,
    style: TextStyle,
    translatedStyle: TextStyle,
    subStyle: TextStyle,
    modelSubStyle: TextStyle,
    maxWidth: Int
): ParagraphLayout {
    val spans = WordSplitter.split(paragraph)
    val mainH = measurer.measure("Ag", style).size.height.toFloat()
    val subH = measurer.measure("Ag", subStyle).size.height.toFloat()

    // Атомы: переведённые диапазоны — целиком, остальные слова — по одному,
    // между ними «клей» (пробелы и пунктуация исходного текста).
    val atoms = mutableListOf<Atom>()
    var i = 0
    var last = 0
    while (i < spans.size) {
        val span = spans[i]
        val tr = translations.firstOrNull { it.start == span.start }
        val atomSpan: WordSpan
        if (tr != null) {
            atomSpan = WordSpan(tr.start, tr.end, tr.original)
            while (i < spans.size && spans[i].start < tr.end) i++
        } else {
            atomSpan = span
            i++
        }
        if (atomSpan.start > last) {
            val glue = paragraph.substring(last, atomSpan.start)
            atoms += Atom(glue, null, null, measurer.measure(glue, style), null)
        }
        val text = paragraph.substring(atomSpan.start, atomSpan.end)
        val atomStyle = if (tr != null) translatedStyle else style
        val trLayout = tr?.let {
            measurer.measure(it.translation, if (it.fromModel) modelSubStyle else subStyle)
        }
        atoms += Atom(text, atomSpan, tr, measurer.measure(text, atomStyle), trLayout)
        last = atomSpan.end
    }
    if (last < paragraph.length) {
        val tail = paragraph.substring(last)
        atoms += Atom(tail, null, null, measurer.measure(tail, style), null)
    }

    // Жадный перенос строк; «клей» прилипает к предыдущему слову
    val rawLines = mutableListOf<MutableList<PlacedAtom>>()
    var current = mutableListOf<PlacedAtom>()
    var x = 0f
    for (atom in atoms) {
        if (atom.span == null) {
            // Пробельный клей, который не влезает, схлопывается в перенос строки
            if (x + atom.width <= maxWidth || atom.text.isNotBlank()) {
                current += PlacedAtom(atom, x)
                x += atom.width
            }
            continue
        }
        if (x > 0 && x + atom.width > maxWidth) {
            rawLines += current
            current = mutableListOf()
            x = 0f
        }
        current += PlacedAtom(atom, x)
        x += atom.width
    }
    if (current.isNotEmpty()) rawLines += current

    val lines = mutableListOf<Line>()
    var lineY = 0f
    for (raw in rawLines) {
        val line = Line(lineY)
        line.atoms += raw
        lines += line
        lineY += mainH + if (line.hasTranslation) subH else 0f
    }

    // Разрешаем пересечения подстрочных переводов на каждой строке:
    // центрируем под словом, но не наезжаем на соседа справа и не вылезаем
    // за края абзаца
    val gap = 8f
    for (line in lines) {
        var rightEdge = -gap
        for (placed in line.atoms) {
            val atom = placed.atom
            val tl = atom.translationLayout ?: continue
            val subW = tl.size.width.toFloat()
            var subX = placed.x + atom.width / 2f - subW / 2f
            if (subX < rightEdge + gap) subX = rightEdge + gap
            val maxX = (maxWidth - subW).coerceAtLeast(0f)
            if (subX > maxX) subX = maxX
            if (subX < 0f) subX = 0f
            placed.subX = subX
            rightEdge = subX + subW
        }
    }
    return ParagraphLayout(
        lines = lines,
        mainHeight = mainH,
        subHeight = subH,
        heightPx = lineY.toInt().coerceAtLeast(1),
        widthPx = maxWidth
    )
}
