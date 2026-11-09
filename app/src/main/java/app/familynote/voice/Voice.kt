package app.familynote.voice

import android.content.Context
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import android.speech.tts.TextToSpeech as AndroidTts

/** Speaking notes aloud and turning speech into a reply. */
interface Voice {
    fun speak(text: String)
    fun stopSpeaking()

    /**
     * Starts listening once. [onText] receives partial results (final = false) and the
     * final result (final = true). [onError] is called if recognition is unavailable.
     */
    fun listen(onText: (text: String, final: Boolean) -> Unit, onError: (String) -> Unit)
    fun stopListening()

    fun release()
}

class FakeVoice : Voice {
    val spoken = mutableListOf<String>()
    override fun speak(text: String) { spoken += text }
    override fun stopSpeaking() = Unit
    override fun listen(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) = onError("unavailable")
    override fun stopListening() = Unit
    override fun release() = Unit
}

/**
 * Pleos on-device TTS/STT. If Pleos TTS cannot initialize, falls back to the platform
 * TextToSpeech so reading aloud still works on other devices.
 */
class PleosVoice(private val context: Context) : Voice {

    private var tts: PleosTts? = null
    private var ttsReady = false
    private var fallback: AndroidTts? = null
    private var fallbackReady = false
    private var pending: String? = null

    private var stt: PleosStt? = null
    private var sttListener: ResultListener? = null

    init {
        runCatching {
            PleosTts(context.applicationContext, TtsMode.ON_DEVICE).also { t ->
                tts = t
                t.initialize(object : TtsInit {
                    override fun onSuccess() {
                        runCatching { t.setLocale(Locale.KOREAN) }
                        ttsReady = true
                        pending?.let { speak(it) }
                        pending = null
                    }

                    override fun onFailure(error: TtsError) {
                        Log.w(TAG, "Pleos TTS init failed: $error; using platform TTS")
                        startFallback()
                    }
                })
            }
        }.onFailure {
            Log.w(TAG, "Pleos TTS unavailable", it)
            startFallback()
        }
    }

    private fun startFallback() {
        if (fallback != null) return
        fallback = AndroidTts(context.applicationContext) { status ->
            fallbackReady = status == AndroidTts.SUCCESS
            if (fallbackReady) {
                fallback?.language = Locale.KOREAN
                pending?.let { speak(it) }
                pending = null
            }
        }
    }

    override fun speak(text: String) {
        when {
            ttsReady -> runCatching { tts?.speak(text, true) }.onFailure { Log.w(TAG, "speak failed", it) }
            fallbackReady -> fallback?.speak(text, AndroidTts.QUEUE_FLUSH, null, "note")
            else -> pending = text
        }
    }

    override fun stopSpeaking() {
        pending = null
        runCatching { tts?.stop() }
        fallback?.stop()
    }

    override fun listen(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        stopListening()
        val recognizer = runCatching { PleosStt(context.applicationContext, SttMode.ON_DEVICE) }
            .getOrElse {
                onError("음성 인식을 쓸 수 없어요")
                return
            }
        stt = recognizer
        val listener = object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) = onText(text, isFinal)
            override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onError(error: SttError) = onError("음성을 알아듣지 못했어요")
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        sttListener = listener
        recognizer.initialize(object : SttInit {
            override fun onSuccess() {
                if (stt !== recognizer) return
                runCatching {
                    recognizer.setLocale(Locale.KOREAN)
                    recognizer.addListener(listener)
                    recognizer.request()
                }.onFailure { onError("음성 인식을 시작하지 못했어요") }
            }

            override fun onFailure(error: SttError) {
                Log.w(TAG, "STT init failed: $error")
                onError("음성 인식을 쓸 수 없어요")
            }
        })
    }

    override fun stopListening() {
        val r = stt ?: return
        stt = null
        runCatching {
            r.stop()
            sttListener?.let { r.removeListener(it) }
            r.release()
        }
        sttListener = null
    }

    override fun release() {
        stopListening()
        runCatching { tts?.release() }
        fallback?.shutdown()
        tts = null
        fallback = null
    }

    companion object {
        private const val TAG = "PleosVoice"
    }
}
