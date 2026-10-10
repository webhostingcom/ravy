package com.ravy.assistant.gmail

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Real Gmail integration — plain REST calls to the Gmail API over HTTPS,
 * using an OAuth token from Google Sign-In. This deliberately avoids
 * Google's official "google-api-client-android" + "google-api-services-gmail"
 * Java libraries: they are large generated-code artifacts that were pushing
 * Kotlin compilation memory past what a phone-based build (e.g. CodeAssist)
 * can handle, causing real "out of memory" build failures. This does the
 * exact same job with a fraction of the dependency footprint — the same
 * plain-HTTP pattern already used for Gemini and ElevenLabs elsewhere in
 * this app.
 *
 * SETUP REQUIRED BEFORE THIS WORKS (see README "Gmail setup"):
 * 1. Create a Google Cloud project, enable the Gmail API.
 * 2. Create an OAuth 2.0 Android client ID tied to this app's package name
 *    (com.ravy.assistant) and your debug/release signing certificate's SHA-1.
 * 3. Add your Google account as a "test user" on the OAuth consent screen
 *    (or complete verification for production use).
 *
 * Ravy cannot do this part for you. Until it's done, sign-in or token
 * requests fail with a real Google error, and Ravy reports NEEDS_PERMISSION
 * / FAILED honestly rather than pretend email access works.
 */
object GmailAuth {

    /**
     * Expanded from read-only to also include modify (archive/trash/mark
     * read-unread) and send (reply) — required for the interactive Gmail
     * assistant features. IMPORTANT: anyone who already signed into Gmail
     * under the old read-only-only scope must sign in again once for this
     * build; Google requires fresh consent whenever requested scopes grow,
     * it cannot be silently upgraded.
     */
    private const val GMAIL_SCOPE =
        "oauth2:https://www.googleapis.com/auth/gmail.modify https://www.googleapis.com/auth/gmail.send"

    fun signInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope("https://www.googleapis.com/auth/gmail.modify"),
                Scope("https://www.googleapis.com/auth/gmail.send")
            )
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun signInIntent(context: Context): Intent = signInClient(context).signInIntent

    fun lastSignedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    /**
     * Blocking call to fetch a short-lived OAuth access token for the signed-in
     * account — only ever call from a background thread. Throws a real
     * exception (including UserRecoverableAuthException, which callers can
     * catch to prompt re-consent) rather than returning a fake token.
     */
    fun getAccessToken(context: Context, account: GoogleSignInAccount): String {
        val androidAccount = account.account
            ?: throw IllegalStateException("Signed-in Google account is missing its underlying Account object.")
        return GoogleAuthUtil.getToken(context, androidAccount, GMAIL_SCOPE)
    }
}

data class GmailSummaryItem(val id: String, val from: String, val subject: String, val snippet: String)

/**
 * Blocking network calls throughout — always invoke from a background
 * thread/coroutine, never the main thread (ActionEngine already guarantees
 * this everywhere it calls in here).
 */
object GmailClient {

    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    /**
     * Real Gmail search using the API's own query syntax (e.g. "from:john",
     * "subject:invoice", or just plain words) — the same search Gmail's own
     * UI uses, via the `q=` parameter on the real messages.list endpoint.
     */
    fun searchMessages(context: Context, account: GoogleSignInAccount, query: String, maxResults: Int = 5): List<GmailSummaryItem> {
        val token = GmailAuth.getAccessToken(context, account)
        val listJson = getJson("$BASE/messages?maxResults=$maxResults&q=" + java.net.URLEncoder.encode(query, "UTF-8"), token)
        val messageRefs = listJson.optJSONArray("messages") ?: return emptyList()

        val items = mutableListOf<GmailSummaryItem>()
        for (i in 0 until messageRefs.length()) {
            val id = messageRefs.getJSONObject(i).getString("id")
            val msgJson = getJson("$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject", token)
            val headers = msgJson.optJSONObject("payload")?.optJSONArray("headers")
            var from = "Unknown sender"
            var subject = "(no subject)"
            if (headers != null) {
                for (h in 0 until headers.length()) {
                    val header = headers.getJSONObject(h)
                    when (header.optString("name")) {
                        "From" -> from = header.optString("value", from)
                        "Subject" -> subject = header.optString("value", subject)
                    }
                }
            }
            items.add(GmailSummaryItem(id = id, from = from, subject = subject, snippet = msgJson.optString("snippet", "")))
        }
        return items
    }

    /** Fetches the newest [maxResults] inbox messages' sender + subject only (not full body). */
    fun fetchInboxSummary(context: Context, account: GoogleSignInAccount, maxResults: Int = 10): List<GmailSummaryItem> {
        val token = GmailAuth.getAccessToken(context, account)

        val listJson = getJson("$BASE/messages?maxResults=$maxResults&labelIds=INBOX", token)
        val messageRefs = listJson.optJSONArray("messages") ?: return emptyList()

        val items = mutableListOf<GmailSummaryItem>()
        for (i in 0 until messageRefs.length()) {
            val id = messageRefs.getJSONObject(i).getString("id")
            val msgJson = getJson(
                "$BASE/messages/$id?format=metadata&metadataHeaders=From&metadataHeaders=Subject",
                token
            )
            val headers = msgJson.optJSONObject("payload")?.optJSONArray("headers")
            var from = "Unknown sender"
            var subject = "(no subject)"
            if (headers != null) {
                for (h in 0 until headers.length()) {
                    val header = headers.getJSONObject(h)
                    when (header.optString("name")) {
                        "From" -> from = header.optString("value", from)
                        "Subject" -> subject = header.optString("value", subject)
                    }
                }
            }
            items.add(GmailSummaryItem(id = id, from = from, subject = subject, snippet = msgJson.optString("snippet", "")))
        }
        return items
    }

    /** Full body text of one message — only ever called when the user explicitly asks to hear it. */
    fun fetchFullMessageText(context: Context, account: GoogleSignInAccount, messageId: String): String {
        val token = GmailAuth.getAccessToken(context, account)
        val msgJson = getJson("$BASE/messages/$messageId?format=full", token)
        val bodyData = msgJson.optJSONObject("payload")?.optJSONObject("body")?.optString("data")
        if (!bodyData.isNullOrBlank()) {
            return try {
                String(Base64.getUrlDecoder().decode(bodyData))
            } catch (e: Exception) {
                msgJson.optString("snippet", "")
            }
        }
        // Multipart messages keep body text under parts[] instead of the top-level body.
        val parts = msgJson.optJSONObject("payload")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val partData = parts.getJSONObject(i).optJSONObject("body")?.optString("data")
                if (!partData.isNullOrBlank()) {
                    return try {
                        String(Base64.getUrlDecoder().decode(partData))
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
        return msgJson.optString("snippet", "")
    }

    // ---------- Real modify/send operations (require gmail.modify + gmail.send scope) ----------

    private fun modifyLabels(context: Context, account: GoogleSignInAccount, messageId: String, add: List<String>, remove: List<String>) {
        val token = GmailAuth.getAccessToken(context, account)
        val body = JSONObject().put("addLabelIds", JSONArray(add)).put("removeLabelIds", JSONArray(remove))
        postJson("$BASE/messages/$messageId/modify", token, body)
    }

    /** Real archive — removes the INBOX label, same effect as tapping Archive in Gmail. */
    fun archiveMessage(context: Context, account: GoogleSignInAccount, messageId: String) {
        modifyLabels(context, account, messageId, add = emptyList(), remove = listOf("INBOX"))
    }

    /** Real delete, via Gmail's own trash endpoint (moves to Trash, same as the Gmail app's delete button). */
    fun trashMessage(context: Context, account: GoogleSignInAccount, messageId: String) {
        val token = GmailAuth.getAccessToken(context, account)
        postJson("$BASE/messages/$messageId/trash", token, JSONObject())
    }

    fun markRead(context: Context, account: GoogleSignInAccount, messageId: String) {
        modifyLabels(context, account, messageId, add = emptyList(), remove = listOf("UNREAD"))
    }

    fun markUnread(context: Context, account: GoogleSignInAccount, messageId: String) {
        modifyLabels(context, account, messageId, add = listOf("UNREAD"), remove = emptyList())
    }

    /**
     * Real reply: fetches the original message's From/Subject/Message-ID so
     * the reply threads correctly (In-Reply-To/References headers, same
     * Gmail thread), builds a real RFC 2822 message, and sends it via the
     * actual Gmail send endpoint — not a draft, not a simulated success.
     */
    fun sendReply(context: Context, account: GoogleSignInAccount, messageId: String, replyBody: String): String {
        val token = GmailAuth.getAccessToken(context, account)
        val original = getJson("$BASE/messages/$messageId?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Message-ID", token)
        val headers = original.optJSONObject("payload")?.optJSONArray("headers")
        var fromHeader = ""
        var subjectHeader = "(no subject)"
        var messageIdHeader = ""
        if (headers != null) {
            for (h in 0 until headers.length()) {
                val header = headers.getJSONObject(h)
                when (header.optString("name")) {
                    "From" -> fromHeader = header.optString("value", "")
                    "Subject" -> subjectHeader = header.optString("value", subjectHeader)
                    "Message-ID" -> messageIdHeader = header.optString("value", "")
                }
            }
        }
        val replyToAddress = Regex("""<([^>]+)>""").find(fromHeader)?.groupValues?.get(1) ?: fromHeader
        val threadId = original.optString("threadId")
        val replySubject = if (subjectHeader.startsWith("Re:", ignoreCase = true)) subjectHeader else "Re: $subjectHeader"

        val raw = buildString {
            append("To: $replyToAddress\r\n")
            append("Subject: $replySubject\r\n")
            if (messageIdHeader.isNotBlank()) {
                append("In-Reply-To: $messageIdHeader\r\n")
                append("References: $messageIdHeader\r\n")
            }
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("\r\n")
            append(replyBody)
        }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        val body = JSONObject().put("raw", encoded).apply { if (threadId.isNotBlank()) put("threadId", threadId) }
        postJson("$BASE/messages/send", token, body)
        return replyToAddress
    }

    private fun postJson(url: String, token: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (conn.responseCode !in 200..299) {
            val message = json.optJSONObject("error")?.optString("message") ?: "Gmail API error ${conn.responseCode}"
            throw IllegalStateException(message)
        }
        return json
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("Gmail API returned an unreadable response (HTTP ${conn.responseCode}).")
        }
        if (conn.responseCode !in 200..299) {
            val message = json.optJSONObject("error")?.optString("message") ?: "Gmail API error ${conn.responseCode}"
            throw IllegalStateException(message)
        }
        return json
    }
}
