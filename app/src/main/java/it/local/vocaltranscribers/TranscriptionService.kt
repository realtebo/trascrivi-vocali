package it.local.vocaltranscribers

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs audio decoding and speech recognition in a separate process so the UI can stop
 * a synchronous native Whisper decode without terminating the app itself.
 */
internal class TranscriptionService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val serviceMessenger by lazy {
        Messenger(IncomingHandler(this))
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private class IncomingHandler(private val service: TranscriptionService) :
        Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != MSG_TRANSCRIBE) return
            val replyTo = message.replyTo ?: return
            val data = message.data
            val uriText = data.getString(KEY_AUDIO_URI)
            val modelKey = data.getString(KEY_MODEL)
            val modelDirectoryText = data.getString(KEY_MODEL_DIRECTORY)
            val model = AsrModel.entries.firstOrNull { it.key == modelKey }
            if (uriText == null || model == null || modelDirectoryText == null) {
                sendError(replyTo, "Richiesta di trascrizione incompleta.")
                return
            }

            val startedAt = SystemClock.elapsedRealtime()
            send(replyTo, Message.obtain(null, MSG_STARTED).apply { arg1 = Process.myPid() })
            service.worker.execute {
                try {
                    val pcm = AudioDecoder.decodeMono16Khz(service, Uri.parse(uriText))
                    val audioDurationMs = (pcm.size / 2L) * 1000L / SAMPLE_RATE
                    val directory = File(modelDirectoryText)
                    val transcript = when (model) {
                        AsrModel.VOSK -> VoskTranscriber.transcribe(directory, pcm)
                        AsrModel.WHISPER_TINY,
                        AsrModel.WHISPER_BASE,
                        AsrModel.WHISPER_SMALL,
                        AsrModel.WHISPER_MEDIUM -> WhisperTranscriber.transcribe(directory, model, pcm)
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    val response = Message.obtain(null, MSG_COMPLETE).apply {
                        setData(Bundle().apply {
                            putString(KEY_RESULT, transcript)
                            putLong(KEY_AUDIO_DURATION_MS, audioDurationMs)
                            putLong(KEY_ELAPSED_MS, elapsedMs)
                        })
                    }
                    send(replyTo, response)
                } catch (error: Exception) {
                    sendError(replyTo, error.message ?: "formato non supportato")
                }
            }
        }

        private fun sendError(replyTo: Messenger, details: String) {
            send(replyTo, Message.obtain(null, MSG_ERROR).apply {
                data = Bundle().apply { putString(KEY_ERROR, details) }
            })
        }

        private fun send(replyTo: Messenger, message: Message) {
            runCatching { replyTo.send(message) }
        }
    }

    companion object {
        const val MSG_TRANSCRIBE = 1
        const val MSG_STARTED = 2
        const val MSG_COMPLETE = 3
        const val MSG_ERROR = 4

        const val KEY_AUDIO_URI = "audio_uri"
        const val KEY_MODEL = "model"
        const val KEY_MODEL_DIRECTORY = "model_directory"
        const val KEY_RESULT = "result"
        const val KEY_AUDIO_DURATION_MS = "audio_duration_ms"
        const val KEY_ELAPSED_MS = "elapsed_ms"
        const val KEY_ERROR = "error"

        private const val SAMPLE_RATE = 16_000L
    }
}
