package com.ravy.assistant

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.ravy.assistant.auth.RavyAuth
import com.ravy.assistant.core.RavyState
import com.ravy.assistant.gmail.GmailAuth
import com.ravy.assistant.history.HistoryEntry
import com.ravy.assistant.history.RavyHistoryStore
import com.ravy.assistant.language.RavyLanguage
import com.ravy.assistant.listening.RavyListenerService
import com.ravy.assistant.reminders.RavyReminder
import com.ravy.assistant.reminders.RavyReminderScheduler
import com.ravy.assistant.reminders.RavyReminderStore
import com.ravy.assistant.reminders.RepeatType
import com.ravy.assistant.settings.RavySettings
import com.ravy.assistant.ui.VoiceOrb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Destination {
    MAIN, PERMISSIONS, AI_BRAIN, VOICE, LANGUAGE, NOTIFICATIONS, REMINDERS,
    HISTORY, APPEARANCE, SETTINGS, HELP, ABOUT, PRIVACY, SIGN_IN
}

class MainActivity : ComponentActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer

    private var amplitude by mutableStateOf(0f)
    private var transcript by mutableStateOf("") // live/just-finished caption shown under the orb
    private var statusText by mutableStateOf("Tap the orb, or say \"Hey Ravy\" once always-on listening is enabled, Sir.")
    private var listening by mutableStateOf(false)
    private var gmailSignedIn by mutableStateOf(false)
    private var alwaysOnEnabled by mutableStateOf(false)
    private var showAiSetup by mutableStateOf(false)
    private var showVoiceSetup by mutableStateOf(false)
    private var destination by mutableStateOf(Destination.MAIN)
    private var menuOpen by mutableStateOf(false)
    private var ravyState by mutableStateOf(RavyState.IDLE)
    private var chatMode by mutableStateOf(false)
    private var seriousMode by mutableStateOf(false)
    private var googleAccount by mutableStateOf<GoogleSignInAccount?>(null)
    private var chatMessages by mutableStateOf(listOf<Pair<Boolean, String>>()) // true = from user
    private var reminders by mutableStateOf(listOf<RavyReminder>())
    private var historyEntries by mutableStateOf(listOf<HistoryEntry>())
    private var editingReminder by mutableStateOf<RavyReminder?>(null)

    private val core by lazy { (application as RavyApplication).ravyCore }

    private val stateListener: (RavyState) -> Unit = { s -> runOnUiThread { ravyState = s } }

    private val runtimePermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { startAlwaysOnListening() }

    private val gmailSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            gmailSignedIn = true
            speak("Signed into Gmail, Sir. Say 'check my email' any time.")
        } catch (e: Exception) {
            gmailSignedIn = false
            speak("Gmail sign-in failed: ${e.message}")
        }
    }

    private val generalSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            googleAccount = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
        } catch (e: Exception) {
            statusText = "Sign-in failed: ${e.message}"
        }
    }

    private val exactAlarmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gmailSignedIn = GmailAuth.lastSignedInAccount(this) != null
        googleAccount = RavyAuth.currentAccount(this)
        chatMode = RavySettings.isChatMode(this)
        seriousMode = RavySettings.isSeriousMode(this)
        permissionLauncher.launch(runtimePermissions)
        core.addStateListener(stateListener)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(buildRecognitionListener())

        setContent {
            AppRoot()
        }
    }

    private fun buildRecognitionListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { statusText = "Listening, Sir..." }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            amplitude = (rmsdB / 6f).coerceIn(0f, 1f)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { amplitude = 0f; listening = false }
        override fun onError(error: Int) {
            amplitude = 0f; listening = false; transcript = ""
            statusText = "I didn't catch that, Sir. Tap to try again."
        }
        override fun onResults(results: Bundle?) {
            listening = false
            amplitude = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            transcript = text // kept visible briefly; cleared once Ravy starts speaking its reply
            handleUtterance(text)
        }
        // Live caption under the orb while listening — updates as recognition
        // produces text, per the "show what I said under the orb" requirement.
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) transcript = partial
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    @Composable
    private fun AppRoot() {
        // Without an explicit dark ColorScheme, Material3 components
        // (OutlinedTextField, Switch, dialogs, button labels) fall back to
        // a LIGHT scheme's colors — near-black text — regardless of the
        // dark backgrounds drawn manually elsewhere. That's the real cause
        // of "I type but can't see the text": the typed characters were
        // genuinely black-on-black. This wraps every screen in a real dark
        // scheme so typed text, cursors, placeholders, and selections are
        // all visible everywhere a text field exists.
        val ravyDarkColors = darkColorScheme(
            primary = Color(0xFF7CFFF4),
            onPrimary = Color(0xFF03060A),
            secondary = Color(0xFF9333EA),
            background = Color(0xFF03060A),
            onBackground = Color(0xFFE6F7F8),
            surface = Color(0xFF0D2229),
            onSurface = Color(0xFFE6F7F8),
            surfaceVariant = Color(0xFF12313A),
            onSurfaceVariant = Color(0xFFCFEFF2),
            error = Color(0xFFFF6B6B)
        )
        MaterialTheme(colorScheme = ravyDarkColors) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (destination) {
                Destination.MAIN -> MainScreen()
                Destination.PERMISSIONS -> PermissionsScreen(context = this@MainActivity, onBack = { destination = Destination.MAIN })
                Destination.AI_BRAIN -> AiBrainScreen(
                    onBack = { destination = Destination.MAIN },
                    onOpenSetup = { showAiSetup = true }
                )
                Destination.VOICE -> VoiceScreen(
                    onBack = { destination = Destination.MAIN },
                    onOpenSetup = { showVoiceSetup = true },
                    onTestVoice = { speak("This is a test of Ravy's voice, Sir.") }
                )
                Destination.LANGUAGE -> LanguageScreen(onBack = { destination = Destination.MAIN })
                Destination.NOTIFICATIONS -> NotificationsScreen(
                    onBack = { destination = Destination.MAIN },
                    onOpenSettings = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                )
                Destination.REMINDERS -> RemindersScreen(
                    reminders = reminders,
                    onBack = { destination = Destination.MAIN },
                    onDelete = { deleteReminder(it) },
                    onToggle = { toggleReminder(it) },
                    onEdit = { editingReminder = it }
                )
                Destination.HISTORY -> HistoryScreen(
                    entries = historyEntries,
                    onBack = { destination = Destination.MAIN },
                    onClear = { RavyHistoryStore.clear(this@MainActivity); historyEntries = emptyList() }
                )
                Destination.APPEARANCE -> AppearanceScreen(
                    seriousMode = seriousMode,
                    onBack = { destination = Destination.MAIN },
                    onToggleSerious = { setSeriousModeUi(it) }
                )
                Destination.SETTINGS -> SettingsScreen(
                    chatMode = chatMode,
                    seriousMode = seriousMode,
                    onBack = { destination = Destination.MAIN },
                    onToggleChatMode = { chatMode = it; RavySettings.setChatMode(this@MainActivity, it) },
                    onToggleSerious = { setSeriousModeUi(it) }
                )
                Destination.HELP -> StaticTextScreen("Help", HELP_TEXT) { destination = Destination.MAIN }
                Destination.ABOUT -> StaticTextScreen("About Ravy", ABOUT_TEXT) { destination = Destination.MAIN }
                Destination.PRIVACY -> StaticTextScreen("Privacy", PRIVACY_TEXT) { destination = Destination.MAIN }
                Destination.SIGN_IN -> SignInScreen(
                    account = googleAccount,
                    onBack = { destination = Destination.MAIN },
                    onGoogleSignIn = { generalSignInLauncher.launch(RavyAuth.signInIntent(this@MainActivity)) },
                    onSignOut = { RavyAuth.signOut(this@MainActivity) { googleAccount = null } }
                )
            }

            if (showAiSetup) AiSetupDialog(onDismiss = { showAiSetup = false }, onSave = { p, k -> saveAiSetup(p, k) })
            if (showVoiceSetup) VoiceSetupDialog(onDismiss = { showVoiceSetup = false }, onSave = { p, k, v -> saveVoiceSetup(p, k, v) })
            editingReminder?.let { r ->
                ReminderEditDialog(reminder = r, onDismiss = { editingReminder = null }, onSave = { updated -> saveReminderEdit(r, updated) })
            }
        }
        } // close MaterialTheme
    }

    @Composable
    private fun MainScreen() {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF03060A), Color(0xFF06131A))))
        ) {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .semantics { contentDescription = "Menu" }
            ) {
                Text("\u2630", fontSize = 24.sp, color = Color(0xFF7CFFF4))
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))

                Surface(
                    onClick = { if (!chatMode) startListening() },
                    color = Color.Transparent,
                    modifier = Modifier.size(260.dp).semantics { contentDescription = "Ravy" }
                ) {
                    VoiceOrb(amplitude = amplitude, state = ravyState, serious = seriousMode)
                }

                // Small, temporary "what you said" caption — only while there is
                // something live to show; clears itself once Ravy starts replying
                // or a fresh listen begins. Stays close to the orb, not a log.
                if (transcript.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "\u201C$transcript\u201D",
                        color = Color(0xFF8FD9E0), fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 40.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    statusText, color = Color(0xFFCFEFF2), fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 32.dp).semantics { contentDescription = statusText },
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(Modifier.weight(1f))

                if (chatMode) {
                    ChatBar(
                        messages = chatMessages,
                        onSend = { text -> sendChatMessage(text) }
                    )
                } else {
                    Surface(
                        onClick = { startListening() },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (seriousMode) Color(0xFF5A1E1E) else Color(0xFF12313A),
                        modifier = Modifier.size(64.dp).semantics { contentDescription = "Microphone" }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("\uD83C\uDF99", fontSize = 26.sp) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            if (menuOpen) {
                HamburgerMenu(
                    onClose = { menuOpen = false },
                    onNavigate = { d ->
                        menuOpen = false
                        if (d == Destination.REMINDERS) reminders = RavyReminderStore.getAll(this@MainActivity)
                        if (d == Destination.HISTORY) historyEntries = RavyHistoryStore.getAll(this@MainActivity).reversed()
                        destination = d
                    }
                )
            }
        }
    }

    @Composable
    private fun ChatBar(messages: List<Pair<Boolean, String>>, onSend: (String) -> Unit) {
        var input by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(messages) { (fromUser, text) ->
                    Text(
                        text = (if (fromUser) "You: " else "Ravy: ") + text,
                        color = if (fromUser) Color(0xFFCFEFF2) else Color(0xFF7CFFF4),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 8.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Ravy...") },
                    singleLine = true
                )
                IconButton(onClick = { if (input.isNotBlank()) { onSend(input); input = "" } }) {
                    Text("\u27A4", fontSize = 20.sp, color = Color(0xFF7CFFF4))
                }
            }
        }
    }

    private fun sendChatMessage(text: String) {
        chatMessages = chatMessages + (true to text)
        core.handle(text) { spoken ->
            runOnUiThread {
                chatMessages = chatMessages + (false to spoken)
                seriousMode = RavySettings.isSeriousMode(this)
                core.speak(spoken) {}
            }
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText = "I need microphone permission first, Sir. Please grant it in Settings."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText = "This device has no speech recognition service available, Sir — Ravy genuinely can't listen here."
            return
        }
        // Make sure the always-on wake-word loop isn't still holding the
        // single system-wide recognition session (belt-and-suspenders on
        // top of the onResume/onPause pause — in case this is ever called
        // before onResume has run, e.g. the very first tap after launch).
        if (alwaysOnEnabled) {
            startService(Intent(this, RavyListenerService::class.java).setAction(RavyListenerService.ACTION_PAUSE))
        }
        transcript = ""
        listening = true
        statusText = "Listening, Sir..."
        val locale = RavyLanguage.current(this).locale
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            // A SpeechRecognizer instance can get into a stuck state after
            // certain errors (a real, documented Android quirk). Recreate it
            // once and retry, rather than silently failing forever.
            recreateSpeechRecognizer()
            speechRecognizer.startListening(intent)
        }
    }

    private fun recreateSpeechRecognizer() {
        speechRecognizer.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(buildRecognitionListener())
    }

    private fun handleUtterance(text: String) {
        statusText = "You said: \"$text\""
        if (text.lowercase().contains("sign into gmail") || text.lowercase().contains("sign in to gmail")) {
            gmailSignInLauncher.launch(GmailAuth.signInIntent(this))
            return
        }
        core.handle(text) { spoken ->
            runOnUiThread {
                seriousMode = RavySettings.isSeriousMode(this)
                speak(spoken)
            }
        }
    }

    private fun speak(text: String) {
        transcript = "" // the "what you said" caption disappears once Ravy starts responding
        statusText = text
        core.speak(text) {
            runOnUiThread { startListening() }
        }
    }

    // ---------- Always-on "Hey Ravy" ----------

    private fun toggleAlwaysOnListening() {
        if (alwaysOnEnabled) {
            startService(Intent(this, RavyListenerService::class.java).setAction(RavyListenerService.ACTION_STOP))
            alwaysOnEnabled = false
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            statusText = "Enable \"Display over other apps\" first, Sir — opening Settings."
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startAlwaysOnListening()
    }

    private fun startAlwaysOnListening() {
        if (!Settings.canDrawOverlays(this)) {
            statusText = "The popup orb permission wasn't granted, Sir — always-on listening needs it."
            return
        }
        startService(Intent(this, RavyListenerService::class.java).setAction(RavyListenerService.ACTION_START))
        alwaysOnEnabled = true
        statusText = "Always listening for \"Hey Ravy\", Sir."
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } else {
            statusText = "Battery optimization is already off for Ravy, Sir."
        }
    }

    private fun setSeriousModeUi(on: Boolean) {
        seriousMode = on
        RavySettings.setSeriousMode(this, on)
    }

    // ---------- Reminders ----------

    private fun deleteReminder(r: RavyReminder) {
        RavyReminderScheduler.cancel(this, r)
        RavyReminderStore.remove(this, r.id)
        reminders = RavyReminderStore.getAll(this)
    }

    private fun toggleReminder(r: RavyReminder) {
        val updated = r.copy(enabled = !r.enabled)
        if (updated.enabled) {
            val next = updated.copy(nextTriggerMillis = RavyReminderScheduler.computeNextTrigger(r.hour, r.minute, r.repeat))
            RavyReminderScheduler.schedule(this, next)
            RavyReminderStore.update(this, next)
        } else {
            RavyReminderScheduler.cancel(this, r)
            RavyReminderStore.update(this, updated)
        }
        reminders = RavyReminderStore.getAll(this)
    }

    private fun saveReminderEdit(original: RavyReminder, updated: RavyReminder) {
        RavyReminderScheduler.cancel(this, original)
        val withNextTrigger = updated.copy(
            id = original.id,
            nextTriggerMillis = RavyReminderScheduler.computeNextTrigger(updated.hour, updated.minute, updated.repeat)
        )
        RavyReminderScheduler.schedule(this, withNextTrigger)
        RavyReminderStore.update(this, withNextTrigger)
        reminders = RavyReminderStore.getAll(this)
        editingReminder = null
    }

    // ---------- AI setup (your own API key, stored encrypted on-device) ----------

    private fun saveAiSetup(provider: RavySettings.LlmProvider, key: String) {
        if (key.isNotBlank()) {
            RavySettings.save(this, provider, key)
            statusText = "AI provider saved, Sir. Try asking me anything."
        }
        showAiSetup = false
    }

    // ---------- Voice setup (ElevenLabs, your own API key, stored encrypted on-device) ----------

    private fun saveVoiceSetup(provider: RavySettings.TtsProvider, key: String, voiceId: String) {
        if (provider == RavySettings.TtsProvider.DEVICE) {
            RavySettings.saveTts(this, RavySettings.TtsProvider.DEVICE, "", "")
            statusText = "Back to the on-device voice, Sir."
        } else if (key.isNotBlank()) {
            val resolvedVoiceId = voiceId.ifBlank { com.ravy.assistant.tts.RavyTtsClient.DEFAULT_VOICE_ID }
            RavySettings.saveTts(this, RavySettings.TtsProvider.ELEVENLABS, key, resolvedVoiceId)
            statusText = "ElevenLabs voice saved, Sir. I'll use it from my next reply."
        }
        showVoiceSetup = false
    }

    override fun onResume() {
        super.onResume()
        // Release the wake-word service's recognizer session while this
        // Activity is on screen, so its own mic use never silently loses
        // the single system-wide recognition session to the background loop.
        if (alwaysOnEnabled) {
            startService(Intent(this, RavyListenerService::class.java).setAction(RavyListenerService.ACTION_PAUSE))
        }
    }

    override fun onPause() {
        super.onPause()
        if (alwaysOnEnabled) {
            startService(Intent(this, RavyListenerService::class.java).setAction(RavyListenerService.ACTION_RESUME))
        }
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        core.removeStateListener(stateListener)
        super.onDestroy()
    }
}

// ================= Hamburger menu =================

@Composable
fun HamburgerMenu(onClose: () -> Unit, onNavigate: (Destination) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC03060A))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f).fillMaxHeight()
                .align(Alignment.TopEnd)
                .background(Color(0xFF0A1218))
                .padding(20.dp)
        ) {
            Text("RAVY", color = Color(0xFF7CFFF4), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            MenuItem("Sign in") { onNavigate(Destination.SIGN_IN) }
            MenuItem("Permissions") { onNavigate(Destination.PERMISSIONS) }
            MenuItem("AI / Brain") { onNavigate(Destination.AI_BRAIN) }
            MenuItem("Voice") { onNavigate(Destination.VOICE) }
            MenuItem("Language") { onNavigate(Destination.LANGUAGE) }
            MenuItem("Notifications") { onNavigate(Destination.NOTIFICATIONS) }
            MenuItem("Reminders & Routines") { onNavigate(Destination.REMINDERS) }
            MenuItem("History") { onNavigate(Destination.HISTORY) }
            MenuItem("Appearance") { onNavigate(Destination.APPEARANCE) }

            Spacer(Modifier.height(20.dp))
            Text("GENERAL", color = Color(0xFF4A6B72), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            MenuItem("Settings") { onNavigate(Destination.SETTINGS) }
            MenuItem("Help") { onNavigate(Destination.HELP) }
            MenuItem("About") { onNavigate(Destination.ABOUT) }
            MenuItem("Privacy") { onNavigate(Destination.PRIVACY) }

            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = Color(0xFF7CFFF4)) }
        }
    }
}

@Composable
fun MenuItem(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFFCFEFF2), fontSize = 15.sp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF03060A)).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("\u2190 Back", color = Color(0xFF7CFFF4)) }
        }
        Text(title, color = Color(0xFF7CFFF4), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
        content()
    }
}

// ================= Permission Center =================

data class PermissionRow(val name: String, val granted: Boolean, val required: Boolean, val onTap: () -> Unit)

@Composable
fun PermissionsScreen(context: Context, onBack: () -> Unit) {
    val activity = context as MainActivity
    val rows = buildPermissionRows(context, activity)
    ScreenScaffold("Permissions", onBack) {
        Text("Never pretend a permission was granted — this reflects Android's real, current state.", color = Color(0xFF4A6B72), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(rows) { row ->
                Surface(
                    onClick = row.onTap,
                    color = Color(0xFF0D2229),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(row.name, color = Color(0xFFCFEFF2), fontSize = 14.sp)
                            Text(if (row.required) "Required" else "Optional", color = Color(0xFF4A6B72), fontSize = 11.sp)
                        }
                        Text(
                            if (row.granted) "Granted" else "Not granted",
                            color = if (row.granted) Color(0xFF4AFF8F) else Color(0xFFFF4A4A),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

fun buildPermissionRows(context: Context, activity: MainActivity): List<PermissionRow> {
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val rows = mutableListOf<PermissionRow>()
    rows += PermissionRow("Microphone", granted(Manifest.permission.RECORD_AUDIO), true) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Camera (flashlight)", granted(Manifest.permission.CAMERA), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Contacts", granted(Manifest.permission.READ_CONTACTS), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("SMS", granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Phone (calls)", granted(Manifest.permission.CALL_PHONE), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Call log (phone activity reports)", granted(Manifest.permission.READ_CALL_LOG), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Calendar", granted(Manifest.permission.WRITE_CALENDAR), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Location", granted(Manifest.permission.ACCESS_FINE_LOCATION), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Files / Media", granted(Manifest.permission.READ_MEDIA_IMAGES), false) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow(
        "Notifications (listener)",
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        false
    ) { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
    rows += PermissionRow(
        "Accessibility (screen reading)",
        Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services")?.contains(context.packageName) == true,
        false
    ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    rows += PermissionRow("Display over other apps (Hey Ravy orb)", Settings.canDrawOverlays(context), false) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    rows += PermissionRow("Alarms & reminders", RavyReminderScheduler.canScheduleExactAlarms(context), false) {
        if (Build.VERSION.SDK_INT >= 31) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }
    rows += PermissionRow("Gmail sign-in", GmailAuth.lastSignedInAccount(context) != null, false) {
        // Handled from the Gmail chip / voice command; nothing to deep-link to directly here.
    }
    return rows
}

// ================= AI / Brain =================

@Composable
fun AiBrainScreen(onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ScreenScaffold("AI / Brain", onBack) {
        val provider = RavySettings.getProvider(context)
        val configured = RavySettings.isConfigured(context)
        Text("Current provider: ${provider.name}", color = Color(0xFFCFEFF2), fontSize = 15.sp)
        Text(if (configured) "Status: configured" else "Status: not configured", color = if (configured) Color(0xFF4AFF8F) else Color(0xFFFF4A4A), fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Text("Gemini is the default brain. Unrecognized commands and open-ended questions are routed here.", color = Color(0xFF4A6B72), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSetup) { Text("Configure") }
    }
}

// ================= Voice =================

@Composable
fun VoiceScreen(onBack: () -> Unit, onOpenSetup: () -> Unit, onTestVoice: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rate by remember { mutableStateOf(RavySettings.getSpeechRate(context)) }
    ScreenScaffold("Voice", onBack) {
        val provider = RavySettings.getTtsProvider(context)
        Text("Current voice: ${if (provider == RavySettings.TtsProvider.ELEVENLABS) "ElevenLabs (cloud)" else "Device voice"}", color = Color(0xFFCFEFF2), fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
        Text("Speech speed: ${String.format("%.1f", rate)}x", color = Color(0xFFCFEFF2), fontSize = 13.sp)
        Slider(value = rate, onValueChange = { rate = it; RavySettings.setSpeechRate(context, it) }, valueRange = 0.5f..2.0f)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenSetup) { Text("Configure") }
            OutlinedButton(onClick = onTestVoice) { Text("Test voice") }
        }
        Spacer(Modifier.height(16.dp))
        Text("If ElevenLabs fails (no key, no network, rate limit), Ravy falls back to the device voice rather than going silent.", color = Color(0xFF4A6B72), fontSize = 12.sp)
    }
}

// ================= Language =================

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selected by remember { mutableStateOf(RavySettings.getLanguageCode(context)) }
    ScreenScaffold("Language", onBack) {
        Text("Affects Ravy's speech recognition, voice replies, and AI responses — not just menu labels.", color = Color(0xFF4A6B72), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text("If a language isn't available for speech on this device, Ravy falls back to English and says so.", color = Color(0xFF4A6B72), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(RavyLanguage.SUPPORTED) { lang ->
                Surface(
                    onClick = { selected = lang.code; RavySettings.setLanguageCode(context, lang.code) },
                    color = if (selected == lang.code) Color(0xFF12313A) else Color(0xFF0D2229),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Text(lang.displayName, color = Color(0xFFCFEFF2), fontSize = 15.sp, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

// ================= Notifications =================

@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    ScreenScaffold("Notifications", onBack) {
        Text(if (granted) "Notification access: granted" else "Notification access: not granted", color = if (granted) Color(0xFF4AFF8F) else Color(0xFFFF4A4A), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Text("Lets Ravy summarize incoming notifications (\"read my notifications\") and read a specific app's message content on request.", color = Color(0xFF4A6B72), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        if (!granted) Button(onClick = onOpenSettings) { Text("Open Settings") }
    }
}

// ================= Reminders & Routines =================

@Composable
fun RemindersScreen(
    reminders: List<RavyReminder>,
    onBack: () -> Unit,
    onDelete: (RavyReminder) -> Unit,
    onToggle: (RavyReminder) -> Unit,
    onEdit: (RavyReminder) -> Unit
) {
    ScreenScaffold("Reminders & Routines", onBack) {
        Text("Say things like \"remind me at 6pm to sleep\" or \"every weekday at 7am give me my school schedule.\"", color = Color(0xFF4A6B72), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (reminders.isEmpty()) {
            Text("No reminders yet, Sir.", color = Color(0xFFCFEFF2), fontSize = 14.sp)
        }
        LazyColumn {
            items(reminders) { r ->
                Surface(color = Color(0xFF0D2229), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(r.label, color = Color(0xFFCFEFF2), fontSize = 14.sp)
                        Text(
                            String.format("%02d:%02d", r.hour, r.minute) + when (r.repeat) {
                                RepeatType.DAILY -> " · every day"
                                RepeatType.WEEKDAYS -> " · every weekday"
                                RepeatType.NONE -> " · once"
                            } + if (!r.enabled) " · disabled" else "",
                            color = Color(0xFF4A6B72), fontSize = 12.sp
                        )
                        if (r.missed) {
                            Text("Missed — you didn't answer in time", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { onEdit(r) }) { Text("Edit", color = Color(0xFF7CFFF4)) }
                            TextButton(onClick = { onToggle(r) }) { Text(if (r.enabled) "Disable" else "Enable", color = Color(0xFF7CFFF4)) }
                            TextButton(onClick = { onDelete(r) }) { Text("Delete", color = Color(0xFFFF4A4A)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReminderEditDialog(reminder: RavyReminder, onDismiss: () -> Unit, onSave: (RavyReminder) -> Unit) {
    var hour by remember { mutableStateOf(reminder.hour.toString()) }
    var minute by remember { mutableStateOf(reminder.minute.toString()) }
    var label by remember { mutableStateOf(reminder.label) }
    var repeat by remember { mutableStateOf(reminder.repeat) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit reminder") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("Hour (0-23)") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("Minute") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("What Ravy says/does") })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = repeat == RepeatType.NONE, onClick = { repeat = RepeatType.NONE }, label = { Text("Once") })
                    FilterChip(selected = repeat == RepeatType.DAILY, onClick = { repeat = RepeatType.DAILY }, label = { Text("Daily") })
                    FilterChip(selected = repeat == RepeatType.WEEKDAYS, onClick = { repeat = RepeatType.WEEKDAYS }, label = { Text("Weekdays") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hour.toIntOrNull() ?: reminder.hour
                val m = minute.toIntOrNull() ?: reminder.minute
                onSave(reminder.copy(hour = h, minute = m, label = label, repeat = repeat))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ================= History =================

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, onBack: () -> Unit, onClear: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    ScreenScaffold("History", onBack) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("${entries.size} past turns", color = Color(0xFF4A6B72), fontSize = 12.sp)
            TextButton(onClick = onClear) { Text("Clear history", color = Color(0xFFFF4A4A)) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(entries) { e ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(fmt.format(Date(e.timestamp)), color = Color(0xFF4A6B72), fontSize = 11.sp)
                    Text("You: ${e.userText}", color = Color(0xFFCFEFF2), fontSize = 13.sp)
                    Text("Ravy: ${e.ravyText}", color = Color(0xFF7CFFF4), fontSize = 13.sp)
                }
            }
        }
    }
}

// ================= Appearance =================

@Composable
fun AppearanceScreen(seriousMode: Boolean, onBack: () -> Unit, onToggleSerious: (Boolean) -> Unit) {
    ScreenScaffold("Appearance", onBack) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Serious Mode (red orb)", color = Color(0xFFCFEFF2), fontSize = 15.sp)
            Switch(checked = seriousMode, onCheckedChange = onToggleSerious)
        }
        Spacer(Modifier.height(12.dp))
        Text("Also toggleable by voice: \"Ravy, go serious mode\" / \"Ravy, exit serious mode.\"", color = Color(0xFF4A6B72), fontSize = 12.sp)
    }
}

// ================= Settings =================

@Composable
fun SettingsScreen(
    chatMode: Boolean,
    seriousMode: Boolean,
    onBack: () -> Unit,
    onToggleChatMode: (Boolean) -> Unit,
    onToggleSerious: (Boolean) -> Unit
) {
    ScreenScaffold("Settings", onBack) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat Mode (text instead of voice)", color = Color(0xFFCFEFF2), fontSize = 15.sp)
            Switch(checked = chatMode, onCheckedChange = onToggleChatMode)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Serious Mode", color = Color(0xFFCFEFF2), fontSize = 15.sp)
            Switch(checked = seriousMode, onCheckedChange = onToggleSerious)
        }
    }
}

// ================= Sign in =================

@Composable
fun SignInScreen(
    account: GoogleSignInAccount?,
    onBack: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    ScreenScaffold("Sign in", onBack) {
        if (account != null) {
            Text("Signed in as:", color = Color(0xFF4A6B72), fontSize = 12.sp)
            Text(account.email ?: account.displayName ?: "Unknown account", color = Color(0xFFCFEFF2), fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignOut) { Text("Sign out") }
        } else {
            Button(onClick = onGoogleSignIn) { Text("Continue with Google") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {}, enabled = false) { Text("Continue with email (not yet implemented)") }
            Spacer(Modifier.height(12.dp))
            Text(
                "A real send-code/verify-code email sign-in needs a backend that sends and checks codes — genuine server infrastructure, not something that can be faked inside this app.",
                color = Color(0xFF4A6B72), fontSize = 12.sp
            )
        }
    }
}

// ================= Static text screens =================

@Composable
fun StaticTextScreen(title: String, body: String, onBack: () -> Unit) {
    ScreenScaffold(title, onBack) {
        Text(body, color = Color(0xFFCFEFF2), fontSize = 14.sp)
    }
}

const val HELP_TEXT = "Tap the orb or mic to talk to Ravy. Say \"Hey Ravy\" once always-on listening is enabled in Permissions. Use the hamburger menu for setup: AI/Brain (Gemini), Voice (ElevenLabs or device voice), Language, and Reminders."
const val ABOUT_TEXT = "Ravy is a voice assistant built around real Android actions: every result shown or spoken reflects what Android actually reported, never a guessed or faked outcome."
const val PRIVACY_TEXT = "API keys (Gemini, ElevenLabs) are stored encrypted, only on this device, and sent only to the provider you configured. History and reminders are stored locally and can be cleared or deleted at any time from the hamburger menu."

// ================= Existing setup dialogs (unchanged logic) =================

@Composable
fun AiSetupDialog(
    onDismiss: () -> Unit,
    onSave: (RavySettings.LlmProvider, String) -> Unit
) {
    var provider by remember { mutableStateOf(RavySettings.LlmProvider.GEMINI) }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI setup") },
        text = {
            Column {
                Text("Pick your provider and paste your own API key. Stored encrypted, only on this device.")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == RavySettings.LlmProvider.GEMINI,
                        onClick = { provider = RavySettings.LlmProvider.GEMINI },
                        label = { Text("Gemini") }
                    )
                    FilterChip(
                        selected = provider == RavySettings.LlmProvider.OPENAI,
                        onClick = { provider = RavySettings.LlmProvider.OPENAI },
                        label = { Text("OpenAI") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key") },
                    singleLine = true
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(provider, key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun VoiceSetupDialog(
    onDismiss: () -> Unit,
    onSave: (RavySettings.TtsProvider, String, String) -> Unit
) {
    var provider by remember { mutableStateOf(RavySettings.TtsProvider.ELEVENLABS) }
    var key by remember { mutableStateOf("") }
    var voiceId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice setup") },
        text = {
            Column {
                Text("Use ElevenLabs for a real, higher-quality voice instead of the default on-device one. Your key is stored encrypted, only on this device.")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = provider == RavySettings.TtsProvider.ELEVENLABS,
                        onClick = { provider = RavySettings.TtsProvider.ELEVENLABS },
                        label = { Text("ElevenLabs") }
                    )
                    FilterChip(
                        selected = provider == RavySettings.TtsProvider.DEVICE,
                        onClick = { provider = RavySettings.TtsProvider.DEVICE },
                        label = { Text("Device voice") }
                    )
                }
                if (provider == RavySettings.TtsProvider.ELEVENLABS) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("ElevenLabs API key") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voiceId,
                        onValueChange = { voiceId = it },
                        label = { Text("Voice ID (optional — default is a deep male stock voice)") },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(provider, key, voiceId) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
