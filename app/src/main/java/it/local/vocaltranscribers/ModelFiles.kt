package it.local.vocaltranscribers

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal enum class AsrModel(val key: String, val label: String) {
    VOSK("vosk", "Vosk · 50 MB · qualità scarsa"),
    WHISPER_TINY("whisper-tiny", "Whisper tiny · 104 MB · qualità molto bassa"),
    WHISPER_BASE("whisper-base", "Whisper base · 160 MB · qualità bassa"),
    WHISPER_SMALL("whisper-small", "Whisper small · 375 MB · qualità media"),
    WHISPER_MEDIUM("whisper-medium", "Whisper medium · 946 MB · qualità alta");

    companion object {
        fun fromSpinnerPosition(position: Int): AsrModel? = entries.getOrNull(position - 1)
    }
}

internal object ModelFiles {
    private const val VOSK_MODEL = "vosk-model-small-it-0.22"
    private const val VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
    private const val VOSK_ARCHIVE_BYTES = 49_665_141L
    private const val VOSK_ARCHIVE_SHA256 = "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"

    private const val WHISPER_TINY_REVISION = "65176e2deb88badc814a94058666cadccc29b61c"
    private const val WHISPER_TINY_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/$WHISPER_TINY_REVISION"
    private const val WHISPER_ENCODER_BYTES = 12_937_772L
    private const val WHISPER_ENCODER_SHA256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
    private const val WHISPER_DECODER_BYTES = 89_855_401L
    private const val WHISPER_DECODER_SHA256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
    private const val WHISPER_TOKENS_BYTES_MIN = 500_000L
    private const val WHISPER_BASE_REVISION = "bb53ee204431c90d314c1cc08d28d23e5b7927cc"
    private const val WHISPER_BASE_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/$WHISPER_BASE_REVISION"
    private const val WHISPER_BASE_ENCODER_BYTES = 29_120_534L
    private const val WHISPER_BASE_ENCODER_SHA256 = "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"
    private const val WHISPER_BASE_DECODER_BYTES = 130_672_026L
    private const val WHISPER_BASE_DECODER_SHA256 = "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"
    private const val WHISPER_BASE_TOKENS_BYTES_MIN = 500_000L
    private const val WHISPER_SMALL_REVISION = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309"
    private const val WHISPER_SMALL_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/$WHISPER_SMALL_REVISION"
    private const val WHISPER_SMALL_ENCODER_BYTES = 112_442_483L
    private const val WHISPER_SMALL_ENCODER_SHA256 = "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"
    private const val WHISPER_SMALL_DECODER_BYTES = 262_226_114L
    private const val WHISPER_SMALL_DECODER_SHA256 = "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"
    private const val WHISPER_SMALL_TOKENS_BYTES_MIN = 500_000L
    private const val WHISPER_MEDIUM_REVISION = "5ce006117ee462612717129f32b0b23a88af0c96"
    private const val WHISPER_MEDIUM_REPO = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-medium/resolve/$WHISPER_MEDIUM_REVISION"
    private const val WHISPER_MEDIUM_ENCODER_BYTES = 374_194_129L
    private const val WHISPER_MEDIUM_ENCODER_SHA256 = "3a357b9d1ece665fac58ef714dd7e00306bab7cdfb0b39146457e5a19ca71752"
    private const val WHISPER_MEDIUM_DECODER_BYTES = 571_049_735L
    private const val WHISPER_MEDIUM_DECODER_SHA256 = "9de5f1b0c420e6bb01a7bcc966f2c73dbc75383b4c6005b4c024d44f8cda7bb5"
    private const val WHISPER_MEDIUM_TOKENS_BYTES_MIN = 500_000L

    fun isInstalled(context: Context, model: AsrModel): Boolean =
        isReady(File(context.filesDir, "models/${model.key}"), model)

    fun prepare(context: Context, model: AsrModel, onProgress: (String) -> Unit): File {
        val root = File(context.filesDir, "models")
        root.mkdirs()
        root.listFiles()
            ?.filter { it.name.startsWith(".install-") }
            ?.forEach { staging ->
                check(staging.deleteRecursively()) { "Non riesco a rimuovere un download interrotto" }
            }
        val installed = File(root, model.key)
        if (isReady(installed, model)) {
            removeOtherModel(root, model)
            return installed
        }

        val staging = File(root, ".install-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "Non riesco a creare lo spazio temporaneo per il modello" }
        try {
            val prepared = when (model) {
                AsrModel.VOSK -> prepareVosk(staging, onProgress)
                AsrModel.WHISPER_TINY -> prepareWhisper(staging, "tiny", onProgress)
                AsrModel.WHISPER_BASE -> prepareWhisper(staging, "base", onProgress)
                AsrModel.WHISPER_SMALL -> prepareWhisper(staging, "small", onProgress)
                AsrModel.WHISPER_MEDIUM -> prepareWhisper(staging, "medium", onProgress)
            }
            check(isReady(prepared, model)) { "Il modello scaricato non è completo" }
            if (installed.exists()) {
                check(installed.deleteRecursively()) { "Non riesco a sostituire il modello selezionato" }
            }
            check(prepared.renameTo(installed)) { "Non riesco a installare il modello selezionato" }
            removeOtherModel(root, model)
            return installed
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun prepareVosk(staging: File, onProgress: (String) -> Unit): File {
        val archive = File(staging, "vosk-model.zip")
        download(
            VOSK_URL,
            archive,
            VOSK_ARCHIVE_BYTES,
            VOSK_ARCHIVE_SHA256,
            "Scarico il modello Vosk",
            onProgress,
        )
        val extractedRoot = File(staging, "extracted")
        extractZip(archive, extractedRoot)
        val model = File(extractedRoot, VOSK_MODEL)
        check(File(model, "am/final.mdl").isFile) { "L'archivio Vosk non contiene il modello previsto" }
        File(model, ".ready").writeText("vosk-model-small-it-0.22")
        return model
    }

    private fun prepareWhisper(staging: File, size: String, onProgress: (String) -> Unit): File {
        val model = File(staging, "whisper")
        check(model.mkdirs()) { "Non riesco a creare la cartella Whisper" }
        val prefix = size
        val repo = when (size) {
            "tiny" -> WHISPER_TINY_REPO
            "base" -> WHISPER_BASE_REPO
            "small" -> WHISPER_SMALL_REPO
            else -> WHISPER_MEDIUM_REPO
        }
        val encoderBytes = when (size) {
            "tiny" -> WHISPER_ENCODER_BYTES
            "base" -> WHISPER_BASE_ENCODER_BYTES
            "small" -> WHISPER_SMALL_ENCODER_BYTES
            else -> WHISPER_MEDIUM_ENCODER_BYTES
        }
        val encoderSha = when (size) {
            "tiny" -> WHISPER_ENCODER_SHA256
            "base" -> WHISPER_BASE_ENCODER_SHA256
            "small" -> WHISPER_SMALL_ENCODER_SHA256
            else -> WHISPER_MEDIUM_ENCODER_SHA256
        }
        val decoderBytes = when (size) {
            "tiny" -> WHISPER_DECODER_BYTES
            "base" -> WHISPER_BASE_DECODER_BYTES
            "small" -> WHISPER_SMALL_DECODER_BYTES
            else -> WHISPER_MEDIUM_DECODER_BYTES
        }
        val decoderSha = when (size) {
            "tiny" -> WHISPER_DECODER_SHA256
            "base" -> WHISPER_BASE_DECODER_SHA256
            "small" -> WHISPER_SMALL_DECODER_SHA256
            else -> WHISPER_MEDIUM_DECODER_SHA256
        }
        val tokensMin = when (size) {
            "tiny" -> WHISPER_TOKENS_BYTES_MIN
            "base" -> WHISPER_BASE_TOKENS_BYTES_MIN
            "small" -> WHISPER_SMALL_TOKENS_BYTES_MIN
            else -> WHISPER_MEDIUM_TOKENS_BYTES_MIN
        }
        val totalWeightsBytes = encoderBytes + decoderBytes
        val encoderProgressEnd = (99L * encoderBytes / totalWeightsBytes).toInt()
        val encoderProgressRange = 0..encoderProgressEnd
        val decoderProgressRange = encoderProgressEnd..99
        download(
            "$repo/$prefix-encoder.int8.onnx?download=true",
            File(model, "$prefix-encoder.int8.onnx"),
            encoderBytes,
            encoderSha.ifBlank { null },
            "Scarico Whisper $prefix · encoder",
            onProgress,
            encoderProgressRange,
        )
        download(
            "$repo/$prefix-decoder.int8.onnx?download=true",
            File(model, "$prefix-decoder.int8.onnx"),
            decoderBytes,
            decoderSha.ifBlank { null },
            "Scarico Whisper $prefix · decoder",
            onProgress,
            decoderProgressRange,
        )
        download(
            "$repo/$prefix-tokens.txt?download=true",
            File(model, "$prefix-tokens.txt"),
            expectedBytes = null,
            expectedSha256 = null,
            label = "Scarico Whisper $prefix · vocabolario",
            onProgress = onProgress,
            percentRange = 99..99,
        )
        check(File(model, "$prefix-tokens.txt").length() >= tokensMin) {
            "Il vocabolario Whisper scaricato è incompleto"
        }
        val revision = when (size) {
            "tiny" -> WHISPER_TINY_REVISION
            "base" -> WHISPER_BASE_REVISION
            "small" -> WHISPER_SMALL_REVISION
            else -> WHISPER_MEDIUM_REVISION
        }
        File(model, ".ready").writeText(revision)
        onProgress("Scarico Whisper $prefix · completato: 100%")
        return model
    }

    private fun isReady(directory: File, model: AsrModel): Boolean {
        if (!File(directory, ".ready").isFile) return false
        val expectedRevision = when (model) {
            AsrModel.VOSK -> VOSK_MODEL
            AsrModel.WHISPER_TINY -> WHISPER_TINY_REVISION
            AsrModel.WHISPER_BASE -> WHISPER_BASE_REVISION
            AsrModel.WHISPER_SMALL -> WHISPER_SMALL_REVISION
            AsrModel.WHISPER_MEDIUM -> WHISPER_MEDIUM_REVISION
        }
        if (File(directory, ".ready").readText() != expectedRevision) return false
        return when (model) {
            AsrModel.VOSK -> File(directory, "am/final.mdl").isFile &&
                File(directory, "graph/HCLr.fst").isFile
            AsrModel.WHISPER_TINY -> whisperReady(directory, "tiny", WHISPER_ENCODER_BYTES, WHISPER_DECODER_BYTES, WHISPER_TOKENS_BYTES_MIN)
            AsrModel.WHISPER_BASE -> whisperReady(directory, "base", WHISPER_BASE_ENCODER_BYTES, WHISPER_BASE_DECODER_BYTES, WHISPER_BASE_TOKENS_BYTES_MIN)
            AsrModel.WHISPER_SMALL -> whisperReady(directory, "small", WHISPER_SMALL_ENCODER_BYTES, WHISPER_SMALL_DECODER_BYTES, WHISPER_SMALL_TOKENS_BYTES_MIN)
            AsrModel.WHISPER_MEDIUM -> whisperReady(directory, "medium", WHISPER_MEDIUM_ENCODER_BYTES, WHISPER_MEDIUM_DECODER_BYTES, WHISPER_MEDIUM_TOKENS_BYTES_MIN)
        }
    }

    private fun whisperReady(directory: File, prefix: String, encoderBytes: Long, decoderBytes: Long, tokensMin: Long): Boolean =
        File(directory, "$prefix-encoder.int8.onnx").length() == encoderBytes &&
            File(directory, "$prefix-decoder.int8.onnx").length() == decoderBytes &&
            File(directory, "$prefix-tokens.txt").length() >= tokensMin

    private fun removeOtherModel(root: File, selected: AsrModel) {
        AsrModel.entries.filter { it != selected }.forEach { other ->
            val directory = File(root, other.key)
            if (directory.exists()) {
                check(directory.deleteRecursively()) { "Non riesco a rimuovere il modello ${other.label}" }
            }
        }
    }

    private fun download(
        url: String,
        destination: File,
        expectedBytes: Long?,
        expectedSha256: String?,
        label: String,
        onProgress: (String) -> Unit,
        percentRange: IntRange? = null,
    ) {
        destination.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "Vocali/0.1.0 Android")
        }
        try {
            check(connection.responseCode in 200..299) { "$label: HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var nextUpdate = 0L
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        if (received >= nextUpdate) {
                            val filePercent = expectedBytes?.let { (received * 100 / it).coerceAtMost(100) }
                            val percent = when {
                                filePercent != null && percentRange != null ->
                                    percentRange.first + (percentRange.last - percentRange.first) * filePercent / 100
                                filePercent != null -> filePercent
                                percentRange != null -> percentRange.first
                                else -> null
                            }
                            onProgress(if (percent == null) label else "$label: $percent%")
                            nextUpdate = received + 1024 * 1024
                        }
                    }
                }
            }
            if (expectedBytes != null) {
                check(received == expectedBytes) {
                    "$label: dimensione inattesa ($received byte; attesi $expectedBytes byte)"
                }
            }
            if (expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expectedSha256) { "$label: verifica SHA-256 non riuscita" }
            }
            onProgress(percentRange?.let { "$label: completato: ${it.last}%" } ?: "$label: completato")
        } finally {
            connection.disconnect()
        }
    }

    private fun extractZip(archive: File, destination: File) {
        check(destination.mkdirs()) { "Non riesco a creare la cartella temporanea Vosk" }
        val root = destination.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (entry != null) {
                val target = File(destination, entry.name).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Archivio Vosk non valido" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
