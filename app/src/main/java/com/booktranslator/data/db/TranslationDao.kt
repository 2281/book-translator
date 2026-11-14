package com.booktranslator.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationDao {

    @Query("SELECT * FROM translations WHERE word = :word")
    suspend fun get(word: String): TranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(translation: TranslationEntity)
}
