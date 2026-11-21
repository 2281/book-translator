# AGENTS.md

## Обзор проекта

Мобильное приложение для Android — читалка книг с функцией перевода слов
(английский → русский) по нажатию на слово. Описание продукта — в `README.md`,
план реализации по этапам — в `ROADMAP.md`.

Ключевая особенность: оффлайн-перевод слов моделью Helsinki-NLP
`opus-mt-en-ru`, конвертированной в ONNX, выполнение на устройстве через
ONNX Runtime.

## Технологический стек

- **Язык:** Kotlin 2.0.x, JVM target 17 (OpenJDK 17).
- **UI:** Jetpack Compose (Material 3).
- **Сборка:** Gradle 8.10.2 (wrapper), Android Gradle Plugin 8.7.x,
  compileSdk/targetSdk 35, minSdk 24.
- **Хранение:** Room (каталог книг, кэш переводов), DataStore (настройки).
- **Форматы книг:** txt, fb2, epub — свои парсеры без зависимостей;
  pdf (с текстовым слоем) — `com.tom-roush:pdfbox-android:2.0.27.0`
  (init в `BookTranslatorApp.onCreate`).
- **Перевод:** ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android:1.20.0`),
  токенизация SentencePiece как custom ops из
  `onnxruntime-extensions-android:0.13.0` (класс `OrtxPackage`).
  Модель: Helsinki-NLP `opus-mt-en-ru` → ONNX (encoder + decoder +
  decoder_with_past, int8). Декодирование с KV-кэшем: шаг 0 через
  `decoder_model.onnx` (выдаёт present.*, включая кросс-KV энкодера),
  далее по одному токену через `decoder_with_past_model.onnx`.
  ВАЖНО: у opus-mt нумерация id в vocab.json отличается от нумерации внутри
  .spm — токенизаторные графы содержат remap-таблицы (см. `tools/`).
- **Жизненный цикл модели:** переводчик — синглтон в `BookTranslatorApp`,
  веса прогреваются в фоне при старте приложения и живут, пока жив процесс.
  Инференс на CPU (4 потока). NNAPI сознательно отключён
  (`USE_NNAPI = false` в `OnnxTranslator`): на устройствах без вендорских
  NNAPI-драйверов ops падают на nnapi-reference, который медленнее
  CPU-ядер ONNX Runtime.
- **Android SDK:** `~/Android/Sdk` (platform android-35, build-tools 34.0.0).

## Сборка и тестирование

```bash
./gradlew assembleDebug          # собрать debug APK (app/build/outputs/apk/debug/)
./gradlew assembleRelease        # release APK (на этапе 5 — подписанный)
./gradlew installDebug           # установить на подключённое устройство (adb)
./gradlew test                   # unit-тесты
./gradlew connectedAndroidTest   # инструментальные тесты (нужно устройство/эмулятор)
```

## Структура кода

(Заполняется по мере появления кода; обновлять при изменениях.)

- `app/` — единственный модуль приложения, пакет `com.booktranslator`.
  - `MainActivity.kt` — точка входа, навигация (каталог ↔ чтение).
  - `model/BookContent.kt` — единое представление книги (главы `Chapter` +
    абзацы).
  - `parse/` — разбор файлов в `BookContent`: `BookParser` (диспетчер,
    txt + список поддерживаемых форматов), `Fb2Parser` (XmlPullParser),
    `EpubParser` (ZIP + OPF/spine + XHTML), `PdfParser` (PDFBox-Android,
    текстовый слой).
  - `data/db/` — Room: `BookEntity`, `BookDao`, `AppDatabase`.
  - `data/BookRepository.kt` — импорт файлов, позиция чтения, удаление.
  - `data/settings/` — настройки чтения (`ReaderSettings`, `SettingsRepository`
    на DataStore): шрифт, размер, цвета, фоновое изображение.
  - `translate/` — перевод: интерфейс `Translator`, `OnnxTranslator`
    (инференс ONNX: tok-encode → encoder → жадный декодер с KV-кэшем →
    tok-decode), `CachingTranslator` (кэш в Room).
  - `model/WordSpan.kt` — разбиение текста на кликабельные слова.
  - `model/WordDictionary.kt` — словарный уровень: `WordDictionaryProvider`
    (база `assets/dict/dictionary.db`, копирование в filesDir с перезаписью
    при изменении размера) и `BookDictionary` (словарь открытой книги
    в памяти: словоформы книги → статьи, лемматизация).
  - `model/DictionaryLookup.kt` — выбор словарного перевода (один вариант)
    с учётом времени/числа и рода по местоимению слева в предложении.
  - `model/IdiomDictionary.kt` — устойчивые выражения (таблица phrases
    словарной базы; ручной `assets/idioms.json` влит в базу на этапе
    сборки), поиск самого длинного совпадения при тапе по ЛЕММАМ
    (а не точным словоформам), родовые варианты перевода фразы
    (ru_m/ru_f/ru_n/ru_pl) по местоимению слева от фразы.
  - `ui/catalog/` — экран каталога (список, добавление, удаление книг).
  - `ui/reader/` — экран чтения (прокрутка, сохранение позиции,
    панель настроек `ReaderSettingsSheet`, оглавление с переходом к главе,
    `InterlinearParagraph` — собственная раскладка абзаца: кликабельные
    слова, подстрочный перевод под словом/фразой; при наложении
    подстрочников виден перевод последнего тапнутого, `SpanTranslation.seq`).
  - `app/src/main/assets/model/` — int8 ONNX-модели encoder/decoder/
    decoder_with_past + токенизаторные графы tok-encode/tok-decode
    (всего ~220 МБ).
  - `app/src/main/assets/dict/` — словарная база SQLite en→ru (~12.5 МБ,
    хранится несжатой — `noCompress "db"` в build.gradle.kts).
- `tools/` — инструменты конвертации модели (venv, скрипты
  `build_tokenizers.py`, `test_translate.py`, `test_translate_past.py`,
  исходные файлы HF в `tools/hf/`).
  Не нужны для сборки приложения.
- `tools/wiktionary/` — сборка словарной базы из дампов kaikki.org
  (`build_dictionary.py`; дампы `*.jsonl.gz` и итоговый `dictionary.db`
  в .gitignore — пересобираются скриптом, итог копируется в
  `app/src/main/assets/dict/`).
- `samples/` — тестовые файлы для эмулятора (`sample-en.txt`,
  `paper-texture.png`).
  - `ui/theme/Theme.kt` — тема Material 3.

## Соглашения

- Основной язык документации, комментариев и сообщений — **русский**.
- Придерживаться требований из `README.md` и плана из `ROADMAP.md`;
  не выдумывать функциональность, которой там нет.
- При изменении стека, команд сборки или структуры кода — обновлять этот файл.
- Все форматы книг приводятся к единому внутреннему представлению
  (книга → главы → абзацы → слова); читалка и перевод не зависят от формата.
- Git-репозиторий, основная ветка — `master`. Коммиты и сообщения коммитов — на русском.
