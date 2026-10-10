package com.ravy.assistant.reminders

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravy.assistant.RavyApplication
import com.ravy.assistant.ui.VoiceOrb
import java.util.Locale

/**
 * The "incoming call"-style full-screen alert. NOT a real phone call — an
 * app-generated screen, like an alarm clock app's ring screen. Shows
 * Answer/Dismiss, rings + vibrates while waiting, and on Answer runs the
 * reminder's stored phrase through the real Ravy pipeline, speaks the real
 * result, then opens the mic for real follow-up questions.
 */
class IncomingRavyCallActivity : ComponentActivity() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private var statusText by mutableStateOf("")
    private var answered by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var reminderIdForTimeout: String = ""

    companion object {
        /** How long Ravy keeps ringing before giving up and marking the reminder missed. */
        private const val RING_TIMEOUT_MS = 45_000L
    }

    private val core by lazy { (application as RavyApplication).ravyCore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val label = intent.getStringExtra("label") ?: "Reminder"
        val reminderId = intent.getStringExtra("reminder_id") ?: ""
        reminderIdForTimeout = reminderId
        statusText = label
        startRinging()
        startRingTimeout(reminderId)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                core.handle(text) { spoken -> runOnUiThread { speakAndMaybeListen(spoken) } }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        setContent {
            IncomingCallScreen(
                label = label,
                answered = answered,
                statusText = statusText,
                onAnswer = { answer(reminderId, label) },
                onDismiss = { dismiss(reminderId) }
            )
        }
    }

    private fun startRinging() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (e: Exception) { /* real device without a default ringtone — ring silently rather than crash */ }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0, 600, 400)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 1)
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        vibrator?.cancel()
    }

    /**
     * If nothing happens within RING_TIMEOUT_MS, stop ringing and mark the
     * reminder missed — matching real incoming-call/alarm behavior: it does
     * not ring forever once its moment has passed. E.g. a 7:00 PM reminder
     * does not still be ringing at 7:01 PM if untouched.
     */
    private fun startRingTimeout(reminderId: String) {
        val runnable = Runnable {
            if (!answered) {
                stopRinging()
                markMissed(reminderId)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(reminderId.hashCode())
                finish()
            }
        }
        timeoutRunnable = runnable
        timeoutHandler.postDelayed(runnable, RING_TIMEOUT_MS)
    }

    private fun cancelRingTimeout() {
        timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
    }

    private fun markMissed(reminderId: String) {
        val all = RavyReminderStore.getAll(this)
        val match = all.firstOrNull { it.id == reminderId } ?: return
        RavyReminderStore.update(this, match.copy(missed = true))
    }

    private fun answer(reminderId: String, label: String) {
        cancelRingTimeout()
        stopRinging()
        answered = true
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(reminderId.hashCode())

        // Run the reminder's own stored phrase through the real pipeline —
        // the same ActionEngine/Gemini path as any typed or spoken command —
        // rather than fabricating a canned "report".
        core.handle(label) { spoken -> runOnUiThread { speakAndMaybeListen(spoken) } }
    }

    private fun speakAndMaybeListen(text: String) {
        statusText = text
        core.speak(text) {
            runOnUiThread { startFollowUpListening() }
        }
    }

    private fun startFollowUpListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        listening = true
        val recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
        }
        speechRecognizer.startListening(recIntent)
    }

    private fun dismiss(reminderId: String) {
        cancelRingTimeout()
        stopRinging()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(reminderId.hashCode())
        finish()
    }

    override fun onDestroy() {
        cancelRingTimeout()
        stopRinging()
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        super.onDestroy()
    }
}

@Composable
private fun IncomingCallScreen(
    label: String,
    answered: Boolean,
    statusText: String,
    onAnswer: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF03060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("RAVY", color = Color(0xFF7CFFF4), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(if (answered) statusText else label, color = Color(0xFFCFEFF2), fontSize = 18.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            VoiceOrb(amplitude = if (answered) 0.4f else 0.2f)
            Spacer(Modifier.height(48.dp))
            if (!answered) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1E1E))) { Text("Dismiss") }
                    Button(onClick = onAnswer, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E5A33))) { Text("Answer") }
                }
            } else {
                Button(onClick = onDismiss) { Text("End") }
            }
        }
    }
}
