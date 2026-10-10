package com.ravy.assistant.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores exactly two things, encrypted on-device: which LLM provider you
 * chose, and your own API key for it. Ravy ships with neither — general
 * question-answering (RavyLLM) stays disabled until you fill these in via
 * MainActivity's "AI setup" chip.
 *
 * Nothing here is sent anywhere except directly to the provider you chose,
 * over HTTPS, exactly like any app you'd install that calls that provider.
 */
object RavySettings {

    private const val PREFS_NAME = "ravy_secure_prefs"
    private const val KEY_PROVIDER = "llm_provider"
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_TTS_PROVIDER = "tts_provider"
    private const val KEY_TTS_API_KEY = "tts_api_key"
    private const val KEY_TTS_VOICE_ID = "tts_voice_id"

    enum class LlmProvider { GEMINI, OPENAI, NONE }
    enum class TtsProvider { DEVICE, ELEVENLABS }

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Plain (unencrypted) prefs for non-sensitive UI toggles — no reason to
    // pay encryption overhead for "is chat mode on".
    private const val UI_PREFS_NAME = "ravy_ui_prefs"
    private const val KEY_CHAT_MODE = "chat_mode"
    private const val KEY_SERIOUS_MODE = "serious_mode"
    private const val KEY_LANGUAGE_CODE = "language_code"
    private const val KEY_SPEECH_RATE = "speech_rate"

    private fun uiPrefs(context: Context) = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)

    fun isChatMode(context: Context): Boolean = uiPrefs(context).getBoolean(KEY_CHAT_MODE, false)
    fun setChatMode(context: Context, enabled: Boolean) { uiPrefs(context).edit().putBoolean(KEY_CHAT_MODE, enabled).apply() }

    fun isSeriousMode(context: Context): Boolean = uiPrefs(context).getBoolean(KEY_SERIOUS_MODE, false)
    fun setSeriousMode(context: Context, enabled: Boolean) { uiPrefs(context).edit().putBoolean(KEY_SERIOUS_MODE, enabled).apply() }

    /** Defaults to "en" (English) — see language/RavyLanguage.kt for the supported list. */
    fun getLanguageCode(context: Context): String = uiPrefs(context).getString(KEY_LANGUAGE_CODE, "en") ?: "en"
    fun setLanguageCode(context: Context, code: String) { uiPrefs(context).edit().putString(KEY_LANGUAGE_CODE, code).apply() }

    fun getSpeechRate(context: Context): Float = uiPrefs(context).getFloat(KEY_SPEECH_RATE, 1.0f)
    fun setSpeechRate(context: Context, rate: Float) { uiPrefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply() }

    fun getProvider(context: Context): LlmProvider =
        when (prefs(context).getString(KEY_PROVIDER, null)) {
            "GEMINI" -> LlmProvider.GEMINI
            "OPENAI" -> LlmProvider.OPENAI
            else -> LlmProvider.NONE
        }

    fun getApiKey(context: Context): String? = prefs(context).getString(KEY_API_KEY, null)

    fun save(context: Context, provider: LlmProvider, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun isConfigured(context: Context): Boolean =
        getProvider(context) != LlmProvider.NONE && !getApiKey(context).isNullOrBlank()

    // ---------- Text-to-speech (voice) settings ----------

    fun getTtsProvider(context: Context): TtsProvider =
        if (prefs(context).getString(KEY_TTS_PROVIDER, null) == "ELEVENLABS") TtsProvider.ELEVENLABS else TtsProvider.DEVICE

    fun getTtsApiKey(context: Context): String? = prefs(context).getString(KEY_TTS_API_KEY, null)

    fun getTtsVoiceId(context: Context): String =
        prefs(context).getString(KEY_TTS_VOICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: com.ravy.assistant.tts.RavyTtsClient.DEFAULT_VOICE_ID

    fun saveTts(context: Context, provider: TtsProvider, apiKey: String, voiceId: String) {
        prefs(context).edit()
            .putString(KEY_TTS_PROVIDER, provider.name)
            .putString(KEY_TTS_API_KEY, apiKey)
            .putString(KEY_TTS_VOICE_ID, voiceId)
            .apply()
    }

    fun isTtsConfigured(context: Context): Boolean =
        getTtsProvider(context) == TtsProvider.ELEVENLABS && !getTtsApiKey(context).isNullOrBlank()
}
