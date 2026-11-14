package com.booktranslator.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.booktranslator.data.settings.AppFontFamily
import com.booktranslator.data.settings.ReaderSettings

private val TEXT_COLORS = listOf(
    0xFF1B1B1B, // почти чёрный
    0xFF4A4A4A, // серый
    0xFF5B4636, // сепия
    0xFF1E3A5F, // тёмно-синий
    0xFF2E4B2E, // тёмно-зелёный
    0xFFFFFFFF  // белый
)

private val BACKGROUND_COLORS = listOf(
    0xFFFFFFFF, // белый
    0xFFF7F0DC, // сепия
    0xFFF2F2F2, // светло-серый
    0xFFDCE8DC, // мятный
    0xFF1A1A1A, // тёмный
    0xFF000000  // чёрный
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onDismiss: () -> Unit,
    onFontFamily: (AppFontFamily) -> Unit,
    onFontSize: (Float) -> Unit,
    onTextColor: (Long) -> Unit,
    onBackgroundColor: (Long) -> Unit,
    onBackgroundImage: (Uri) -> Unit,
    onClearBackgroundImage: () -> Unit
) {
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(onBackgroundImage)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Шрифт")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFontFamily.entries.forEach { family ->
                    FilterChip(
                        selected = settings.fontFamily == family,
                        onClick = { onFontFamily(family) },
                        label = { Text(family.label) }
                    )
                }
            }

            SectionTitle("Размер шрифта: ${settings.fontSizeSp.toInt()}")
            var sliderValue by remember(settings.fontSizeSp) {
                mutableFloatStateOf(settings.fontSizeSp)
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onFontSize(sliderValue) },
                valueRange = 12f..32f
            )

            SectionTitle("Цвет шрифта")
            ColorRow(colors = TEXT_COLORS, selected = settings.textColor, onSelect = onTextColor)

            SectionTitle("Цвет фона")
            ColorRow(
                colors = BACKGROUND_COLORS,
                selected = settings.backgroundColor,
                onSelect = onBackgroundColor
            )

            SectionTitle("Фоновое изображение")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text("Выбрать изображение")
                }
                if (settings.backgroundImagePath != null) {
                    TextButton(onClick = onClearBackgroundImage) {
                        Text("Убрать")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun ColorRow(colors: List<Long>, selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEach { color ->
            ColorSwatch(
                color = color,
                selected = color == selected,
                onClick = { onSelect(color) }
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}
