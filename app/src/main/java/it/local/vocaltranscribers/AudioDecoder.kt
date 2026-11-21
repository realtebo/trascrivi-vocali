package it.local.vocaltranscribers

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AudioDecoder {
    private const val TARGET_RATE = 16_000

    fun decodeMono16Khz(context: Context, uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        if (uri.scheme == "file") {
            extractor.setDataSource(uri.path ?: error("Percorso audio non valido"))
        } else {
            extractor.setDataSource(context, uri, null)
        }
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Il file non contiene una traccia audio")
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("Formato audio sconosciuto")
        if (mime == "audio/raw") {
            return try {
                decodeRaw(extractor, inputFormat)
            } finally {
                extractor.release()
            }
        }
        val codec = MediaCodec.createDecoderByType(mime)
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Buffer audio non disponibile")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val output = codec.getOutputBuffer(outputIndex) ?: error("Audio decodificato non disponibile")
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            appendResampled(output.slice().order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, channels, sampleRate, pcm)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        check(pcm.size() > 0) { "La traccia audio è vuota" }
        return pcm.toByteArray()
    }

    private fun decodeRaw(extractor: MediaExtractor, format: MediaFormat): ByteArray {
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val output = ByteArrayOutputStream()
        val packet = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            packet.clear()
            val size = extractor.readSampleData(packet, 0)
            if (size < 0) break
            packet.position(0)
            packet.limit(size)
            appendResampled(packet, encoding, channels, sampleRate, output)
            extractor.advance()
        }
        check(output.size() > 0) { "La traccia audio è vuota" }
        return output.toByteArray()
    }

    private fun appendResampled(
        bytes: ByteBuffer,
        encoding: Int,
        channels: Int,
        sourceRate: Int,
        output: ByteArrayOutputStream,
    ) {
        require(channels > 0 && sourceRate > 0) { "Parametri audio non validi" }
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * channels
        val frames = bytes.remaining() / frameSize
        val mono = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            repeat(channels) {
                sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                    bytes.getFloat()
                } else {
                    bytes.getShort() / 32768f
                }
            }
            mono[frame] = (sum / channels).coerceIn(-1f, 1f)
        }

        val step = sourceRate.toDouble() / TARGET_RATE
        var position = 0.0
        while (position < frames) {
            val left = position.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (position - left).toFloat()
            val sample = mono[left] + (mono[right] - mono[left]) * fraction
            val value = (sample * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.write(value and 0xff)
            output.write((value shr 8) and 0xff)
            position += step
        }
    }
}
