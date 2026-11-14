package com.booktranslator.data.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.readerSettingsDataStore by preferencesDataStore(name = "reader_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val TEXT_COLOR = longPreferencesKey("text_color")
        val BACKGROUND_COLOR = longPreferencesKey("background_color")
        val BACKGROUND_IMAGE = stringPreferencesKey("background_image")
    }

    val settings: Flow<ReaderSettings> = context.readerSettingsDataStore.data.map { prefs ->
        ReaderSettings(
            fontFamily = prefs[Keys.FONT_FAMILY]
                ?.let { runCatching { AppFontFamily.valueOf(it) }.getOrNull() }
                ?: AppFontFamily.SERIF,
            fontSizeSp = prefs[Keys.FONT_SIZE] ?: 18f,
            textColor = prefs[Keys.TEXT_COLOR] ?: 0xFF1B1B1B,
            backgroundColor = prefs[Keys.BACKGROUND_COLOR] ?: 0xFFFFFFFF,
            backgroundImagePath = prefs[Keys.BACKGROUND_IMAGE]
        )
    }

    suspend fun setFontFamily(fontFamily: AppFontFamily) {
        context.readerSettingsDataStore.edit { it[Keys.FONT_FAMILY] = fontFamily.name }
    }

    suspend fun setFontSize(sizeSp: Float) {
        context.readerSettingsDataStore.edit { it[Keys.FONT_SIZE] = sizeSp }
    }

    suspend fun setTextColor(color: Long) {
        context.readerSettingsDataStore.edit { it[Keys.TEXT_COLOR] = color }
    }

    suspend fun setBackgroundColor(color: Long) {
        context.readerSettingsDataStore.edit { it[Keys.BACKGROUND_COLOR] = color }
    }

    /** Копирует выбранное изображение во внутреннее хранилище и ставит его фоном. */
    suspend fun setBackgroundImage(uri: Uri) = withContext(Dispatchers.IO) {
        val extension = queryDisplayName(uri)
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: "jpg"
        val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
        val file = File(dir, "bg-${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext
        context.readerSettingsDataStore.edit { it[Keys.BACKGROUND_IMAGE] = file.absolutePath }
    }

    suspend fun clearBackgroundImage() {
        context.readerSettingsDataStore.edit { it.remove(Keys.BACKGROUND_IMAGE) }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
