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
import com.booktranslator.model.BookDictionary
import com.booktranslator.model.IdiomDictionary
import com.booktranslator.model.WordDictionaryProvider
import com.booktranslator.model.WordSpan
import com.booktranslator.model.WordSplitter
import com.booktranslator.model.formatFromDictionary
import com.booktranslator.model.sentenceStartBefore
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
    private val dictionaryProvider: WordDictionaryProvider,
    private val bookId: Long
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val settings: StateFlow<ReaderSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    /** Переведённые фрагменты: ключ "индексАбзаца:начало:конец" → перевод. */
    private val _translations = MutableStateFlow<Map<String, SpanTranslation>>(emptyMap())
    val translations: StateFlow<Map<String, SpanTranslation>> = _translations.asStateFlow()

    /** Словарь открытой книги и фразы; грузятся следом за текстом книги. */
    private var bookDictionary: BookDictionary? = null
    private var idiomDictionary: IdiomDictionary? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val book = repository.getBook(bookId)
            val parsed = when {
                book == null -> null
                else -> runCatching { BookParser.parse(File(book.filePath), book.format) }
                    .getOrNull()
            }
            if (book == null) {
                _state.value = ReaderState.Error("Книга не найдена в каталоге")
                return@launch
            }
            if (parsed == null) {
                _state.value = ReaderState.Error("Не удалось прочитать книгу")
                return@launch
            }
            _state.value = ReaderState.Ready(book, parsed)
            // Словарь книги и фразы — в память, пока книга открыта
            runCatching {
                bookDictionary = BookDictionary.build(dictionaryProvider, parsed.paragraphs)
                idiomDictionary = IdiomDictionary.load(dictionaryProvider)
            }
        }
    }

    /**
     * Тап по слову. Цепочка перевода:
     * 1. тап внутри уже переведённого фрагмента — снять перевод;
     * 2. слово внутри устойчивого выражения — перевод всей фразы из словаря;
     * 3. слово есть в словаре книги — словарный перевод с учётом времени
     *    и рода (род — по местоимению слева; если его нет — уточнение
     *    моделью с контекстом предложения);
     * 4. слова нет в словаре — перевод моделью (подсвечивается красным).
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

        val spans = WordSplitter.split(paragraph)
        val tappedIndex = spans.indexOfFirst { it.start == span.start }
        if (tappedIndex < 0) return
        val dict = bookDictionary

        // Устойчивое выражение вокруг тапнутого слова
        if (dict != null) {
            val idiom = idiomDictionary?.find(paragraph, spans, tappedIndex, dict)
            if (idiom != null) {
                putTranslation(paragraphIndex, SpanTranslation(
                    start = idiom.start,
                    end = idiom.end,
                    original = idiom.phrase,
                    translation = idiom.translation,
                    fromIdiom = true
                ))
                return
            }
        }

        // Слово из словаря книги
        val entries = dict?.lookup(span.word.lowercase())
        if (!entries.isNullOrEmpty()) {
            val result = formatFromDictionary(entries, paragraph, spans, tappedIndex)
            if (result.genderUnresolved) {
                // Глагол прош. вр. без местоимения слева — уточняем моделью
                // с контекстом предложения; не вышло — показываем все формы
                viewModelScope.launch {
                    val start = sentenceStartBefore(paragraph, span.start)
                    val fragment = paragraph.substring(start, span.end)
                    try {
                        val translation = translator.translate(fragment)
                        putTranslation(paragraphIndex, SpanTranslation(
                            start = start,
                            end = span.end,
                            original = fragment,
                            translation = translation,
                            fromIdiom = false,
                            fromModel = true
                        ))
                    } catch (e: Exception) {
                        putTranslation(paragraphIndex, SpanTranslation(
                            start = span.start,
                            end = span.end,
                            original = span.word,
                            translation = result.text,
                            fromIdiom = false
                        ))
                    }
                }
            } else {
                putTranslation(paragraphIndex, SpanTranslation(
                    start = span.start,
                    end = span.end,
                    original = span.word,
                    translation = result.text,
                    fromIdiom = false
                ))
            }
            return
        }

        // Слова нет в словаре — перевод моделью (подсветка красным)
        viewModelScope.launch {
            try {
                val translation = translator.translate(span.word)
                putTranslation(paragraphIndex, SpanTranslation(
                    start = span.start,
                    end = span.end,
                    original = span.word,
                    translation = translation,
                    fromIdiom = false,
                    fromModel = true
                ))
            } catch (e: Exception) {
                // Ошибка перевода — просто не добавляем подстрочник
            }
        }
    }

    /** Счётчик тапов для разрешения наложений подстрочников. */
    private var translationSeq = 0L

    private fun putTranslation(paragraphIndex: Int, tr: SpanTranslation) {
        val key = "$paragraphIndex:${tr.start}:${tr.end}"
        _translations.value = _translations.value + (key to tr.copy(seq = ++translationSeq))
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
                    app.dictionaryProvider,
                    bookId
                )
            }
        }
    }
}
