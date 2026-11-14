package com.booktranslator.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val format: String,
    val addedAt: Long,
    val updatedAt: Long,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0
)
