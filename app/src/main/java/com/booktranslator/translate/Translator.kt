package com.booktranslator.translate

/** Источник перевода одного слова. Реализация подменяема (ONNX, онлайн и т.п.). */
interface Translator {
    suspend fun translate(word: String): String
}
