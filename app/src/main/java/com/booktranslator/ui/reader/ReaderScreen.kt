package com.booktranslator.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.booktranslator.data.settings.AppFontFamily
import com.booktranslator.data.settings.ReaderSettings
import com.booktranslator.model.WordSpan
import com.booktranslator.model.WordSplitter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = ReaderViewModel.factory(context, bookId)
    )
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val translations by viewModel.translations.collectAsState()
    val currentTranslation by viewModel.currentTranslation.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (state as? ReaderState.Ready)?.book?.title ?: "",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки отображения")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            when (val current = state) {
                is ReaderState.Loading -> CircularProgressIndicator()
                is ReaderState.Error -> Text(
                    text = current.message,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
                is ReaderState.Ready -> ReaderContent(
                    state = current,
                    settings = settings,
                    translations = translations,
                    onWordTapped = viewModel::onWordTapped,
                    onPositionChanged = viewModel::savePosition
                )
            }

            // Плашка с переводом последнего нажатого слова
            currentTranslation?.let { translation ->
                TranslationBanner(
                    translation = translation,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    onDismiss = viewModel::dismissTranslation
                )
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onDismiss = { showSettings = false },
            onFontFamily = viewModel::setFontFamily,
            onFontSize = viewModel::setFontSize,
            onTextColor = viewModel::setTextColor,
            onBackgroundColor = viewModel::setBackgroundColor,
            onBackgroundImage = viewModel::setBackgroundImage,
            onClearBackgroundImage = viewModel::clearBackgroundImage
        )
    }
}

@Composable
private fun TranslationBanner(
    translation: TranslationUi,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                translation.loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("${translation.word} — переводим…")
                }
                translation.error -> Text("${translation.word} — ошибка перевода")
                else -> Text("${translation.word} → ${translation.translation}")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Скрыть")
            }
        }
    }
}

@Composable
private fun ReaderContent(
    state: ReaderState.Ready,
    settings: ReaderSettings,
    translations: Map<String, String>,
    onWordTapped: (Int, WordSpan) -> Unit,
    onPositionChanged: (Int, Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.book.scrollIndex,
        initialFirstVisibleItemScrollOffset = state.book.scrollOffset
    )

    // Сохраняем позицию чтения при выходе с экрана
    DisposableEffect(Unit) {
        onDispose {
            onPositionChanged(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(settings.backgroundColor))
    ) {
        // Фоновое изображение, если выбрано
        settings.backgroundImagePath?.let { path ->
            val bitmap = remember(path) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        val composeFontFamily = when (settings.fontFamily) {
            AppFontFamily.SERIF -> FontFamily.Serif
            AppFontFamily.SANS -> FontFamily.SansSerif
            AppFontFamily.MONO -> FontFamily.Monospace
        }
        val textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = composeFontFamily,
            fontSize = settings.fontSizeSp.sp,
            lineHeight = (settings.fontSizeSp * 1.5f).sp,
            color = Color(settings.textColor)
        )
        val translatedColor = MaterialTheme.colorScheme.primary

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
        ) {
            itemsIndexed(state.content.paragraphs) { paragraphIndex, paragraph ->
                ClickableParagraph(
                    paragraph = paragraph,
                    paragraphIndex = paragraphIndex,
                    translations = translations,
                    style = textStyle,
                    translatedColor = translatedColor,
                    onWordTapped = onWordTapped
                )
            }
        }
    }
}

/**
 * Абзац с кликабельными словами. Переведённые слова подменяются переводом
 * и выделяются; повторный тап возвращает оригинал.
 */
@Composable
private fun ClickableParagraph(
    paragraph: String,
    paragraphIndex: Int,
    translations: Map<String, String>,
    style: TextStyle,
    translatedColor: Color,
    onWordTapped: (Int, WordSpan) -> Unit
) {
    val spans = remember(paragraph) { WordSplitter.split(paragraph) }

    // Отображаемый текст + соответствие позиций в нём исходным словам
    val (annotated, displaySpans) = remember(paragraph, translations, paragraphIndex) {
        var last = 0
        val segs = mutableListOf<Pair<IntRange, WordSpan>>()
        val text = buildAnnotatedString {
            spans.forEach { span ->
                if (span.start > last) append(paragraph.substring(last, span.start))
                val translation = translations["$paragraphIndex:${span.start}"]
                val display = translation ?: span.word
                val displayStart = length
                if (translation != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = translatedColor)) {
                        append(display)
                    }
                } else {
                    append(display)
                }
                segs.add((displayStart until displayStart + display.length) to span)
                last = span.end
            }
            if (last < paragraph.length) append(paragraph.substring(last))
        }
        text to segs
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .pointerInput(annotated) {
                detectTapGestures { tapOffset ->
                    val layout = layoutResult ?: return@detectTapGestures
                    val position = layout.getOffsetForPosition(tapOffset)
                    displaySpans
                        .firstOrNull { position in it.first }
                        ?.let { onWordTapped(paragraphIndex, it.second) }
                }
            },
        style = style
    )
}
