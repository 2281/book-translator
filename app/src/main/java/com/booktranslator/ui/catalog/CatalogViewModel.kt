package com.booktranslator.ui.catalog

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.booktranslator.data.BookRepository
import com.booktranslator.data.db.AppDatabase
import com.booktranslator.data.db.BookEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CatalogViewModel(private val repository: BookRepository) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = repository.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            repository.importBook(uri).onFailure { error ->
                _importError.value = error.message ?: "Не удалось добавить книгу"
            }
        }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch { repository.deleteBook(book) }
    }

    fun clearImportError() {
        _importError.value = null
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer {
                CatalogViewModel(
                    BookRepository(AppDatabase.get(context).bookDao(), context.applicationContext)
                )
            }
        }
    }
}
