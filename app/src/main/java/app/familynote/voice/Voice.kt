package app.familynote.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
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
}

class FakeVoice : Voice {
    val spoken = mutableListOf<String>()
    override fun speak(text: String) { spoken += text }
    override fun stopSpeaking() = Unit
    override fun listen(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) = onError("unavailable")
    override fun stopListening() = Unit
}

/**
 * Pleos on-device TTS/STT. If Pleos TTS cannot initialize, falls back to the platform
 * TextToSpeech so reading aloud still works on other devices.
 *
 * All public methods are called on the main thread, and every SDK callback is posted back
 * to the main thread before touching state, so no field is shared across threads.
 */
class PleosVoice(private val context: Context) : Voice {

    private val main = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

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
                    override fun onSuccess() = onMain {
                        runCatching { t.setLocale(Locale.KOREAN) }
                        ttsReady = true
                        pending?.let { speak(it) }
                        pending = null
                    }

                    override fun onFailure(error: TtsError) = onMain {
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
            onMain {
                fallbackReady = status == AndroidTts.SUCCESS
                if (fallbackReady) {
                    fallback?.language = Locale.KOREAN
                    pending?.let { speak(it) }
                    pending = null
                }
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
        // Results for a recognizer that was stopped or replaced are dropped.
        fun live(block: () -> Unit) = onMain { if (stt === recognizer) block() }
        val listener = object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) = live { onText(text, isFinal) }
            override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onError(error: SttError) = live { onError("음성을 알아듣지 못했어요") }
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
        }
        sttListener = listener
        recognizer.initialize(object : SttInit {
            override fun onSuccess() = live {
                runCatching {
                    recognizer.setLocale(Locale.KOREAN)
                    recognizer.addListener(listener)
                    recognizer.request()
                }.onFailure { onError("음성 인식을 시작하지 못했어요") }
            }

            override fun onFailure(error: SttError) = live {
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

    companion object {
        private const val TAG = "PleosVoice"
    }
}
