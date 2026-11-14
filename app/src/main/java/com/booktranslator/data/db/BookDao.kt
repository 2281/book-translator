package com.booktranslator.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET scrollIndex = :index, scrollOffset = :offset, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePosition(id: Long, index: Int, offset: Int, updatedAt: Long)

    @Delete
    suspend fun delete(book: BookEntity)
}
