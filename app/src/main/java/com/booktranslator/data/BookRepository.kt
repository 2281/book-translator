package com.booktranslator.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.booktranslator.data.db.BookDao
import com.booktranslator.data.db.BookEntity
import com.booktranslator.parse.BookParser
import com.booktranslator.parse.UnsupportedFormatException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class BookRepository(
    private val dao: BookDao,
    private val context: Context
) {
    fun observeBooks(): Flow<List<BookEntity>> = dao.observeAll()

    suspend fun getBook(id: Long): BookEntity? = dao.getById(id)

    /** Копирует выбранный пользователем файл во внутреннее хранилище и добавляет книгу в каталог. */
    suspend fun importBook(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "book.txt"
            val format = displayName.substringAfterLast('.', "").lowercase()
            if (format !in BookParser.SUPPORTED_FORMATS) throw UnsupportedFormatException(format)

            val dir = File(context.filesDir, "books").apply { mkdirs() }
            val file = File(dir, "${System.currentTimeMillis()}-$displayName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Не удалось открыть файл")

            val now = System.currentTimeMillis()
            dao.insert(
                BookEntity(
                    title = displayName.substringBeforeLast('.'),
                    filePath = file.absolutePath,
                    format = format,
                    addedAt = now,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun savePosition(bookId: Long, index: Int, offset: Int) =
        withContext(Dispatchers.IO) {
            dao.updatePosition(bookId, index, offset, System.currentTimeMillis())
        }

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        File(book.filePath).delete()
        dao.delete(book)
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
