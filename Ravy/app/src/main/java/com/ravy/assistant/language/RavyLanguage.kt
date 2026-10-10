package com.ravy.assistant.language

import android.content.Context
import android.speech.tts.TextToSpeech
import com.ravy.assistant.settings.RavySettings
import java.util.Locale

/**
 * Real language architecture: adding a language means adding one line to
 * [SUPPORTED] below — nothing else in the app needs to change. This covers
 * the UI's selected language (persisted in RavySettings), which locale is
 * requested from Android's speech recognizer, which locale the on-device
 * TTS is asked to use, and what instruction is given to Gemini so its
 * replies — not just the menu labels — come back in that language.
 *
 * HONEST LIMIT: whether a given locale actually works for speech recognition
 * or text-to-speech depends on what language packs are installed on this
 * specific device — something only Android itself knows at runtime, not
 * something this list can promise. [bestAvailableTtsLocale] checks for real
 * and falls back with a plain explanation rather than silently guessing.
 */
object RavyLanguage {

    data class LanguageOption(val code: String, val displayName: String, val locale: Locale)

    val SUPPORTED = listOf(
        LanguageOption("en", "English", Locale.ENGLISH),
        LanguageOption("es", "Español (Spanish)", Locale("es")),
        LanguageOption("fr", "Français (French)", Locale.FRENCH),
        LanguageOption("de", "Deutsch (German)", Locale.GERMAN),
        LanguageOption("pt", "Português (Portuguese)", Locale("pt")),
        LanguageOption("ar", "العربية (Arabic)", Locale("ar")),
        LanguageOption("hi", "हिन्दी (Hindi)", Locale("hi")),
        LanguageOption("yo", "Yorùbá (Yoruba)", Locale("yo")),
        LanguageOption("ig", "Igbo", Locale("ig")),
        LanguageOption("ha", "Hausa", Locale("ha"))
    )

    fun byCode(code: String): LanguageOption = SUPPORTED.firstOrNull { it.code == code } ?: SUPPORTED[0]

    fun current(context: Context): LanguageOption = byCode(RavySettings.getLanguageCode(context))

    /**
     * Checks the on-device TTS engine for real support of [desired]. If
     * unsupported, returns English with [fellBack] = true instead of
     * silently using an unsupported locale (which on many devices just
     * speaks in the default language anyway, pretending the setting took
     * effect when it didn't).
     */
    fun bestAvailableTtsLocale(tts: TextToSpeech?, desired: LanguageOption): Pair<LanguageOption, Boolean> {
        if (tts == null) return desired to false
        val availability = tts.isLanguageAvailable(desired.locale)
        return if (availability >= TextToSpeech.LANG_AVAILABLE) {
            desired to false
        } else {
            SUPPORTED[0] to true // English fallback
        }
    }

    /** Instruction appended to Gemini's system prompt so replies — not just UI labels — use the chosen language. */
    fun geminiInstruction(option: LanguageOption): String =
        if (option.code == "en") "" else "\n\nRespond only in ${option.displayName}, regardless of what language this instruction is written in."
}
