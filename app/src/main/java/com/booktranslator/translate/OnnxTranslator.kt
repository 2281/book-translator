package com.booktranslator.translate

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.extensions.OrtxPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.LongBuffer

/**
 * Оффлайн-переводчик en -> ru на ONNX-модели Helsinki-NLP opus-mt-en-ru.
 *
 * Конвейер: tok-encode.onnx (SentencePiece + remap в нумерацию модели) ->
 * encoder_model.onnx -> decoder_model.onnx (жадное декодирование) ->
 * tok-decode.onnx. Токенизаторы работают через onnxruntime-extensions.
 *
 * Модели лежат в assets/model/ и при первом запуске копируются во внутреннее
 * хранилище (нужен файловый путь для ORT).
 */
class OnnxTranslator(private val context: Context) : Translator {

    private val initMutex = Mutex()
    private var initialized = false

    private lateinit var env: OrtEnvironment
    private lateinit var tokEncode: OrtSession
    private lateinit var tokDecode: OrtSession
    private lateinit var encoder: OrtSession
    private lateinit var decoder: OrtSession

    /** Загрузить модель в память заранее (вызывается при старте приложения). */
    suspend fun warmUp() = ensureInitialized()

    private suspend fun ensureInitialized() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                env = OrtEnvironment.getEnvironment()
                val extOptions = OrtSession.SessionOptions().apply {
                    registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                }
                val modelOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    // NNAPI: по возможности исполняет граф на GPU/DSP,
                    // неподдерживаемые операции остаются на CPU
                    runCatching { addNnapi() }
                }
                tokEncode = env.createSession(assetFile("tok-encode.onnx"), extOptions)
                tokDecode = env.createSession(assetFile("tok-decode.onnx"), extOptions)
                encoder = env.createSession(assetFile("encoder_model.onnx"), modelOptions)
                decoder = env.createSession(assetFile("decoder_model.onnx"), modelOptions)
                initialized = true
            }
        }
    }

    private fun assetFile(name: String): String {
        val dir = File(context.filesDir, "model").apply { mkdirs() }
        val file = File(dir, name)
        if (!file.exists()) {
            context.assets.open("model/$name").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    override suspend fun translate(word: String): String = withContext(Dispatchers.Default) {
        ensureInitialized()

        // Токенизация: строка -> id модели (1-D int64)
        val ids: LongArray
        OnnxTensor.createTensor(env, arrayOf(word)).use { textTensor ->
            tokEncode.run(mapOf("text" to textTensor)).use { result ->
                val tensor = result.get(0) as OnnxTensor
                ids = when (val value = tensor.value) {
                    is LongArray -> value
                    else -> error("Неожиданный выход токенизатора: ${value?.javaClass}")
                }
            }
        }
        if (ids.isEmpty()) return@withContext word

        // Энкодер
        val mask = LongArray(ids.size) { 1L }
        val inputIdsTensor = longTensor(ids)
        val maskTensor = longTensor(mask)
        val encResult = encoder.run(
            mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to maskTensor
            )
        )
        // encResult.close() в finally закрывает и этот тензор
        val encHidden = encResult.get(0) as OnnxTensor

        try {
            // Жадное декодирование
            val output = mutableListOf<Long>()
            var decIds = longArrayOf(PAD_ID)
            var finished = false
            var steps = 0
            while (steps < MAX_DECODE_STEPS && !finished) {
                steps++
                val decIdsTensor = longTensor(decIds)
                try {
                    decoder.run(
                        mapOf(
                            "input_ids" to decIdsTensor,
                            "encoder_hidden_states" to encHidden,
                            "encoder_attention_mask" to maskTensor
                        ),
                        setOf("logits")
                    ).use { decResult ->
                        val logitsTensor = decResult.get("logits").get() as OnnxTensor
                        val next = argmaxLastToken(logitsTensor, decIds.size)
                        if (next == EOS_ID) {
                            finished = true
                        } else {
                            output.add(next)
                            decIds = decIds + next
                        }
                    }
                } finally {
                    decIdsTensor.close()
                }
            }

            // Детокенизация: id модели -> строка
            longTensor(output.toLongArray(), longArrayOf(output.size.toLong())).use { idsTensor ->
                tokDecode.run(mapOf("ids" to idsTensor)).use { result ->
                    val text = (result.get(0) as OnnxTensor).value as Array<*>
                    text.firstOrNull() as? String ?: word
                }
            }
        } finally {
            encResult.close()
            inputIdsTensor.close()
            maskTensor.close()
        }
    }

    /** Логиты [1, seqLen, vocab]: индекс максимума в последней позиции. */
    private fun argmaxLastToken(tensor: OnnxTensor, seqLen: Int): Long {
        val buffer = tensor.floatBuffer
        val vocab = buffer.remaining() / seqLen
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        val offset = (seqLen - 1) * vocab
        for (i in 0 until vocab) {
            val value = buffer.get(offset + i)
            if (value > bestValue) {
                bestValue = value
                best = i
            }
        }
        return best.toLong()
    }

    private fun longTensor(values: LongArray, shape: LongArray = longArrayOf(1, values.size.toLong())): OnnxTensor {
        val buffer: LongBuffer = ByteBuffer
            .allocateDirect(values.size * 8)
            .order(java.nio.ByteOrder.nativeOrder())
            .asLongBuffer()
            .put(values)
        buffer.rewind()
        return OnnxTensor.createTensor(env, buffer, shape)
    }

    private companion object {
        const val PAD_ID = 62517L   // decoder_start_token_id модели
        const val EOS_ID = 0L
        const val MAX_DECODE_STEPS = 40
    }
}
