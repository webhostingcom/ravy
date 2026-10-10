package com.ravy.assistant.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import com.ravy.assistant.RavyApplication
import com.ravy.assistant.listening.RavyListenerService
import java.util.Locale

/**
 * Shown the moment "Hey Ravy" is heard. Draws the small bottom orb over
 * whatever app you're in, listens for your actual request, speaks the
 * reply, then — per the "don't make me say Hey Ravy every time" request —
 * keeps listening for a follow-up for a short window before quietly
 * disappearing and handing listening back to the wake-word service.
 *
 * Requires the "Display over other apps" special permission — Android
 * will not let any app show this kind of overlay without it, and Ravy
 * cannot grant it to itself. MainActivity's "Enable popup orb" button
 * opens that settings screen directly.
 */
class RavyOverlayService : Service() {

    companion object {
        const val EXTRA_TRAILING_COMMAND = "trailing_command"
        private const val FOLLOW_UP_WINDOW_MS = 9000L
    }

    private lateinit var windowManager: WindowManager
    private var orbView: OverlayOrbView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var followUpRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOrb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trailing = intent?.getStringExtra(EXTRA_TRAILING_COMMAND)?.trim()
        if (!trailing.isNullOrBlank()) {
            // "Hey Ravy, what's the battery" was said in one breath — no need
            // to listen again, just process it immediately.
            processUtterance(trailing)
        } else {
            listenForCommand()
        }
        return START_NOT_STICKY
    }

    private fun showOrb() {
        if (!Settings_canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val orb = OverlayOrbView(this)
        orbView = orb
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            180, 180, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }
        windowManager.addView(orb, params)
    }

    private fun listenForCommand() {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { orbView?.amplitude = (rmsdB / 10f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { orbView?.amplitude = 0f }
            override fun onError(error: Int) {
                orbView?.amplitude = 0f
                // Silence/timeout while waiting for a follow-up -> end the session.
                endSessionAndResumeWakeWord()
            }
            override fun onResults(results: android.os.Bundle?) {
                orbView?.amplitude = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) { endSessionAndResumeWakeWord() } else { processUtterance(text) }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, com.ravy.assistant.language.RavyLanguage.current(this@RavyOverlayService).locale)
            // Real (if modest) fix for "I have to speak right into the mic":
            // Android's default recognizer cuts off on a short silence gap,
            // which reads as it "not hearing" quieter/farther speech. This
            // doesn't increase physical mic range (that's hardware), but it
            // stops it giving up on you so quickly.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        recognizer.startListening(intent)
    }

    private fun processUtterance(text: String) {
        cancelFollowUpTimeout()
        val core = (application as RavyApplication).ravyCore
        core.handle(text) { spoken ->
            mainHandler.post {
                core.speak(spoken) {
                    // Reply finished — stay "awake" for a follow-up instead of
                    // requiring "Hey Ravy" again, per the requested behavior.
                    scheduleFollowUpListen()
                }
            }
        }
    }

    private fun scheduleFollowUpListen() {
        val runnable = Runnable { listenForCommand() }
        followUpRunnable = runnable
        // Small delay so we don't start listening mid-way through our own TTS tail.
        mainHandler.postDelayed(runnable, 400)
        // Overall session ceiling: if nothing useful happens, give up.
        mainHandler.postDelayed({ endSessionAndResumeWakeWord() }, FOLLOW_UP_WINDOW_MS)
    }

    private fun cancelFollowUpTimeout() {
        followUpRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun endSessionAndResumeWakeWord() {
        cancelFollowUpTimeout()
        stopSelf()
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        orbView?.let { windowManager.removeView(it); it.destroy() }
        // Hand listening back to the always-on wake-word service.
        startService(Intent(this, RavyListenerService::class.java).setAction(RavyListenerService.ACTION_RESUME))
        super.onDestroy()
    }

    private fun Settings_canDrawOverlays(context: android.content.Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)
}
