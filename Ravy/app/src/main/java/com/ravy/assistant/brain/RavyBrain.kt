package com.ravy.assistant.brain

import com.ravy.assistant.action.ActionType
import com.ravy.assistant.action.RavyAction

/**
 * PLACEHOLDER BRAIN — simple keyword matching, not a language model.
 * Swap this for a call to your real ravyBrain backend, which should return
 * the same RavyAction shape (see action/ActionSchema.kt).
 */
class RavyBrain {

    /** Set by MainActivity right before a SEND_SMS is re-issued after the user says "yes, send it". */
    var pendingConfirmation: RavyAction? = null

    fun interpret(utterance: String): RavyAction {
        val text = utterance.lowercase().trim()

        // Confirmation handling for whatever action is currently pending.
        pendingConfirmation?.let { pending ->
            if (text.contains("yes")) {
                pendingConfirmation = null
                return pending.copy(requiresConfirmation = true)
            }
            if (text.contains("no") || text.contains("cancel")) {
                pendingConfirmation = null
                return RavyAction(action = ActionType.UNKNOWN, reason = "User cancelled the pending action")
            }
        }

        // "open <anything>" — resolved live against installed apps in ActionEngine,
        // not limited to a fixed list. Speech recognition mishears are tolerated
        // there via fuzzy matching, so we just pass through what was heard.
        if (text.startsWith("open ")) {
            val name = text.removePrefix("open ").trim()
            if (name.isNotBlank()) {
                return RavyAction(ActionType.OPEN_APP_BY_NAME, mapOf("name" to name), reason = "User asked to open $name")
            }
        }

        if (text.contains("flashlight") || text.contains("torch")) {
            return RavyAction(ActionType.TOGGLE_FLASHLIGHT, reason = "User asked about the flashlight")
        }
        if (text.contains("battery")) {
            return RavyAction(ActionType.GET_BATTERY_INFO, reason = "User asked about battery")
        }
        if (text.contains("device info") || text.contains("what phone")) {
            return RavyAction(ActionType.GET_DEVICE_INFO, reason = "User asked about the device")
        }

        if (text.startsWith("call ")) {
            val name = text.removePrefix("call ").trim()
            return RavyAction(ActionType.SEARCH_CONTACT, mapOf("name" to name), reason = "User asked to call $name")
            // Note: MainActivity resolves the found number, then issues CALL_CONTACT.
        }
        if (text.contains("find contact") || text.contains("search contact")) {
            val name = text.substringAfterLast(" ").trim()
            return RavyAction(ActionType.SEARCH_CONTACT, mapOf("name" to name), reason = "User searched for a contact")
        }

        parseDial(text)?.let { return it }

        if (text.contains("unread text") || text.contains("new messages") || text.contains("text messages")) {
            return RavyAction(ActionType.GET_SMS_COUNT, reason = "User asked about SMS")
        }

        parseSendSms(text)?.let { return it }

        if (text.contains("where am i") || text.contains("my location")) {
            return RavyAction(ActionType.GET_LOCATION, reason = "User asked for current location")
        }

        if (text.contains("recent files") || text.contains("my files")) {
            return RavyAction(ActionType.LIST_RECENT_FILES, reason = "User asked about recent files")
        }

        parseReadAppNotifications(text)?.let { return it }

        if (text.contains("notification")) {
            return RavyAction(ActionType.GET_NOTIFICATION_SUMMARY, reason = "User asked about notifications")
        }

        if (text.contains("read the screen") || text.contains("read screen") || text.contains("what's on screen")) {
            return RavyAction(ActionType.READ_SCREEN, reason = "User asked Ravy to read the screen")
        }

        if (text.contains("sign into gmail") || text.contains("sign in to gmail") || text.contains("connect gmail")) {
            return RavyAction(ActionType.GMAIL_SIGN_IN, reason = "User asked to sign into Gmail")
        }
        if (text.contains("check email") || text.contains("check my email") || text.contains("check my inbox") || text.contains("read my email")) {
            return RavyAction(ActionType.GMAIL_INBOX_SUMMARY, reason = "User asked for an email summary")
        }

        if (text.contains("serious mode")) {
            val on = !(text.contains("exit") || text.contains("stop") || text.contains("turn off") || text.contains("leave"))
            return RavyAction(ActionType.SET_SERIOUS_MODE, mapOf("on" to on.toString()), reason = "User toggled serious mode")
        }

        // ---- Navigation ----
        if (text == "go back" || text == "back" || text.endsWith(" go back")) {
            return RavyAction(ActionType.GO_BACK, reason = "User asked to go back")
        }
        if (text == "go home" || text == "home" || text.endsWith(" go home")) {
            return RavyAction(ActionType.GO_HOME, reason = "User asked to go home")
        }
        if (text.contains("recent apps") || text.contains("switch apps") || text == "recents") {
            return RavyAction(ActionType.GO_RECENTS, reason = "User asked for recent apps")
        }

        // ---- Web search / Maps ----
        // Matches "search [the web/chrome/google] for X" and "open chrome and search for X" alike —
        // both are satisfied by opening a real search results page directly.
        Regex("""search(?: the web| chrome| google)? for (.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.SEARCH_WEB, mapOf("query" to m.groupValues[1].trim()), reason = "User asked to search the web")
        }
        Regex("""open chrome and search (?:for )?(.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.SEARCH_WEB, mapOf("query" to m.groupValues[1].trim()), reason = "User asked to open Chrome and search")
        }
        Regex("""(?:open )?maps(?: and search for| for)? (.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.OPEN_MAPS, mapOf("query" to m.groupValues[1].trim()), reason = "User asked to open Maps")
        }
        if (text == "open maps" || text == "open the map") {
            return RavyAction(ActionType.OPEN_MAPS, reason = "User asked to open Maps")
        }

        // ---- Timers / Alarms ----
        Regex("""set a timer for (\d+) ?(second|minute|hour)s?""").find(text)?.let { m ->
            val amount = m.groupValues[1].toInt()
            val unit = m.groupValues[2]
            val seconds = when (unit) {
                "minute" -> amount * 60
                "hour" -> amount * 3600
                else -> amount
            }
            return RavyAction(ActionType.SET_TIMER, mapOf("seconds" to seconds.toString()), reason = "User asked for a timer")
        }
        Regex("""set an alarm for (\d{1,2})(?::(\d{2}))? ?(am|pm)?""").find(text)?.let { m ->
            val h12 = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            val isPm = m.groupValues[3] == "pm"
            var hour24 = h12 % 12
            if (isPm) hour24 += 12
            return RavyAction(ActionType.SET_ALARM, mapOf("hour" to hour24.toString(), "minute" to minute.toString()), reason = "User asked for an alarm")
        }

        // ---- Camera vision ----
        if (text.contains("what are you seeing") || text.contains("what do you see") || text.contains("what am i looking at") ||
            text.contains("describe what's in front") || text.contains("describe what is in front")
        ) {
            return RavyAction(ActionType.DESCRIBE_CAMERA_VIEW, reason = "User asked Ravy to describe what the camera sees")
        }

        // ---- Call log / phone activity report ----
        if (text.contains("recent calls") || text.contains("call log") || text.contains("who called")) {
            return RavyAction(ActionType.GET_CALL_LOG, reason = "User asked about recent calls")
        }
        Regex("""(?:report|happened).*last (\d+) ?hours?""").find(text)?.let { m ->
            return RavyAction(ActionType.PHONE_ACTIVITY_REPORT, mapOf("hours" to m.groupValues[1]), reason = "User asked for a phone activity report")
        }
        if (text.contains("what happened on my phone today") || text.contains("what did i miss")) {
            return RavyAction(ActionType.PHONE_ACTIVITY_REPORT, mapOf("hours" to "24"), reason = "User asked what they missed")
        }
        if (text.contains("while i was sleeping") || text.contains("while i was away")) {
            return RavyAction(ActionType.PHONE_ACTIVITY_REPORT, mapOf("hours" to "8"), reason = "User asked what happened while away")
        }

        // ---- SMS / Gmail search ----
        Regex("""search (?:my )?(?:text|texts|messages|sms) for (.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.SEARCH_SMS, mapOf("query" to m.groupValues[1].trim()), reason = "User asked to search SMS")
        }
        Regex("""search (?:my )?(?:email|emails|gmail|inbox) for (.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.GMAIL_SEARCH, mapOf("query" to m.groupValues[1].trim()), reason = "User asked to search email")
        }

        // ---- Gmail interactive context ("this"/"that"/"the second one") ----
        if (Regex("""open (?:the )?(?:this|that|first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|number \d+)\b.*(email|message)""").containsMatchIn(text) ||
            text.contains("open this email") || text.contains("open that email")
        ) {
            return RavyAction(ActionType.GMAIL_OPEN_MESSAGE, mapOf("reference" to text), reason = "User asked to open a specific email")
        }
        if ((text.contains("archive")) && (text.contains("this") || text.contains("that") || text.contains("email"))) {
            return RavyAction(ActionType.GMAIL_ARCHIVE, mapOf("reference" to text), reason = "User asked to archive an email")
        }
        if ((text.contains("delete") || text.contains("trash")) && (text.contains("email") || text.contains("this") || text.contains("that"))) {
            return RavyAction(ActionType.GMAIL_TRASH, mapOf("reference" to text), reason = "User asked to delete an email")
        }
        if (text.contains("mark") && text.contains("read") && !text.contains("unread")) {
            return RavyAction(ActionType.GMAIL_MARK_READ, mapOf("reference" to text), reason = "User asked to mark an email read")
        }
        if (text.contains("mark") && text.contains("unread")) {
            return RavyAction(ActionType.GMAIL_MARK_UNREAD, mapOf("reference" to text), reason = "User asked to mark an email unread")
        }
        Regex("""reply(?: to (?:this|that|it))? saying (.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.GMAIL_REPLY, mapOf("reference" to text, "body" to m.groupValues[1].trim()), reason = "User asked to reply to an email")
        }

        // ---- Files ----
        Regex("""open(?: the)? file (?:called |named )?(.+)""").find(text)?.let { m ->
            return RavyAction(ActionType.OPEN_FILE_BY_NAME, mapOf("name" to m.groupValues[1].trim()), reason = "User asked to open a file")
        }

        // ---- Media controls ----
        if (text.contains("pause the music") || text == "pause") {
            return RavyAction(ActionType.MEDIA_CONTROL, mapOf("command" to "pause"), reason = "User asked to pause media")
        }
        if (text.contains("play the music") || text.contains("resume the music") || text == "play") {
            return RavyAction(ActionType.MEDIA_CONTROL, mapOf("command" to "play"), reason = "User asked to play media")
        }
        if (text.contains("next song") || text.contains("skip this song") || text.contains("next track")) {
            return RavyAction(ActionType.MEDIA_CONTROL, mapOf("command" to "next"), reason = "User asked to skip to next track")
        }
        if (text.contains("previous song") || text.contains("previous track")) {
            return RavyAction(ActionType.MEDIA_CONTROL, mapOf("command" to "previous"), reason = "User asked for previous track")
        }

        parseReminder(text)?.let { return it }

        // Nothing structured matched — hand it to the real LLM instead of
        // saying "I don't understand." This is what lets Ravy answer
        // "how are you", "you're mad", or anything else conversationally.
        // Requires an API key configured (RavySettings) — ActionEngine
        // reports NEEDS_PERMISSION honestly if none is set up yet.
        return RavyAction(ActionType.GENERAL_QA, mapOf("query" to utterance.trim()), reason = "Open-ended question, routed to the configured LLM")
    }

    /** "dial 12345 with sim 2", "dial 12345 using sim one", or a plain "dial 12345". */
    private fun parseDial(text: String): RavyAction? {
        val numberPattern = "[0-9+][0-9+\\- ]{4,}"

        Regex("""dial ($numberPattern) (?:with|using|on) sim (1|2|one|two)""")
            .find(text)?.let { m ->
                val number = m.groupValues[1].trim()
                val sim = if (m.groupValues[2].startsWith("2") || m.groupValues[2] == "two") "2" else "1"
                return RavyAction(ActionType.DIAL_NUMBER, mapOf("number" to number, "sim" to sim), reason = "User asked to dial $number on SIM $sim")
            }

        Regex("""dial ($numberPattern)""").find(text)?.let { m ->
            return RavyAction(ActionType.DIAL_NUMBER, mapOf("number" to m.groupValues[1].trim()), reason = "User asked to dial a number")
        }

        return null
    }

    /** "read whatsapp notifications", "read the message from whatsapp", "read whatsapp message". */
    private fun parseReadAppNotifications(text: String): RavyAction? {
        Regex("""read (?:the )?(?:message|messages|notification|notifications) from (.+)""")
            .find(text)?.let { m ->
                return RavyAction(ActionType.READ_APP_NOTIFICATIONS, mapOf("appName" to m.groupValues[1].trim()), reason = "User asked to read notifications from an app")
            }
        Regex("""read (?:my )?(.+?) notifications?""").find(text)?.let { m ->
            return RavyAction(ActionType.READ_APP_NOTIFICATIONS, mapOf("appName" to m.groupValues[1].trim()), reason = "User asked to read an app's notifications")
        }
        Regex("""read (?:the )?(.+?) message""").find(text)?.let { m ->
            return RavyAction(ActionType.READ_APP_NOTIFICATIONS, mapOf("appName" to m.groupValues[1].trim()), reason = "User asked to read an app's message")
        }
        return null
    }

    /**
     * Recognizes a handful of common phrasings for sending a text message.
     * Always returns SEND_SMS with requiresConfirmation left false — that's
     * intentional: ActionEngine.sendSms() sees requiresConfirmation == false
     * and responds NEEDS_CONFIRMATION instead of sending, which is what puts
     * this action into brain.pendingConfirmation until the user says "yes".
     * Only the re-issued copy (after "yes") carries requiresConfirmation = true.
     *
     * Matches things like:
     *   "send hello with sms app to 1234657"
     *   "send hello by sms to 1234657"
     *   "send sms to 1234657 saying hello"
     *   "text 1234657 saying hello"
     */
    private fun parseSendSms(text: String): RavyAction? {
        val numberPattern = "[0-9+][0-9+\\- ]{4,}"

        Regex("""send (.+?) (?:with|by|via) sms(?: app)? to ($numberPattern)""")
            .find(text)?.let { m ->
                val body = m.groupValues[1].trim()
                val number = m.groupValues[2].trim()
                return RavyAction(
                    ActionType.SEND_SMS,
                    mapOf("number" to number, "body" to body),
                    reason = "User asked to send an SMS to $number"
                )
            }

        Regex("""send sms to ($numberPattern) saying (.+)""")
            .find(text)?.let { m ->
                return RavyAction(
                    ActionType.SEND_SMS,
                    mapOf("number" to m.groupValues[1].trim(), "body" to m.groupValues[2].trim()),
                    reason = "User asked to send an SMS"
                )
            }

        Regex("""text ($numberPattern) saying (.+)""")
            .find(text)?.let { m ->
                return RavyAction(
                    ActionType.SEND_SMS,
                    mapOf("number" to m.groupValues[1].trim(), "body" to m.groupValues[2].trim()),
                    reason = "User asked to text a number"
                )
            }

        return null
    }

    /**
     * "remind me at 6pm to sleep", "call me at 7am with my morning report",
     * "every weekday at 7am give me my school schedule". The trailing phrase
     * (whatever comes after the time) is stored verbatim as the reminder's
     * "label" — at trigger time, that exact phrase is re-run through the
     * real pipeline (core.handle), the same as if the user said it live.
     * This is a deliberately simple heuristic parser, not full date/time
     * NLU: it only recognizes a single "H(:MM) am/pm" time per utterance.
     */
    private fun parseReminder(text: String): RavyAction? {
        if (!(text.contains("remind me") || text.contains("call me") || text.startsWith("every"))) return null
        val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""").find(text) ?: return null

        val hour12 = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
        val isPm = timeMatch.groupValues[3] == "pm"
        var hour24 = hour12 % 12
        if (isPm) hour24 += 12

        val repeatStr = when {
            text.contains("every weekday") -> "WEEKDAYS"
            text.contains("every day") -> "DAILY"
            else -> "NONE"
        }

        val afterTime = text.substring(timeMatch.range.last + 1).trim()
        var label = afterTime.removePrefix("to ").removePrefix("and ").removePrefix("with ").removePrefix(",").trim()
        if (label.isBlank()) label = "This is your reminder, Sir."

        return RavyAction(
            ActionType.CREATE_REMINDER,
            mapOf("hour" to hour24.toString(), "minute" to minute.toString(), "repeat" to repeatStr, "label" to label),
            reason = "User asked to create a reminder/routine"
        )
    }
}
