package me.rerere.asr.providers

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude

/**
 * Free on-device/system speech input backed by Android [SpeechRecognizer].
 *
 * On devices with Google Speech Services installed this normally uses Google's
 * recognizer. It deliberately does not require an API key and is only used for
 * push-to-talk chat input; Voice Mode still requires an ASR provider with
 * server-side endpointing.
 */
class GoogleSpeechASRController(
    private val context: Context,
    private val provider: ASRProviderSetting.GoogleSpeech,
) : ASRController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(
        ASRState(isAvailable = SpeechRecognizer.isRecognitionAvailable(context))
    )
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var userStopping = false
    private var disposed = false

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (disposed || _state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setError("Microphone permission is required")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            setError("Google/System speech recognition service is unavailable on this device")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        userStopping = false
        _state.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)

        mainHandler.post {
            if (disposed) return@post
            destroyRecognizer()
            try {
                val speechRecognizer = preferredRecognitionService()?.let { component ->
                    runCatching { SpeechRecognizer.createSpeechRecognizer(context, component) }.getOrNull()
                } ?: SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = speechRecognizer
                speechRecognizer.setRecognitionListener(listener)
                speechRecognizer.startListening(buildIntent())
            } catch (t: Throwable) {
                destroyRecognizer()
                setError(t.message ?: "Unable to start speech recognition")
            }
        }
    }

    override fun stop() {
        if (disposed) return
        userStopping = true
        _state.update { it.copy(status = ASRStatus.Stopping) }
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
                .onFailure { finishIdle() }
        }
    }

    override fun dispose() {
        disposed = true
        onTranscriptChange = null
        mainHandler.post {
            destroyRecognizer()
            _state.value = ASRState(isAvailable = SpeechRecognizer.isRecognitionAvailable(context))
        }
    }

    private fun preferredRecognitionService(): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = context.packageManager.queryIntentServices(intent, 0)
        val service = services.firstOrNull { info ->
            val packageName = info.serviceInfo?.packageName.orEmpty().lowercase()
            packageName.startsWith("com.google.") || packageName.contains("google")
        } ?: return null
        val info = service.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        provider.language.trim().takeIf { it.isNotEmpty() }?.let {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, it)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
        }

        override fun onBeginningOfSpeech() {
            _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
        }

        override fun onRmsChanged(rmsdB: Float) {
            // SpeechRecognizer commonly reports roughly -2..10 dB. Normalize for
            // RikkaHub's existing waveform UI and keep the same bounded history.
            val amplitude = ((rmsdB.coerceIn(-2f, 10f) + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _state.update { it.copy(status = ASRStatus.Stopping) }
        }

        override fun onError(error: Int) {
            // stopListening/cancel may cause ERROR_CLIENT or NO_MATCH. If the user
            // deliberately stopped after a partial transcript, don't surface it as
            // a scary failure toast.
            if (userStopping && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                finishIdle()
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> finishIdle()
                else -> setError(errorMessage(error))
            }
        }

        override fun onResults(results: Bundle?) {
            emitTranscript(results)
            finishIdle()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            emitTranscript(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun emitTranscript(bundle: Bundle?) {
        val text = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) return
        _state.update { it.copy(transcript = text, errorMessage = null) }
        onTranscriptChange?.invoke(text)
    }

    private fun finishIdle() {
        userStopping = false
        _state.update {
            it.copy(
                status = ASRStatus.Idle,
                isAvailable = SpeechRecognizer.isRecognitionAvailable(context),
                errorMessage = null,
            )
        }
    }

    private fun setError(message: String) {
        userStopping = false
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                isAvailable = SpeechRecognizer.isRecognitionAvailable(context),
                errorMessage = message,
            )
        }
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Speech recognition audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
        SpeechRecognizer.ERROR_NETWORK -> "Speech recognition network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech recognition service disconnected"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many speech recognition requests"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Selected speech language is not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Selected speech language is currently unavailable"
        else -> "Speech recognition failed ($error)"
    }
}
