package com.ravy.assistant.listening

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ravy.assistant.overlay.RavyOverlayService
import java.util.Locale

/**
 * Makes "Hey Ravy" work without opening the app or tapping the orb.
 *
 * HONEST LIMITS, read before relying on this:
 * - This is a real, working restart-loop around Android's SpeechRecognizer,
 *   not a low-power hardware hotword detector like "Hey Google". It uses
 *   noticeably more battery than a true hotword chip would.
 * - It must run as a foreground service (hence the persistent notification —
 *   Android requires this for any app doing continuous microphone work in
 *   the background; Ravy cannot hide it).
 * - Some phones (Xiaomi/MIUI, Samsung, and others with aggressive battery
 *   managers) may still kill background services unless you exempt Ravy
 *   under Settings > Battery > Unrestricted / "No restrictions" for this app.
 *   That's an OS/OEM policy, not something any app can bypass.
 */
class RavyListenerService : Service() {

    companion object {
        const val ACTION_START = "com.ravy.assistant.action.START_LISTENING"
        const val ACTION_STOP = "com.ravy.assistant.action.STOP_LISTENING"
        const val ACTION_RESUME = "com.ravy.assistant.action.RESUME_LISTENING"
        /**
         * Releases the SpeechRecognizer session without killing the
         * foreground service/notification — used while MainActivity itself
         * needs the mic. Without this, the wake-word restart loop and the
         * in-app recognizer fight over the single system-wide recognition
         * session, and the in-app one silently loses every time. This is
         * the real fix for "typing works, voice doesn't" once "Hey Ravy"
         * has ever been enabled.
         */
        const val ACTION_PAUSE = "com.ravy.assistant.action.PAUSE_LISTENING"
        private const val CHANNEL_ID = "ravy_wake_word"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_PHRASE = "hey ravy"
        private const val RESTART_DELAY_MS = 400L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListeningLoop(); stopSelf() }
            ACTION_PAUSE -> stopListeningLoop() // keeps the service/notification alive, just releases the mic
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startListeningLoop()
            }
        }
        return START_STICKY
    }

    private fun startListeningLoop() {
        if (running) return
        running = true
        listenOnce()
    }

    private fun stopListeningLoop() {
        running = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun listenOnce() {
        if (!running) return
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                recognizer.destroy()
                restartAfterDelay()
            }
            override fun onResults(results: android.os.Bundle?) {
                recognizer.destroy()
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase(Locale.getDefault()).orEmpty()
                if (heard.contains(WAKE_PHRASE)) {
                    val trailing = heard.substringAfter(WAKE_PHRASE).trim()
                    handOffToOverlay(trailing)
                } else {
                    restartAfterDelay()
                }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, com.ravy.assistant.language.RavyLanguage.current(this@RavyListenerService).locale)
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
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            restartAfterDelay()
        }
    }

    private fun restartAfterDelay() {
        if (!running) return
        mainHandler.postDelayed({ listenOnce() }, RESTART_DELAY_MS)
    }

    /** Wake word heard — pause our own loop and let the popup orb take over the conversation. */
    private fun handOffToOverlay(trailingCommand: String) {
        running = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        startService(
            Intent(this, RavyOverlayService::class.java)
                .putExtra(RavyOverlayService.EXTRA_TRAILING_COMMAND, trailingCommand)
        )
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ravy is listening")
            .setContentText("Say \"Hey Ravy\" any time.")
            .setSmallIcon(com.ravy.assistant.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ravy wake word", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopListeningLoop()
        super.onDestroy()
    }
}
