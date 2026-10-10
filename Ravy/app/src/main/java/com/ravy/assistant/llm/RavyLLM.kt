package com.ravy.assistant.llm

import com.ravy.assistant.settings.RavySettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls a real LLM over HTTPS using the user's own API key from RavySettings.
 * This is what makes Ravy answer arbitrary questions ("Ravy, you're mad",
 * "how are you") like a real conversational assistant instead of only
 * recognizing fixed phrases.
 *
 * MODULAR BY DESIGN: each provider (Gemini, OpenAI) is just one private
 * function that builds a request and parses that provider's response shape
 * into the same plain-text-or-structured-action result. Adding a future
 * provider means adding one more private fun + one more branch in ask() —
 * nothing else in the app needs to change.
 *
 * Nothing here is faked: if no key is configured, callers get a real,
 * honest error telling them to add one — Ravy never invents a "canned"
 * personality reply pretending it came from an LLM, and never claims a
 * device action happened just because the LLM said something like it did —
 * see ActionEngine.generalQa(), which always re-executes any action the LLM
 * proposes through the real ActionEngine before saying it worked.
 *
 * IMPORTANT: ask() does blocking network I/O. Only call it from a
 * background thread (ActionEngine already requires this everywhere).
 */
object RavyLLM {

    /**
     * If the model decides the user's request is a device action Ravy can
     * actually perform, it replies with a single line starting with this
     * marker followed by JSON matching { "action": "...", "parameters": {...} }
     * using the exact ActionType names from action/ActionSchema.kt. Otherwise
     * it just replies with normal conversational text.
     */
    private const val ACTION_MARKER = "ACTION_JSON::"

    private val SYSTEM_PROMPT = """
You are Ravy, a voice assistant created by Praise Joseph. Address the user
as "Sir". Speak naturally and conversationally, the way a sharp, warm,
slightly witty personal assistant would — think Jarvis from Iron Man, not a
customer-service bot. Keep replies short and speakable out loud (1-3
sentences) unless the user clearly wants detail.

You are the fallback brain: a simpler keyword system already handles most
device commands directly, so you are only asked when that system didn't
recognize the phrasing, or when the user is just chatting. If — and only
if — what the user said is clearly asking for one of these exact device
actions, reply with NOTHING but a single line of the form
$ACTION_MARKER{"action":"ACTION_NAME","parameters":{"key":"value"}}
using one of these exact action names and parameters, nothing else, no
extra commentary:
- OPEN_APP_BY_NAME {"name": "<app name as heard>"}
- TOGGLE_FLASHLIGHT {}
- GET_BATTERY_INFO {}
- GET_DEVICE_INFO {}
- SEARCH_CONTACT {"name": "<contact name>"}
- DIAL_NUMBER {"number": "<digits>", "sim": "1 or 2, omit if unspecified"}
- GET_SMS_COUNT {}
- GET_LOCATION {}
- LIST_RECENT_FILES {}
- GET_NOTIFICATION_SUMMARY {}
- READ_APP_NOTIFICATIONS {"appName": "<app name>"}
- READ_SCREEN {}
- GMAIL_INBOX_SUMMARY {}
- GO_BACK {}
- GO_HOME {}
- GO_RECENTS {}
- SEARCH_WEB {"query": "<search text>"}
- OPEN_MAPS {"query": "<place/address, or omit to just open Maps>"}
- SET_TIMER {"seconds": "<number of seconds>", "label": "<optional label>"}
- SET_ALARM {"hour": "<0-23>", "minute": "<0-59>", "label": "<optional label>"}
- DESCRIBE_CAMERA_VIEW {}
- GET_CALL_LOG {}
- PHONE_ACTIVITY_REPORT {"hours": "<number, default 6>"}
- SEARCH_SMS {"query": "<search text>"}
- GMAIL_SEARCH {"query": "<Gmail search text, e.g. from:john>"}
- OPEN_FILE_BY_NAME {"name": "<filename or partial name>"}
- MEDIA_CONTROL {"command": "play, pause, next, or previous"}
- GMAIL_OPEN_MESSAGE {"reference": "<e.g. 'the second one', 'this', or omit for most recent>"}
- GMAIL_MARK_READ {"reference": "<as above>"}
- GMAIL_MARK_UNREAD {"reference": "<as above>"}

Never emit $ACTION_MARKER for SEND_SMS, GMAIL_TRASH, GMAIL_REPLY, or
anything else destructive/sending — those require an in-app confirmation
flow you are not part of. If you are even slightly unsure whether the user
wants a device action, just answer conversationally instead of guessing at one.

You never claim a device action has already happened yourself — when you
do emit $ACTION_MARKER, Ravy's real Android code performs it afterward and
reports the real, true outcome back to the user, not you.
""".trim()

    class LlmException(message: String) : Exception(message)

    /** What RavyLLM decided the reply means. */
    sealed class LlmOutcome {
        data class PlainReply(val text: String) : LlmOutcome()
        data class ActionRequest(val actionName: String, val parameters: Map<String, String>) : LlmOutcome()
    }

    /**
     * [history] is a short rolling list of prior (userText, ravyText) turns
     * for conversational continuity, most recent last. Keep it small (e.g.
     * last 6 turns) — full history isn't persisted anywhere.
     */
    fun ask(context: android.content.Context, userText: String, history: List<Pair<String, String>>): LlmOutcome {
        if (!RavySettings.isConfigured(context)) {
            throw LlmException("No AI provider is set up yet. Open Ravy and tap \"AI setup\" to add your API key, Sir.")
        }
        val apiKey = RavySettings.getApiKey(context)!!
        val languageInstruction = com.ravy.assistant.language.RavyLanguage.geminiInstruction(
            com.ravy.assistant.language.RavyLanguage.current(context)
        )
        val rawText = when (RavySettings.getProvider(context)) {
            RavySettings.LlmProvider.GEMINI -> askGemini(apiKey, userText, history, languageInstruction)
            RavySettings.LlmProvider.OPENAI -> askOpenAi(apiKey, userText, history, languageInstruction)
            RavySettings.LlmProvider.NONE -> throw LlmException("No AI provider configured.")
        }
        return parseOutcome(rawText)
    }

    /**
     * Camera vision ("Ravy's eyes"): sends one captured JPEG frame straight
     * to Gemini's multimodal input and returns its real description. This
     * is a one-off request, not part of the conversational history — only
     * called from ActionEngine.describeCameraView(), and only when the
     * user explicitly asked.
     */
    fun describeImage(context: android.content.Context, jpegBytes: ByteArray): String {
        if (!RavySettings.isConfigured(context)) {
            throw LlmException("No AI provider is set up yet, Sir.")
        }
        if (RavySettings.getProvider(context) != RavySettings.LlmProvider.GEMINI) {
            throw LlmException("Camera vision currently needs Gemini configured as the AI provider, Sir.")
        }
        val apiKey = RavySettings.getApiKey(context)!!
        val base64Image = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)

        val parts = JSONArray()
        parts.put(JSONObject().put("text", "Describe concisely and naturally what is visible in this photo, as if speaking aloud to someone who cannot see it themselves. One or two sentences."))
        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", base64Image)))

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            put("generationConfig", JSONObject().put("maxOutputTokens", 200))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        val conn = openConnection(url)
        conn.setRequestProperty("x-goog-api-key", apiKey)
        writeBody(conn, body)

        val responseJson = readResponse(conn)
        val candidates = responseJson.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val resultParts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val text = resultParts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text", "") } }
            if (!text.isNullOrBlank()) return text
        }
        val errorMessage = responseJson.optJSONObject("error")?.optString("message")
        throw LlmException(errorMessage ?: "Gemini didn't return a usable description.")
    }

    private fun parseOutcome(rawText: String): LlmOutcome {
        val trimmed = rawText.trim()
        if (trimmed.startsWith(ACTION_MARKER)) {
            return try {
                val json = JSONObject(trimmed.removePrefix(ACTION_MARKER).trim())
                val actionName = json.getString("action")
                val paramsJson = json.optJSONObject("parameters") ?: JSONObject()
                val params = mutableMapOf<String, String>()
                paramsJson.keys().forEach { key -> params[key] = paramsJson.optString(key) }
                LlmOutcome.ActionRequest(actionName, params)
            } catch (e: Exception) {
                // Malformed structured-action attempt — fail safe to plain text
                // rather than silently doing nothing or guessing at an action.
                LlmOutcome.PlainReply("I wasn't able to work that one out cleanly, Sir. Could you rephrase it?")
            }
        }
        return LlmOutcome.PlainReply(trimmed.ifBlank { "I didn't get a reply back, Sir." })
    }

    // ---------- Gemini (Google AI Studio / Generative Language API) ----------

    /**
     * Model name lives here in exactly one place.
     *
     * "gemini-2.0-flash" was retired by Google and now 404s — that was the
     * cause of the "model ... is no longer available" error. Replaced with
     * "gemini-2.5-flash": the current stable, non-preview fast model, per
     * Google's own model listing (ai.google.dev/gemini-api/docs/models) as
     * of this fix. Deliberately NOT one of the newer "gemini-3.x" names —
     * several of those are explicitly marked "preview", which means less
     * guaranteed availability/stability than a GA model. If this ever 404s
     * again, swap it for whatever that page currently lists as stable.
     *
     * The REST request/response shape itself (contents/systemInstruction/
     * generationConfig in, candidates[].content.parts[].text out) is
     * unchanged in current docs — only the model name and auth method
     * needed fixing here.
     */
    private const val GEMINI_MODEL = "gemini-2.5-flash"

    private fun askGemini(apiKey: String, userText: String, history: List<Pair<String, String>>, languageInstruction: String): String {
        val contents = JSONArray()
        history.forEach { (u, a) ->
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", u))))
            contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", a))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))

        val body = JSONObject().apply {
            put("contents", contents)
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT + languageInstruction))))
            put("generationConfig", JSONObject().put("maxOutputTokens", 300))
        }

        // Current docs show the API key passed as the x-goog-api-key header
        // (not only the older ?key= query param) — using the header form here.
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
        val conn = openConnection(url)
        conn.setRequestProperty("x-goog-api-key", apiKey)
        writeBody(conn, body)

        val responseJson = readResponse(conn)
        val candidates = responseJson.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val text = parts?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text", "") } }
            if (!text.isNullOrBlank()) return text
        }
        val errorMessage = responseJson.optJSONObject("error")?.optString("message")
        throw LlmException(errorMessage ?: "Unexpected response from Gemini.")
    }

    // ---------- OpenAI (kept as a secondary option, same structured-action protocol) ----------

    private fun askOpenAi(apiKey: String, userText: String, history: List<Pair<String, String>>, languageInstruction: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT + languageInstruction))
        history.forEach { (u, a) ->
            messages.put(JSONObject().put("role", "user").put("content", u))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("max_tokens", 300)
            put("messages", messages)
        }

        val conn = openConnection("https://api.openai.com/v1/chat/completions")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        writeBody(conn, body)

        val responseJson = readResponse(conn)
        val choices = responseJson.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            val text = msg?.optString("content", "")
            if (!text.isNullOrBlank()) return text
        }
        throw LlmException(responseJson.optJSONObject("error")?.optString("message") ?: "Unexpected response from OpenAI.")
    }

    // ---------- shared HTTP plumbing ----------

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
    }

    private fun readResponse(conn: HttpURLConnection): JSONObject {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw LlmException("Could not parse the AI provider's response (HTTP ${conn.responseCode}).")
        }
    }
}
