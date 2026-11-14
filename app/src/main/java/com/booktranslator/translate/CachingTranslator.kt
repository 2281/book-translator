package com.booktranslator.translate

import com.booktranslator.data.db.TranslationDao
import com.booktranslator.data.db.TranslationEntity

/** Обертка над [Translator]: сначала ищет перевод в локальном кэше Room. */
class CachingTranslator(
    private val dao: TranslationDao,
    private val delegate: Translator
) : Translator {

    override suspend fun translate(word: String): String {
        val key = word.lowercase()
        dao.get(key)?.let { return it.translation }
        val result = delegate.translate(word)
        dao.put(TranslationEntity(word = key, translation = result, createdAt = System.currentTimeMillis()))
        return result
    }
}
