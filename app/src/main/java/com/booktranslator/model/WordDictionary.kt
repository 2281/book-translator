package com.booktranslator.model

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Словарная статья одного чтения словоформы (одна часть речи). */
data class WordEntry(
    val lemma: String,
    val pos: String,
    val tags: String,
    val translations: List<String>
)

/** Устойчивая фраза: последовательность лемм и русский перевод.
 * ruM/ruF/ruN/ruPl — варианты перевода в прошедшем времени по родам
 * (если перевод содержит глагол, который удалось проспрягать при сборке). */
data class PhraseEntry(
    val lemmas: List<String>,
    val translation: String,
    val source: String,
    val ruM: String? = null,
    val ruF: String? = null,
    val ruN: String? = null,
    val ruPl: String? = null
)

/**
 * Провайдер словарной базы (assets/dict/dictionary.db, собирается из
 * Викисловаря — см. tools/wiktionary/build_dictionary.py).
 * База копируется из assets во внутреннее хранилище при первом обращении;
 * сама по себе в память не грузится — из неё точечно читаются подмножества
 * (слова конкретной книги) и таблица фраз (целиком, она небольшая).
 */
class WordDictionaryProvider(private val context: Context) {

    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null

    /** Таблица фраз грузится один раз и живёт, пока жив процесс. */
    @Volatile
    var phrases: List<PhraseEntry>? = null
        private set

    private suspend fun openDb(): SQLiteDatabase = mutex.withLock {
        db?.let { return it }
        withContext(Dispatchers.IO) {
            val out = File(context.filesDir, DB_NAME)
            // Перекопируем, если копия отсутствует или словарь в assets
            // обновился. Сравниваем user_version из заголовка SQLite:
            // размер файла при обновлении данных может совпасть
            val assetVersion = context.assets.open("dict/$DB_NAME").use { readUserVersion(it) }
            val copyVersion = if (out.exists()) {
                out.inputStream().use { readUserVersion(it) }
            } else -1L
            if (copyVersion != assetVersion) {
                context.assets.open("dict/$DB_NAME").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
            SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY)
                .also { db = it }
        }
    }

    /** user_version из заголовка SQLite (big-endian int по смещению 60). */
    private fun readUserVersion(input: InputStream): Long {
        val header = ByteArray(64)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) break
            read += n
        }
        if (read < header.size) return -1L
        return ((header[60].toLong() and 0xFF) shl 24) or
            ((header[61].toLong() and 0xFF) shl 16) or
            ((header[62].toLong() and 0xFF) shl 8) or
            (header[63].toLong() and 0xFF)
    }

    /** Все фразы-идиомы (индекс по первой лемме строится у потребителя). */
    suspend fun loadPhrases(): List<PhraseEntry> {
        phrases?.let { return it }
        val result = withContext(Dispatchers.IO) {
            val list = mutableListOf<PhraseEntry>()
            openDb().rawQuery(
                "SELECT lemmas, ru, src, ru_m, ru_f, ru_n, ru_pl FROM phrases", null
            ).use { c ->
                while (c.moveToNext()) {
                    list += PhraseEntry(
                        lemmas = c.getString(0).split(" "),
                        translation = c.getString(1),
                        source = c.getString(2),
                        ruM = c.getString(3),
                        ruF = c.getString(4),
                        ruN = c.getString(5),
                        ruPl = c.getString(6)
                    )
                }
            }
            list
        }
        phrases = result
        return result
    }

    /** Статьи для множества словоформ (lowercase). Возвращает form → статьи. */
    suspend fun lookupForms(forms: Set<String>): Map<String, List<WordEntry>> =
        withContext(Dispatchers.IO) {
            val result = HashMap<String, MutableList<WordEntry>>(forms.size)
            val database = openDb()
            // SQLite ограничивает число параметров — режем на чанки
            val chunk = ArrayList<String>(900)
            fun flush() {
                if (chunk.isEmpty()) return
                val placeholders = chunk.joinToString(",") { "?" }
                database.rawQuery(
                    "SELECT form, pos, lemma, tags, tr FROM words WHERE form IN ($placeholders)",
                    chunk.toTypedArray()
                ).use { c ->
                    while (c.moveToNext()) {
                        val trJson = JSONArray(c.getString(4))
                        val entry = WordEntry(
                            lemma = c.getString(2),
                            pos = c.getString(1),
                            tags = c.getString(3),
                            translations = List(trJson.length()) { trJson.getString(it) }
                        )
                        result.getOrPut(c.getString(0)) { mutableListOf() }.add(entry)
                    }
                }
                chunk.clear()
            }
            for (f in forms) {
                chunk.add(f)
                if (chunk.size >= 900) flush()
            }
            flush()
            result
        }

    /** Род (m/f/n) русских слов (таблица ru_gender) — для согласования
     * прилагательных и определения рода подлежащего. */
    suspend fun lookupGenders(words: Set<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val result = HashMap<String, String>(words.size)
            val database = openDb()
            val chunk = ArrayList<String>(900)
            fun flush() {
                if (chunk.isEmpty()) return
                val placeholders = chunk.joinToString(",") { "?" }
                database.rawQuery(
                    "SELECT word, gender FROM ru_gender WHERE word IN ($placeholders)",
                    chunk.toTypedArray()
                ).use { c ->
                    while (c.moveToNext()) result[c.getString(0)] = c.getString(1)
                }
                chunk.clear()
            }
            for (w in words) {
                chunk.add(w)
                if (chunk.size >= 900) flush()
            }
            flush()
            result
        }

    companion object {
        const val DB_NAME = "dictionary.db"
    }
}

/**
 * Словарь конкретной книги: только те словоформы, что встречаются в её
 * тексте. Живёт в памяти, пока книга открыта; поиск O(1).
 * Также даёт лемматизацию (форма → лемма) для матчинга идиом.
 */
class BookDictionary(
    private val entries: Map<String, List<WordEntry>>,
    private val lemmaOf: Map<String, String>,
    /** Род русских существительных-переводов (m/f/n). */
    private val genders: Map<String, String>
) {
    /** Статьи для словоформы (lowercase), или null, если слова нет в словаре. */
    fun lookup(form: String): List<WordEntry>? = entries[form]

    /** Лемма словоформы; если слово неизвестно словарю — сама форма. */
    fun lemma(form: String): String = lemmaOf[form] ?: form

    /** Род русского существительного ("m"/"f"/"n") или null. */
    fun ruGender(ruWord: String): String? = genders[ruWord]

    companion object {
        /** Собирает словарь книги: сканирует абзацы, выбирает статьи из базы. */
        suspend fun build(
            provider: WordDictionaryProvider,
            paragraphs: List<String>
        ): BookDictionary {
            val forms = HashSet<String>()
            for (p in paragraphs) {
                for (span in WordSplitter.split(p)) {
                    forms.add(span.word.lowercase())
                }
            }
            val found = provider.lookupForms(forms)
            val lemmaOf = HashMap<String, String>(forms.size)
            for ((form, list) in found) {
                lemmaOf[form] = list.minByOrNull { it.lemma.length }?.lemma ?: form
            }
            // род существительных-переводов: согласование прилагательных
            // и род подлежащего у глаголов прош. вр.
            val ruNouns = HashSet<String>()
            for ((_, list) in found) {
                for (e in list) {
                    if (e.pos != "noun") continue
                    for (tr in e.translations) ruNouns.add(tr.substringBefore("|"))
                }
            }
            val genders = provider.lookupGenders(ruNouns)
            return BookDictionary(found, lemmaOf, genders)
        }
    }
}
