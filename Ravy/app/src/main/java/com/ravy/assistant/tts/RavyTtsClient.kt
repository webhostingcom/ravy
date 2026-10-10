package com.ravy.assistant.tts

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real ElevenLabs text-to-speech over HTTPS, using the user's own API key.
 * Produces an actual MP3 file Ravy plays back — nothing here is a canned
 * sample or a fake "better voice" flag; if the call fails (bad key, no
 * network, rate limit), it throws a real exception and RavyCore falls back
 * to the on-device voice rather than pretending this worked.
 *
 * Modular by design: this is the only file that knows about ElevenLabs
 * specifically. RavyCore just calls synthesizeToFile() and doesn't care
 * which cloud provider it is — swapping to a different TTS provider later
 * means writing one more file like this one, not touching RavyCore.
 */
object RavyTtsClient {

    /**
     * "Adam" — a public ElevenLabs stock voice, deep and even-toned, the
     * closest free stock option to a Jarvis-style delivery. Overridable in
     * the app's "Voice setup" dialog if the user has a different voice ID
     * from their own ElevenLabs library.
     */
    const val DEFAULT_VOICE_ID = "pNInz6obpgDQGcFmaJgB"

    class TtsException(message: String) : Exception(message)

    /** Blocking network I/O — only call from a background thread. */
    fun synthesizeToFile(context: Context, text: String, apiKey: String, voiceId: String): File {
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("xi-api-key", apiKey)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "audio/mpeg")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000

        val body = """
            {"text": ${jsonEscape(text)}, "model_id": "eleven_multilingual_v2",
             "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}}
        """.trimIndent()
        conn.outputStream.use { it.write(body.toByteArray()) }

        if (conn.responseCode !in 200..299) {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
            throw TtsException("ElevenLabs error: $errorText")
        }

        val outFile = File(context.cacheDir, "ravy_tts_${System.currentTimeMillis()}.mp3")
        conn.inputStream.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return outFile
    }

    private fun jsonEscape(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        return "\"$escaped\""
    }
}
