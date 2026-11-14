package com.booktranslator.data.settings

/** Настройки отображения текста книги (этап 2). */
data class ReaderSettings(
    val fontFamily: AppFontFamily = AppFontFamily.SERIF,
    val fontSizeSp: Float = 18f,
    val textColor: Long = 0xFF1B1B1B,
    val backgroundColor: Long = 0xFFFFFFFF,
    val backgroundImagePath: String? = null
)

enum class AppFontFamily(val label: String) {
    SERIF("С засечками"),
    SANS("Без засечек"),
    MONO("Моноширинный")
}
