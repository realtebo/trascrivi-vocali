package it.local.vocaltranscribers

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

internal object WhisperTranscriber {
    fun transcribe(modelDirectory: File, model: AsrModel, pcm16Mono: ByteArray): String {
        require(pcm16Mono.size >= 2) { "L'audio non contiene campioni" }
        val prefix = when (model) {
            AsrModel.WHISPER_TINY -> "tiny"
            AsrModel.WHISPER_BASE -> "base"
            AsrModel.WHISPER_SMALL -> "small"
            AsrModel.WHISPER_MEDIUM -> "medium"
            AsrModel.VOSK -> error("Whisper non può usare il modello Vosk")
        }
        val samples = FloatArray(pcm16Mono.size / 2) { index ->
            val low = pcm16Mono[index * 2].toInt() and 0xff
            val high = pcm16Mono[index * 2 + 1].toInt()
            (((high shl 8) or low).toShort().toInt() / 32768f)
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(modelDirectory, "$prefix-encoder.int8.onnx").absolutePath,
                    decoder = File(modelDirectory, "$prefix-decoder.int8.onnx").absolutePath,
                    language = "it",
                    task = "transcribe",
                ),
                tokens = File(modelDirectory, "$prefix-tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        val recognizer = OfflineRecognizer(config = config)
        return try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    private const val SAMPLE_RATE = 16_000
}
