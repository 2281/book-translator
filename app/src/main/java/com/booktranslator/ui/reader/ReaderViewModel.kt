package com.booktranslator.ui.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.booktranslator.data.BookRepository
import com.booktranslator.data.db.AppDatabase
import com.booktranslator.data.db.BookEntity
import com.booktranslator.data.settings.AppFontFamily
import com.booktranslator.data.settings.ReaderSettings
import com.booktranslator.data.settings.SettingsRepository
import com.booktranslator.model.BookContent
import com.booktranslator.model.IdiomDictionary
import com.booktranslator.model.WordSpan
import com.booktranslator.model.WordSplitter
import com.booktranslator.BookTranslatorApp
import com.booktranslator.parse.BookParser
import com.booktranslator.translate.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Ready(val book: BookEntity, val content: BookContent) : ReaderState
    data class Error(val message: String) : ReaderState
}

class ReaderViewModel(
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val translator: Translator,
    private val idiomDictionary: IdiomDictionary,
    private val bookId: Long
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val settings: StateFlow<ReaderSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    /** Переведённые фрагменты: ключ "индексАбзаца:начало:конец" → перевод. */
    private val _translations = MutableStateFlow<Map<String, SpanTranslation>>(emptyMap())
    val translations: StateFlow<Map<String, SpanTranslation>> = _translations.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val book = repository.getBook(bookId)
            _state.value = when {
                book == null -> ReaderState.Error("Книга не найдена в каталоге")
                else -> runCatching { BookParser.parse(File(book.filePath), book.format) }
                    .fold(
                        onSuccess = { ReaderState.Ready(book, it) },
                        onFailure = {
                            ReaderState.Error(it.message ?: "Не удалось прочитать книгу")
                        }
                    )
            }
        }
    }

    /**
     * Тап по слову:
     * - тап внутри уже переведённого фрагмента — убирает перевод;
     * - слово входит в устойчивое выражение из словаря идиом — переводится
     *   вся фраза (без вызова модели, перевод из словаря);
     * - иначе — одиночное слово переводится моделью.
     * Перевод показывается под словом/фразой, английский текст не меняется.
     */
    fun onWordTapped(paragraphIndex: Int, span: WordSpan) {
        val ready = _state.value as? ReaderState.Ready ?: return
        val paragraph = ready.content.paragraphs.getOrNull(paragraphIndex) ?: return

        // Тап внутри уже переведённого фрагмента — снять перевод
        val existingKey = _translations.value.entries
            .firstOrNull { (key, tr) ->
                key.startsWith("$paragraphIndex:") && span.start >= tr.start && span.start < tr.end
            }?.key
        if (existingKey != null) {
            _translations.value = _translations.value - existingKey
            return
        }

        // Проверяем устойчивое выражение (идиому) вокруг тапнутого слова
        val spans = WordSplitter.split(paragraph)
        val tappedIndex = spans.indexOfFirst { it.start == span.start }
        val idiom = idiomDictionary.find(paragraph, spans, tappedIndex)
        if (idiom != null) {
            val key = "$paragraphIndex:${idiom.start}:${idiom.end}"
            _translations.value = _translations.value + (key to SpanTranslation(
                start = idiom.start,
                end = idiom.end,
                original = idiom.phrase,
                translation = idiom.translation,
                fromIdiom = true
            ))
            return
        }

        // Обычное слово — перевод моделью
        viewModelScope.launch {
            try {
                val translation = translator.translate(span.word)
                val key = "$paragraphIndex:${span.start}:${span.end}"
                _translations.value = _translations.value + (key to SpanTranslation(
                    start = span.start,
                    end = span.end,
                    original = span.word,
                    translation = translation,
                    fromIdiom = false
                ))
            } catch (e: Exception) {
                // Ошибка перевода — просто не добавляем подстрочник
            }
        }
    }

    fun savePosition(index: Int, offset: Int) {
        viewModelScope.launch { repository.savePosition(bookId, index, offset) }
    }

    fun setFontFamily(fontFamily: AppFontFamily) {
        viewModelScope.launch { settingsRepository.setFontFamily(fontFamily) }
    }

    fun setFontSize(sizeSp: Float) {
        viewModelScope.launch { settingsRepository.setFontSize(sizeSp) }
    }

    fun setTextColor(color: Long) {
        viewModelScope.launch { settingsRepository.setTextColor(color) }
    }

    fun setBackgroundColor(color: Long) {
        viewModelScope.launch { settingsRepository.setBackgroundColor(color) }
    }

    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch { settingsRepository.setBackgroundImage(uri) }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch { settingsRepository.clearBackgroundImage() }
    }

    companion object {
        fun factory(context: Context, bookId: Long) = viewModelFactory {
            initializer {
                val app = context.applicationContext as BookTranslatorApp
                ReaderViewModel(
                    BookRepository(AppDatabase.get(app).bookDao(), app),
                    SettingsRepository(app),
                    app.translator,
                    IdiomDictionary(app),
                    bookId
                )
            }
        }
    }
}
