package com.booktranslator

import android.app.Application
import com.booktranslator.data.db.AppDatabase
import com.booktranslator.translate.CachingTranslator
import com.booktranslator.translate.OnnxTranslator
import com.booktranslator.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BookTranslatorApp : Application() {

    /** Переводчик уровня приложения: модель загружается в память один раз
     * и живёт, пока жив процесс. */
    lateinit var translator: Translator
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val onnxTranslator = OnnxTranslator(this)
        translator = CachingTranslator(
            AppDatabase.get(this).translationDao(),
            onnxTranslator
        )
        // Фоновый прогрев: загружаем веса модели в память сразу при старте,
        // чтобы первый тап по слову переводился без задержки
        appScope.launch {
            runCatching { onnxTranslator.warmUp() }
        }
    }
}
