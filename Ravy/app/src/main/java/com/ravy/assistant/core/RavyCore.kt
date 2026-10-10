package com.ravy.assistant.core

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.ravy.assistant.action.ActionEngine
import com.ravy.assistant.action.ActionStatus
import com.ravy.assistant.action.ActionType
import com.ravy.assistant.action.RavyAction
import com.ravy.assistant.brain.RavyBrain
import com.ravy.assistant.history.RavyHistoryStore
import com.ravy.assistant.language.RavyLanguage
import com.ravy.assistant.settings.RavySettings
import com.ravy.assistant.tts.RavyTtsClient
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Real states the orb reflects — set only at the matching real pipeline step, never decoratively. */
enum class RavyState { IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, SUCCESS, ERROR }

/**
 * One shared instance of brain + action engine + TTS, used from MainActivity,
 * the always-on wake-word service, and the popup overlay alike — so "Ravy"
 * behaves the same and remembers the same short conversation history no
 * matter which UI surface triggered it.
 */
class RavyCore(context: Context) {

    private val appContext = context.applicationContext
    private val brain = RavyBrain()
    private val actionEngine = ActionEngine(appContext)
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    var state: RavyState = RavyState.IDLE
        private set
    private val stateListeners = mutableListOf<(RavyState) -> Unit>()

    fun addStateListener(listener: (RavyState) -> Unit) { stateListeners.add(listener) }
    fun removeStateListener(listener: (RavyState) -> Unit) { stateListeners.remove(listener) }

    private fun setState(s: RavyState) {
        state = s
        stateListeners.forEach { it(s) }
    }

    /** Called by whichever UI surface (activity, overlay, wake service) has an active mic session. */
    fun notifyListening(active: Boolean) {
        setState(if (active) RavyState.LISTENING else RavyState.IDLE)
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.let { engine ->
                // Prefer a higher-quality "network"/enhanced-quality voice when the
                // device has one installed, instead of the default embedded voice.
                val bestVoice: Voice? = engine.voices
                    ?.filter { it.locale == Locale.getDefault() && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { if (it.quality >= Voice.QUALITY_HIGH) 1 else 0 }
                bestVoice?.let { engine.voice = it }
                engine.setPitch(0.95f)
            }
        }
    }

    /**
     * Interprets [utterance], executes the resulting action off the main
     * thread, then calls [onResult] with the text to speak/show. Safe to
     * call from any thread. Also logs the turn to RavyHistoryStore.
     */
    fun handle(utterance: String, onResult: (String) -> Unit) {
        setState(RavyState.THINKING)
        backgroundExecutor.execute {
            val spoken = try {
                val action = brain.interpret(utterance)
                setState(RavyState.EXECUTING)
                var result = actionEngine.execute(action)

                if (action.action == ActionType.SEARCH_CONTACT &&
                    action.reason.startsWith("User asked to call") &&
                    result.status == ActionStatus.SUCCESS
                ) {
                    val number = Regex("""[+0-9][0-9 \-]{5,}""").find(result.message)?.value?.trim()
                    if (number != null) {
                        result = actionEngine.execute(RavyAction(ActionType.CALL_CONTACT, mapOf("number" to number)))
                    }
                }

                setState(if (result.status == ActionStatus.SUCCESS) RavyState.SUCCESS else RavyState.ERROR)

                when (result.status) {
                    ActionStatus.SUCCESS -> result.message
                    ActionStatus.FAILED -> "That failed, Sir: ${result.message}"
                    ActionStatus.NEEDS_PERMISSION -> result.message
                    ActionStatus.NEEDS_CONFIRMATION -> { brain.pendingConfirmation = action; result.message }
                    ActionStatus.NOT_SUPPORTED -> result.message
                }
            } catch (t: Throwable) {
                setState(RavyState.ERROR)
                "Something went wrong handling that, Sir: ${t.message ?: t.javaClass.simpleName}"
            }
            RavyHistoryStore.add(appContext, utterance, spoken)
            onResult(spoken)
        }
    }

    /**
     * Speaks [text], calling [onDone] once playback finishes (or fails).
     * Uses ElevenLabs if configured, else the on-device voice in the
     * currently selected language (RavyLanguage), falling back to English
     * with an honest notice if the device can't actually speak that locale.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        setState(RavyState.SPEAKING)
        backgroundExecutor.execute {
            val wrappedOnDone = { setState(RavyState.IDLE); onDone() }
            if (RavySettings.isTtsConfigured(appContext)) {
                try {
                    val apiKey = RavySettings.getTtsApiKey(appContext)!!
                    val voiceId = RavySettings.getTtsVoiceId(appContext)
                    val file = RavyTtsClient.synthesizeToFile(appContext, text, apiKey, voiceId)
                    playFile(file, wrappedOnDone)
                } catch (e: Exception) {
                    speakOnDevice(text, wrappedOnDone)
                }
            } else {
                speakOnDevice(text, wrappedOnDone)
            }
        }
    }

    private fun playFile(file: File, onDone: () -> Unit) {
        try {
            mediaPlayer?.release()
            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                it.release(); file.delete(); mediaPlayer = null; onDone()
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release(); file.delete(); mediaPlayer = null; onDone(); true
            }
            player.prepare()
            player.start()
        } catch (e: Exception) {
            file.delete()
            onDone()
        }
    }

    private fun speakOnDevice(text: String, onDone: () -> Unit) {
        var waited = 0
        while (!ttsReady && waited < 3000) { Thread.sleep(100); waited += 100 }
        val engine = tts
        if (engine == null) { onDone(); return }

        val desired = RavyLanguage.current(appContext)
        val (resolved, fellBack) = RavyLanguage.bestAvailableTtsLocale(engine, desired)
        engine.setLanguage(resolved.locale)
        engine.setSpeechRate(RavySettings.getSpeechRate(appContext))

        val toSpeak = if (fellBack) "(${resolved.displayName} isn't available on this device for speech, Sir — using English.) $text" else text

        val id = System.currentTimeMillis().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) onDone() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) onDone() }
        })
        engine.speak(toSpeak, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        backgroundExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
