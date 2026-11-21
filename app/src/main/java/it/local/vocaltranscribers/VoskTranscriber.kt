package it.local.vocaltranscribers

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

internal object VoskTranscriber {
    fun transcribe(modelDirectory: File, pcm16Mono: ByteArray): String {
        val model = Model(modelDirectory.absolutePath)
        return try {
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            try {
                var offset = 0
                while (offset < pcm16Mono.size) {
                    val length = minOf(CHUNK_BYTES, pcm16Mono.size - offset)
                    recognizer.acceptWaveForm(pcm16Mono.copyOfRange(offset, offset + length), length)
                    offset += length
                }
                JSONObject(recognizer.finalResult).optString("text").trim()
            } finally {
                recognizer.close()
            }
        } finally {
            model.close()
        }
    }

    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_BYTES = 8_000
}
