package com.booktranslator.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Кэш переводов: один перевод на слово (в нижнем регистре). */
@Entity(tableName = "translations")
data class TranslationEntity(
    @PrimaryKey val word: String,
    val translation: String,
    val createdAt: Long
)
