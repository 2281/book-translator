package com.booktranslator

import android.app.Application
import com.booktranslator.model.WordDictionaryProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class BookTranslatorApp : Application() {

    /** Словарная база en→ru (Викисловарь): из неё при открытии книги
     * грузится в память словарь конкретной книги и таблица фраз. */
    lateinit var dictionaryProvider: WordDictionaryProvider
        private set

    override fun onCreate() {
        super.onCreate()
        // Инициализация PDFBox для разбора PDF (используется в parse/PdfParser)
        PDFBoxResourceLoader.init(applicationContext)
        dictionaryProvider = WordDictionaryProvider(this)
    }
}
