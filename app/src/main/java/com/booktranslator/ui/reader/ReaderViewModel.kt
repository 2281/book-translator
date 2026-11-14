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
import com.booktranslator.model.WordSpan
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

/** Состояние последнего перевода для показа в нижней плашке. */
data class TranslationUi(
    val word: String,
    val translation: String?,
    val loading: Boolean,
    val error: Boolean = false
)

class ReaderViewModel(
    private val repository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val translator: Translator,
    private val bookId: Long
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val settings: StateFlow<ReaderSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    /** Заменённые слова: ключ "индексАбзаца:позицияСлова" -> перевод. */
    private val _translations = MutableStateFlow<Map<String, String>>(emptyMap())
    val translations: StateFlow<Map<String, String>> = _translations.asStateFlow()

    private val _currentTranslation = MutableStateFlow<TranslationUi?>(null)
    val currentTranslation: StateFlow<TranslationUi?> = _currentTranslation.asStateFlow()

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
     * Тап по слову: если слово уже переведено — вернуть оригинал,
     * иначе перевести и заменить слово в тексте.
     */
    fun onWordTapped(paragraphIndex: Int, span: WordSpan) {
        val key = "$paragraphIndex:${span.start}"
        if (_translations.value.containsKey(key)) {
            _translations.value = _translations.value - key
            _currentTranslation.value = null
            return
        }
        viewModelScope.launch {
            _currentTranslation.value = TranslationUi(span.word, null, loading = true)
            try {
                val translation = translator.translate(span.word)
                _translations.value = _translations.value + (key to translation)
                _currentTranslation.value = TranslationUi(span.word, translation, loading = false)
            } catch (e: Exception) {
                _currentTranslation.value = TranslationUi(
                    span.word, null, loading = false, error = true
                )
            }
        }
    }

    fun dismissTranslation() {
        _currentTranslation.value = null
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
                    bookId
                )
            }
        }
    }
}
