package com.booktranslator.ui.reader

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.booktranslator.data.settings.AppFontFamily
import com.booktranslator.data.settings.ReaderSettings
import com.booktranslator.model.WordSpan
import kotlinx.coroutines.launch

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
    var showSettings by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    val chapters = (state as? ReaderState.Ready)?.content?.chapters.orEmpty()

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
                    if (chapters.isNotEmpty()) {
                        IconButton(onClick = { showContents = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = "Оглавление"
                            )
                        }
                    }
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
                    showContents = showContents,
                    onDismissContents = { showContents = false },
                    onWordTapped = viewModel::onWordTapped,
                    onPositionChanged = viewModel::savePosition
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(
    state: ReaderState.Ready,
    settings: ReaderSettings,
    translations: Map<String, SpanTranslation>,
    showContents: Boolean,
    onDismissContents: () -> Unit,
    onWordTapped: (Int, WordSpan) -> Unit,
    onPositionChanged: (Int, Int) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.book.scrollIndex,
        initialFirstVisibleItemScrollOffset = state.book.scrollOffset
    )
    val scope = rememberCoroutineScope()

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
                val paragraphTranslations = translations.entries
                    .filter { it.key.startsWith("$paragraphIndex:") }
                    .map { it.value }
                InterlinearParagraph(
                    paragraph = paragraph,
                    paragraphIndex = paragraphIndex,
                    translations = paragraphTranslations,
                    style = textStyle,
                    accentColor = translatedColor,
                    onWordTapped = onWordTapped
                )
            }
        }
    }

    // Оглавление: шторка со списком глав, тап — переход к главе
    if (showContents) {
        ModalBottomSheet(onDismissRequest = onDismissContents) {
            Text(
                text = "Оглавление",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(state.content.chapters) { _, chapter ->
                    Text(
                        text = chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    listState.scrollToItem(chapter.paragraphIndex)
                                }
                                onDismissContents()
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
