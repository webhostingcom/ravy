package com.ravy.assistant.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.ravy.assistant.accessibility.RavyAccessibilityStore
import com.ravy.assistant.gmail.GmailAuth
import com.ravy.assistant.gmail.GmailClient
import com.ravy.assistant.llm.RavyLLM
import com.ravy.assistant.notifications.RavyNotificationStore
import com.ravy.assistant.reminders.RavyReminder
import com.ravy.assistant.reminders.RavyReminderScheduler
import com.ravy.assistant.reminders.RavyReminderStore
import com.ravy.assistant.reminders.RepeatType
import com.ravy.assistant.settings.RavySettings
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.ImageReader
import android.media.session.MediaSessionManager
import android.provider.AlarmClock
import android.provider.CallLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.content.ComponentName
import com.ravy.assistant.notifications.RavyNotificationListener

/**
 * Executes RavyAction objects using REAL Android APIs only.
 * Every path returns a truthful RavyActionResult — nothing here
 * fabricates a SUCCESS that didn't actually happen.
 *
 * IMPORTANT: Several methods here (Gmail, ContentResolver queries) do
 * network or disk I/O. Call execute() from a background thread, never
 * the main/UI thread (MainActivity does this).
 */
class ActionEngine(private val context: Context) {

    private var flashlightOn = false

    /** Short rolling window for conversational continuity with the LLM. Not persisted. */
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    private fun hasPermission(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun execute(action: RavyAction): RavyActionResult {
        return when (action.action) {
            ActionType.OPEN_APP -> openApp(action)
            ActionType.OPEN_APP_BY_NAME -> openAppByName(action)
            ActionType.TOGGLE_FLASHLIGHT -> toggleFlashlight(action)
            ActionType.GET_BATTERY_INFO -> getBatteryInfo(action)
            ActionType.GET_DEVICE_INFO -> getDeviceInfo(action)
            ActionType.SEARCH_CONTACT -> searchContact(action)
            ActionType.CALL_CONTACT -> callContact(action)
            ActionType.DIAL_NUMBER -> dialNumber(action)
            ActionType.GET_SMS_COUNT -> getSmsCount(action)
            ActionType.SEND_SMS -> sendSms(action)
            ActionType.CREATE_CALENDAR_EVENT -> createCalendarEvent(action)
            ActionType.GET_LOCATION -> getLocation(action)
            ActionType.LIST_RECENT_FILES -> listRecentFiles(action)
            ActionType.GET_NOTIFICATION_SUMMARY -> getNotificationSummary(action)
            ActionType.READ_APP_NOTIFICATIONS -> readAppNotifications(action)
            ActionType.READ_SCREEN -> readScreen(action)
            ActionType.GMAIL_SIGN_IN -> RavyActionResult(
                action.requestId, ActionStatus.NOT_SUPPORTED, action.action,
                "Gmail sign-in has to be started from the app UI, Sir — MainActivity handles that directly."
            )
            ActionType.GMAIL_INBOX_SUMMARY -> gmailInboxSummary(action)
            ActionType.GMAIL_READ_MESSAGE -> gmailReadMessage(action)
            ActionType.GENERAL_QA -> generalQa(action)
            ActionType.SET_SERIOUS_MODE -> setSeriousMode(action)
            ActionType.CREATE_REMINDER -> createReminder(action)
            ActionType.GO_BACK -> globalAction(action, AccessibilityService.GLOBAL_ACTION_BACK, "Went back.")
            ActionType.GO_HOME -> globalAction(action, AccessibilityService.GLOBAL_ACTION_HOME, "Went home.")
            ActionType.GO_RECENTS -> globalAction(action, AccessibilityService.GLOBAL_ACTION_RECENTS, "Opened recent apps.")
            ActionType.SEARCH_WEB -> searchWeb(action)
            ActionType.OPEN_MAPS -> openMaps(action)
            ActionType.SET_TIMER -> setTimer(action)
            ActionType.SET_ALARM -> setAlarm(action)
            ActionType.DESCRIBE_CAMERA_VIEW -> describeCameraView(action)
            ActionType.GET_CALL_LOG -> getCallLog(action)
            ActionType.PHONE_ACTIVITY_REPORT -> phoneActivityReport(action)
            ActionType.SEARCH_SMS -> searchSms(action)
            ActionType.GMAIL_SEARCH -> gmailSearch(action)
            ActionType.GMAIL_OPEN_MESSAGE -> gmailOpenMessage(action)
            ActionType.GMAIL_ARCHIVE -> gmailArchive(action)
            ActionType.GMAIL_TRASH -> gmailTrash(action)
            ActionType.GMAIL_MARK_READ -> gmailMarkRead(action)
            ActionType.GMAIL_MARK_UNREAD -> gmailMarkUnread(action)
            ActionType.GMAIL_REPLY -> gmailReply(action)
            ActionType.OPEN_FILE_BY_NAME -> openFileByName(action)
            ActionType.MEDIA_CONTROL -> mediaControl(action)
            ActionType.UNKNOWN -> RavyActionResult(
                action.requestId, ActionStatus.NOT_SUPPORTED, action.action,
                "I didn't understand a specific action to take, Sir."
            )
        }
    }

    // ---------- existing four actions (unchanged) ----------

    private fun openApp(action: RavyAction): RavyActionResult {
        val pkg = action.parameters["package"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No package name was provided.")
        val pm = context.packageManager
        val launchIntent = try { pm.getLaunchIntentForPackage(pkg) } catch (e: Exception) { null }
        if (launchIntent == null) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "That app ($pkg) isn't installed, or has no launchable activity.")
        }
        return try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Opened $pkg.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Failed to open $pkg: ${e.message}")
        }
    }

    /**
     * Resolves a spoken app name against every app actually installed and
     * launchable on this device (queried live via PackageManager — not a
     * fixed list), then opens the best match. Speech recognition is imperfect
     * ("what's app" for "WhatsApp"), so matching tolerates small differences
     * rather than requiring an exact string.
     */
    private fun openAppByName(action: RavyAction): RavyActionResult {
        val spoken = action.parameters["name"]?.trim()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No app name was given.")

        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try {
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not list installed apps: ${e.message}")
        }

        if (resolveInfos.isEmpty()) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No launchable apps were found on this device.")
        }

        val normalizedSpoken = normalize(spoken)
        var bestPackage: String? = null
        var bestLabel: String? = null
        var bestScore = Int.MAX_VALUE

        for (info in resolveInfos) {
            val label = info.loadLabel(pm)?.toString() ?: continue
            val normalizedLabel = normalize(label)
            val score = when {
                normalizedLabel == normalizedSpoken -> 0
                normalizedLabel.contains(normalizedSpoken) || normalizedSpoken.contains(normalizedLabel) -> 1
                else -> levenshtein(normalizedLabel, normalizedSpoken)
            }
            if (score < bestScore) {
                bestScore = score
                bestPackage = info.activityInfo.packageName
                bestLabel = label
            }
        }

        // Loose threshold: allow small STT mishears, but don't open something
        // wildly unrelated to what was actually said.
        val maxAllowedDistance = (normalizedSpoken.length / 2).coerceAtLeast(2)
        if (bestPackage == null || bestScore > maxAllowedDistance + 1) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I couldn't find an installed app matching \"$spoken\", Sir.")
        }

        return try {
            val intent = pm.getLaunchIntentForPackage(bestPackage)
                ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "\"$bestLabel\" has no launchable activity.")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Opened $bestLabel.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Failed to open $bestLabel: ${e.message}")
        }
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Standard edit-distance, used only to tolerate minor speech-recognition mishears. */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }


    private fun toggleFlashlight(action: RavyAction): RavyActionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return RavyActionResult(action.requestId, ActionStatus.NOT_SUPPORTED, action.action, "No camera service on this device.")
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "Camera permission is required to control the flashlight.")
        }
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return RavyActionResult(action.requestId, ActionStatus.NOT_SUPPORTED, action.action, "No flash-capable camera found.")
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, if (flashlightOn) "Flashlight turned on." else "Flashlight turned off.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not toggle flashlight: ${e.message}")
        }
    }

    private fun getBatteryInfo(action: RavyAction): RavyActionResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Battery service unavailable.")
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Battery is at $level percent${if (charging) ", and charging" else ""}.")
    }

    private fun getDeviceInfo(action: RavyAction): RavyActionResult {
        val info = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
        return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, info)
    }

    // ---------- Contacts ----------

    private fun searchContact(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Contacts permission to search for someone.")
        }
        val name = action.parameters["name"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No name was given to search for.")

        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        val matches = mutableListOf<Pair<String, String>>()
        cursor?.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                matches.add(n to num)
            }
        }
        return when {
            matches.isEmpty() -> RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No contact matching \"$name\" was found.")
            matches.size == 1 -> RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Found ${matches[0].first}: ${matches[0].second}.")
            else -> RavyActionResult(action.requestId, ActionStatus.NEEDS_CONFIRMATION, action.action, "I found ${matches.size} contacts matching \"$name\": ${matches.joinToString { it.first }}. Which one, Sir?")
        }
    }

    private fun callContact(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.CALL_PHONE)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Phone permission to place a call.")
        }
        val number = action.parameters["number"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No phone number was resolved to call.")
        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(callIntent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Calling $number now.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not place the call: ${e.message}")
        }
    }

    // ---------- SMS ----------

    private fun getSmsCount(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.READ_SMS)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need SMS permission to check your messages.")
        }
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.READ),
            "${Telephony.Sms.READ} = 0",
            null,
            null
        )
        val senders = mutableSetOf<String>()
        var count = 0
        cursor?.use {
            while (it.moveToNext()) {
                count++
                it.getString(0)?.let { addr -> senders.add(addr) }
            }
        }
        return RavyActionResult(
            action.requestId, ActionStatus.SUCCESS, action.action,
            if (count == 0) "You have no unread text messages, Sir."
            else "You have $count unread text message${if (count > 1) "s" else ""}, from ${senders.joinToString()}."
        )
    }

    private fun sendSms(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.SEND_SMS)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need SMS permission to send a text.")
        }
        val number = action.parameters["number"]
        val body = action.parameters["body"]
        if (number == null || body == null) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Missing recipient number or message body.")
        }
        if (!action.requiresConfirmation) {
            // Defensive: sending SMS must always be confirmed by the caller (MainActivity)
            // before execute() is even invoked with requiresConfirmation cleared.
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_CONFIRMATION, action.action, "Should I really send this text to $number, Sir? Say 'yes, send it' to confirm.")
        }
        return try {
            SmsManager.getDefault().sendTextMessage(number, null, body, null, null)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Text sent to $number.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not send the text: ${e.message}")
        }
    }

    // ---------- Calendar ----------

    private fun createCalendarEvent(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.WRITE_CALENDAR)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Calendar permission to add an event.")
        }
        val title = action.parameters["title"] ?: "Ravy event"
        val startMillis = action.parameters["startMillis"]?.toLongOrNull()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No start time was given for the event.")
        val endMillis = action.parameters["endMillis"]?.toLongOrNull() ?: (startMillis + 60 * 60 * 1000)

        val calCursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )
        val calendarId = calCursor?.use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No calendar is set up on this device to add events to.")

        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Added \"$title\" to your calendar.")
            else RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "The calendar didn't accept the new event.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not create the event: ${e.message}")
        }
    }

    // ---------- Location ----------

    private fun getLocation(action: RavyAction): RavyActionResult {
        val fine = hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Location permission for that.")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Location service unavailable.")
        return try {
            val providers = lm.getProviders(true)
            val location = providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
            if (location == null) {
                RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No recent location fix is available yet, Sir.")
            } else {
                RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, describeLocation(location.latitude, location.longitude))
            }
        } catch (e: SecurityException) {
            RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "Location permission was denied at the system level.")
        }
    }

    /**
     * Real reverse geocoding via Android's Geocoder — turns raw coordinates
     * into a human-readable address ("You're on X Street, Y area, Z city")
     * instead of reporting latitude/longitude. Geocoder depends on a backend
     * service that isn't guaranteed present on every device/ROM; if it's
     * unavailable or returns nothing, this honestly falls back to reporting
     * the coordinates rather than inventing a street name.
     */
    private fun describeLocation(lat: Double, lon: Double): String {
        return try {
            val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lon, 1)
            val address = results?.firstOrNull()
            if (address == null) {
                "You're near latitude $lat, longitude $lon, Sir — I couldn't resolve that to an address on this device."
            } else {
                val parts = mutableListOf<String>()
                address.thoroughfare?.let { parts.add(it) }                       // street
                address.featureName?.takeIf { it != address.thoroughfare }?.let { parts.add(it) } // building/landmark
                address.subLocality?.let { parts.add(it) }                        // area/neighborhood
                address.locality?.let { parts.add(it) }                           // city
                if (parts.isEmpty()) {
                    address.getAddressLine(0) ?: "You're near latitude $lat, longitude $lon, Sir."
                } else {
                    "You're on ${parts.joinToString(", ")}, Sir."
                }
            }
        } catch (e: Exception) {
            "You're near latitude $lat, longitude $lon, Sir — reverse geocoding isn't available right now (${e.message})."
        }
    }

    // ---------- Files ----------

    private fun listRecentFiles(action: RavyAction): RavyActionResult {
        val hasImages = hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        val hasLegacy = hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!hasImages && !hasLegacy) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need file/media permission to list your files.")
        }
        val names = mutableListOf<String>()
        val cursor = context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME),
            null, null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )
        cursor?.use {
            var i = 0
            while (it.moveToNext() && i < 10) {
                it.getString(0)?.let { name -> names.add(name) }
                i++
            }
        }
        return if (names.isEmpty()) {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "I couldn't find any recent files, Sir.")
        } else {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Your most recent files: ${names.joinToString()}.")
        }
    }

    // ---------- Notifications ----------

    private fun getNotificationSummary(action: RavyAction): RavyActionResult {
        if (!RavyNotificationStore.isConnected) {
            return RavyActionResult(
                action.requestId, ActionStatus.NEEDS_PERMISSION, action.action,
                "Notification access isn't turned on yet, Sir. Please enable Ravy under notification access in Settings."
            )
        }
        val recent = RavyNotificationStore.snapshot()
        if (recent.isEmpty()) {
            return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "No recent notifications, Sir.")
        }
        val pm = context.packageManager
        fun labelFor(pkg: String): String =
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val byApp = recent.groupingBy { labelFor(it.appPackage) }.eachCount()
        val summary = byApp.entries.joinToString { "${it.value} from ${it.key}" }
        return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "You have ${recent.size} recent notifications: $summary.")
    }

    // ---------- Accessibility screen reading ----------

    private fun readScreen(action: RavyAction): RavyActionResult {
        val service = RavyAccessibilityStore.serviceRef
            ?: return RavyActionResult(
                action.requestId, ActionStatus.NEEDS_PERMISSION, action.action,
                "Screen reading isn't turned on yet, Sir. Please enable Ravy's accessibility service in Settings."
            )
        val text = service.captureScreenText()
        return if (text.isBlank()) {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "The screen doesn't seem to have any readable text right now.")
        } else {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, text)
        }
    }

    // ---------- Gmail ----------

    private fun gmailInboxSummary(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir. Say 'sign into Gmail' first.")
        return try {
            val items = GmailClient.fetchInboxSummary(context, account, maxResults = 10)
            com.ravy.assistant.gmail.GmailSessionContext.setResults(items)
            if (items.isEmpty()) {
                RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Your inbox has no recent messages, Sir.")
            } else {
                val spoken = items.joinToString(separator = ". ") { "From ${it.from}, subject: ${it.subject}" }
                RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "You have ${items.size} recent emails. $spoken")
            }
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "Gmail needs you to re-approve access, Sir — please sign in again.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not reach Gmail: ${e.message}. Have you completed the OAuth setup in the README?")
        }
    }

    private fun gmailReadMessage(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val messageId = action.parameters["messageId"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No specific message was selected to read.")
        return try {
            val text = GmailClient.fetchFullMessageText(context, account, messageId)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, text.ifBlank { "That message appears to be empty." })
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "Gmail needs you to re-approve access, Sir — please sign in again.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not read that email: ${e.message}")
        }
    }

    // ---------- SIM-aware dialing ----------

    /**
     * Dials a raw number, optionally on a specific SIM slot. SIM selection uses
     * TelecomManager's call-capable phone accounts — a real Android API, but
     * one where behavior can vary slightly by phone manufacturer. If a SIM
     * isn't specified, or only one SIM is present, it just dials normally.
     */
    private fun dialNumber(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.CALL_PHONE)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Phone permission to place a call.")
        }
        val number = action.parameters["number"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No phone number was given.")
        val simSlot = action.parameters["sim"]?.toIntOrNull() // 1 or 2, if the user specified one

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            var phoneAccountHandle: PhoneAccountHandle? = null

            if (simSlot != null && telecomManager != null &&
                hasPermission(android.Manifest.permission.READ_PHONE_STATE)
            ) {
                val accounts = telecomManager.callCapablePhoneAccounts
                // Ordering of callCapablePhoneAccounts generally follows SIM slot order,
                // but this isn't formally guaranteed by every manufacturer.
                phoneAccountHandle = accounts.getOrNull(simSlot - 1)
            }

            val callUri = Uri.parse("tel:$number")
            if (phoneAccountHandle != null && telecomManager != null) {
                val extras = android.os.Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                }
                telecomManager.placeCall(callUri, extras)
                RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Calling $number on SIM $simSlot, Sir.")
            } else {
                val callIntent = Intent(Intent.ACTION_CALL, callUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(callIntent)
                RavyActionResult(
                    action.requestId, ActionStatus.SUCCESS, action.action,
                    if (simSlot != null) "Calling $number, Sir — I couldn't confirm which SIM, so it's using this device's default." else "Calling $number, Sir."
                )
            }
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not place the call: ${e.message}")
        }
    }

    // ---------- Per-app notification content ----------

    /**
     * "Read WhatsApp notifications" -> real content from RavyNotificationStore
     * for that app, not just a count. Matches the app name the same fuzzy way
     * openAppByName does, since STT may mishear "WhatsApp".
     */
    private fun readAppNotifications(action: RavyAction): RavyActionResult {
        if (!RavyNotificationStore.isConnected) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "Notification access isn't turned on yet, Sir.")
        }
        val spokenApp = action.parameters["appName"]?.trim()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Which app's notifications, Sir?")

        val pm = context.packageManager
        val normalizedSpoken = normalize(spokenApp)
        val all = RavyNotificationStore.snapshot()
        val matching = all.filter { n ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(n.appPackage, 0)).toString() } catch (e: Exception) { n.appPackage }
            val normalizedLabel = normalize(label)
            normalizedLabel.contains(normalizedSpoken) || normalizedSpoken.contains(normalizedLabel) ||
                levenshtein(normalizedLabel, normalizedSpoken) <= 2
        }

        if (matching.isEmpty()) {
            return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "No recent notifications from $spokenApp, Sir.")
        }

        val spoken = matching.take(5).joinToString(separator = " ") { n ->
            val who = n.title.ifBlank { "Someone" }
            "$who texted and said: ${n.text.ifBlank { "no message text" }}."
        }
        return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, spoken)
    }

    // ---------- General conversation (real LLM, needs the user's own API key) ----------

    /**
     * Falls back here only when RavyBrain's keyword matching didn't recognize
     * the phrasing. The LLM (Gemini by default — see llm/RavyLLM.kt) either:
     *  (a) replies conversationally, which we just speak back, or
     *  (b) proposes a structured device action, which we ALWAYS re-execute
     *      through this same ActionEngine before saying anything happened —
     *      the LLM's opinion that "the user wants X" is never itself treated
     *      as X having occurred. Only Android's own real result is spoken.
     */
    // ---------- Serious Mode (real persisted UI-state toggle, not cosmetic-only) ----------

    private fun setSeriousMode(action: RavyAction): RavyActionResult {
        val on = action.parameters["on"] == "true"
        RavySettings.setSeriousMode(context, on)
        return RavyActionResult(
            action.requestId, ActionStatus.SUCCESS, action.action,
            if (on) "Serious mode engaged, Sir." else "Back to normal, Sir."
        )
    }

    // ---------- Reminders & Routines (real AlarmManager scheduling) ----------

    private fun createReminder(action: RavyAction): RavyActionResult {
        val hour = action.parameters["hour"]?.toIntOrNull()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I didn't catch a time for that reminder.")
        val minute = action.parameters["minute"]?.toIntOrNull() ?: 0
        val repeat = try {
            RepeatType.valueOf(action.parameters["repeat"] ?: "NONE")
        } catch (e: Exception) {
            RepeatType.NONE
        }
        val label = action.parameters["label"] ?: "This is your reminder, Sir."

        if (!RavyReminderScheduler.canScheduleExactAlarms(context)) {
            return RavyActionResult(
                action.requestId, ActionStatus.NEEDS_PERMISSION, action.action,
                "I need the \"Alarms & reminders\" permission to schedule that, Sir — please grant it in Settings."
            )
        }

        val nextTrigger = RavyReminderScheduler.computeNextTrigger(hour, minute, repeat)
        val reminder = RavyReminder(label = label, hour = hour, minute = minute, repeat = repeat, nextTriggerMillis = nextTrigger)

        val scheduled = RavyReminderScheduler.schedule(context, reminder)
        if (!scheduled) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "The system refused to schedule that alarm, Sir.")
        }
        RavyReminderStore.add(context, reminder)

        val timeDesc = String.format("%02d:%02d", hour, minute)
        val repeatDesc = when (repeat) {
            RepeatType.DAILY -> " every day"
            RepeatType.WEEKDAYS -> " every weekday"
            RepeatType.NONE -> ""
        }
        return RavyActionResult(
            action.requestId, ActionStatus.SUCCESS, action.action,
            "Done, Sir. I'll alert you at $timeDesc$repeatDesc: \"$label\"."
        )
    }

    // ---------- Navigation (real AccessibilityService global actions) ----------

    private fun globalAction(action: RavyAction, globalActionCode: Int, successMessage: String): RavyActionResult {
        val service = RavyAccessibilityStore.serviceRef
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need the accessibility service turned on to navigate the screen, Sir.")
        val performed = service.performGlobalAction(globalActionCode)
        return if (performed) {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, successMessage)
        } else {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Android didn't accept that navigation action, Sir.")
        }
    }

    // ---------- Web search / Maps ----------

    private fun searchWeb(action: RavyAction): RavyActionResult {
        val query = action.parameters["query"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No search query was given.")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Searching the web for \"$query\".")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not open a browser to search: ${e.message}")
        }
    }

    private fun openMaps(action: RavyAction): RavyActionResult {
        val query = action.parameters["query"]
        return try {
            val uri = if (query.isNullOrBlank()) Uri.parse("geo:0,0") else Uri.parse("geo:0,0?q=" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, if (query.isNullOrBlank()) "Opening Maps." else "Opening Maps for \"$query\".")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No maps app could handle that, Sir: ${e.message}")
        }
    }

    // ---------- Timers / Alarms (real system Clock app, no custom scheduling needed) ----------

    private fun setTimer(action: RavyAction): RavyActionResult {
        val seconds = action.parameters["seconds"]?.toIntOrNull()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I didn't catch a duration for that timer.")
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                action.parameters["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Timer set for ${seconds / 60} minute(s), Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No clock app could set that timer, Sir.")
        }
    }

    private fun setAlarm(action: RavyAction): RavyActionResult {
        val hour = action.parameters["hour"]?.toIntOrNull()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I didn't catch a time for that alarm.")
        val minute = action.parameters["minute"]?.toIntOrNull() ?: 0
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                action.parameters["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, String.format("Alarm set for %02d:%02d, Sir.", hour, minute))
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No clock app could set that alarm, Sir.")
        }
    }

    // ---------- Camera vision ("Ravy's eyes") ----------

    /**
     * Captures exactly one still frame — no preview, no recording, no
     * running camera between requests — only when this specific action is
     * invoked by the user. Sends the frame to Gemini's vision input and
     * speaks back its real description.
     */
    private fun describeCameraView(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Camera permission to see anything, Sir.")
        }
        if (!RavySettings.isConfigured(context)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need an AI provider configured to describe what the camera sees, Sir.")
        }
        return try {
            val jpeg = captureStillFrame()
                ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I couldn't capture anything from the camera just now, Sir.")
            val description = RavyLLM.describeImage(context, jpeg)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, description)
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Camera vision failed, Sir: ${e.message}")
        }
    }

    @Suppress("MissingPermission")
    private fun captureStillFrame(): ByteArray? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull() ?: return null

        val latch = CountDownLatch(1)
        var jpegData: ByteArray? = null
        var deviceRef: CameraDevice? = null
        val thread = android.os.HandlerThread("RavyCameraThread").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val imageReader = ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.JPEG, 1)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                jpegData = bytes
                image.close()
            }
            deviceRef?.close()
            latch.countDown()
        }, handler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    deviceRef = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureBuilder.addTarget(imageReader.surface)
                        camera.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.capture(captureBuilder.build(), null, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { latch.countDown() }
                        }, handler)
                    } catch (e: Exception) {
                        latch.countDown()
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); latch.countDown() }
            }, handler)
        } catch (e: SecurityException) {
            latch.countDown()
        }

        latch.await(8, TimeUnit.SECONDS)
        thread.quitSafely()
        imageReader.close()
        return jpegData
    }

    // ---------- Call log / Phone activity report ----------

    private fun getCallLog(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need Call Log permission to check recent calls, Sir.")
        }
        val entries = queryCallLog(10)
        if (entries.isEmpty()) {
            return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "No recent calls, Sir.")
        }
        val summary = entries.joinToString(". ") { "${it.first} (${it.second})" }
        return RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Recent calls: $summary.")
    }

    /** Returns up to [limit] recent calls as (description, type) pairs. */
    private fun queryCallLog(limit: Int): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val name = it.getString(0)
                val number = it.getString(1) ?: "unknown number"
                val type = when (it.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    else -> "call"
                }
                results.add((name ?: number) to type)
                count++
            }
        }
        return results
    }

    private fun phoneActivityReport(action: RavyAction): RavyActionResult {
        val hours = action.parameters["hours"]?.toIntOrNull() ?: 6
        val cutoff = System.currentTimeMillis() - hours * 60 * 60 * 1000L
        val sections = mutableListOf<String>()

        if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
            val calls = queryCallLogSince(cutoff)
            sections.add(if (calls.isEmpty()) "No calls in that time." else "Calls: $calls.")
        } else {
            sections.add("Calls: I don't have Call Log permission, so I can't include this.")
        }

        if (hasPermission(android.Manifest.permission.READ_SMS)) {
            val smsCount = countSmsSince(cutoff)
            sections.add(if (smsCount == 0) "No new texts in that time." else "$smsCount new text message(s).")
        } else {
            sections.add("Texts: I don't have SMS permission, so I can't include this.")
        }

        if (RavyNotificationStore.isConnected) {
            val recentNotifs = RavyNotificationStore.snapshot().filter { it.postedAt >= cutoff }
            sections.add(if (recentNotifs.isEmpty()) "No notable notifications in that time." else "${recentNotifs.size} notification(s) came in.")
        } else {
            sections.add("Notifications: access isn't enabled, so I can't include this.")
        }

        val gmailAccount = GmailAuth.lastSignedInAccount(context)
        if (gmailAccount != null) {
            try {
                val items = GmailClient.fetchInboxSummary(context, gmailAccount, maxResults = 5)
                sections.add("Most recent email activity: ${items.size} message(s) at the top of your inbox (not strictly filtered to this time window).")
            } catch (e: Exception) {
                sections.add("Email: couldn't reach Gmail just now (${e.message}).")
            }
        } else {
            sections.add("Email: not signed into Gmail, so I can't include this.")
        }

        sections.add("I don't have access to calendar activity or other apps' internal activity in this report.")

        return RavyActionResult(
            action.requestId, ActionStatus.SUCCESS, action.action,
            "Here's what I can tell you from the last $hours hour(s), Sir. " + sections.joinToString(" ")
        )
    }

    private fun queryCallLogSince(cutoffMillis: Long): Int {
        var count = 0
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} >= ?", arrayOf(cutoffMillis.toString()), null
        )
        cursor?.use { while (it.moveToNext()) count++ }
        return count
    }

    private fun countSmsSince(cutoffMillis: Long): Int {
        var count = 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(cutoffMillis.toString()), null
        )
        cursor?.use { while (it.moveToNext()) count++ }
        return count
    }

    // ---------- SMS search ----------

    private fun searchSms(action: RavyAction): RavyActionResult {
        if (!hasPermission(android.Manifest.permission.READ_SMS)) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need SMS permission to search your messages.")
        }
        val query = action.parameters["query"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No search text was given.")
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
            "${Telephony.Sms.BODY} LIKE ?", arrayOf("%$query%"),
            "${Telephony.Sms.DATE} DESC"
        )
        val matches = mutableListOf<String>()
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < 5) {
                val addr = it.getString(0) ?: "unknown"
                val body = it.getString(1) ?: ""
                matches.add("from $addr: \"${body.take(60)}\"")
                count++
            }
        }
        return if (matches.isEmpty()) {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "No texts matching \"$query\", Sir.")
        } else {
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Found ${matches.size} matching text(s): ${matches.joinToString("; ")}.")
        }
    }

    // ---------- Gmail search ----------

    private fun gmailSearch(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val query = action.parameters["query"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No search text was given.")
        return try {
            val items = GmailClient.searchMessages(context, account, query, maxResults = 5)
            com.ravy.assistant.gmail.GmailSessionContext.setResults(items)
            if (items.isEmpty()) {
                RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "No emails matching \"$query\", Sir.")
            } else {
                val spoken = items.joinToString(". ") { "From ${it.from}, subject: ${it.subject}" }
                RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Found ${items.size} email(s) matching \"$query\": $spoken")
            }
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Gmail search failed: ${e.message}")
        }
    }

    // ---------- Gmail: interactive context (open/archive/delete/read/reply) ----------

    /**
     * Resolves which message "this"/"that"/"the second one" refers to:
     * an explicit ordinal in the parameters takes priority, otherwise the
     * session's current-focus message (set by the last search/summary or
     * the last explicit open). Returns null — and callers report a real
     * "I don't know which email you mean" — rather than ever guessing.
     */
    private fun resolveGmailTarget(action: RavyAction): com.ravy.assistant.gmail.GmailSummaryItem? {
        val ctx = com.ravy.assistant.gmail.GmailSessionContext
        action.parameters["reference"]?.let { ref -> ctx.resolveOrdinal(ref)?.let { return it } }
        return ctx.currentMessage
    }

    private fun gmailOpenMessage(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val ctx = com.ravy.assistant.gmail.GmailSessionContext
        val target = action.parameters["reference"]?.let { ctx.resolveOrdinal(it) }
            ?: ctx.lastResults.firstOrNull()
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I don't have a list of emails to pick from yet, Sir — search or check your inbox first.")
        ctx.focus(target)
        return try {
            val fullText = GmailClient.fetchFullMessageText(context, account, target.id)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "From ${target.from}, subject ${target.subject}. $fullText")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not open that email: ${e.message}")
        }
    }

    private fun gmailArchive(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val target = resolveGmailTarget(action)
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I don't know which email you mean, Sir — open or search for one first.")
        return try {
            GmailClient.archiveMessage(context, account, target.id)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Archived the email from ${target.from}, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not archive that email: ${e.message}")
        }
    }

    private fun gmailTrash(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val target = resolveGmailTarget(action)
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I don't know which email you mean, Sir.")
        if (!action.requiresConfirmation) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_CONFIRMATION, action.action, "Delete the email from ${target.from}, subject \"${target.subject}\"? Say 'yes, delete it' to confirm.")
        }
        return try {
            GmailClient.trashMessage(context, account, target.id)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Deleted the email from ${target.from}, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not delete that email: ${e.message}")
        }
    }

    private fun gmailMarkRead(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val target = resolveGmailTarget(action)
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I don't know which email you mean, Sir.")
        return try {
            GmailClient.markRead(context, account, target.id)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Marked as read, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not mark that as read: ${e.message}")
        }
    }

    private fun gmailMarkUnread(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val target = resolveGmailTarget(action)
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I don't know which email you mean, Sir.")
        return try {
            GmailClient.markUnread(context, account, target.id)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Marked as unread, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not mark that as unread: ${e.message}")
        }
    }

    private fun gmailReply(action: RavyAction): RavyActionResult {
        val account = GmailAuth.lastSignedInAccount(context)
            ?: return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "You're not signed into Gmail yet, Sir.")
        val target = resolveGmailTarget(action)
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "I don't know which email you're replying to, Sir.")
        val body = action.parameters["body"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "What should the reply say, Sir?")
        if (!action.requiresConfirmation) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_CONFIRMATION, action.action, "Reply to ${target.from} saying \"$body\"? Say 'yes, send it' to confirm.")
        }
        return try {
            val sentTo = GmailClient.sendReply(context, account, target.id, body)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Reply sent to $sentTo, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not send that reply: ${e.message}")
        }
    }

    // ---------- Files: open by name ----------

    private fun openFileByName(action: RavyAction): RavyActionResult {
        val hasImages = hasPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        val hasLegacy = hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        if (!hasImages && !hasLegacy) {
            return RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need file/media permission to open files.")
        }
        val name = action.parameters["name"]
            ?: return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No filename was given.")

        val cursor = context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )
        var fileUri: Uri? = null
        var mimeType: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                mimeType = it.getString(2)
                fileUri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
            }
        }
        if (fileUri == null) {
            return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No file matching \"$name\" was found, Sir.")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType ?: "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Opening \"$name\", Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Found the file but no app could open it, Sir: ${e.message}")
        }
    }

    // ---------- Media controls (via the existing Notification Listener's media session access) ----------

    private fun mediaControl(action: RavyAction): RavyActionResult {
        val command = action.parameters["command"] ?: "play"
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listenerComponent = ComponentName(context, RavyNotificationListener::class.java)
            val sessions = msm.getActiveSessions(listenerComponent)
            if (sessions.isEmpty()) {
                return RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "No app seems to be playing media right now, Sir.")
            }
            val controller = sessions[0]
            when (command) {
                "play" -> controller.transportControls.play()
                "pause" -> controller.transportControls.pause()
                "next" -> controller.transportControls.skipToNext()
                "previous" -> controller.transportControls.skipToPrevious()
                else -> return RavyActionResult(action.requestId, ActionStatus.NOT_SUPPORTED, action.action, "I don't recognize that media command.")
            }
            RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, "Done, Sir.")
        } catch (e: SecurityException) {
            RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, "I need notification access enabled to control media playback, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Media control failed: ${e.message}")
        }
    }

    private fun generalQa(action: RavyAction): RavyActionResult {
        val query = action.parameters["query"] ?: return RavyActionResult(
            action.requestId, ActionStatus.FAILED, action.action, "I didn't catch a question to answer."
        )
        return try {
            when (val outcome = RavyLLM.ask(context, query, conversationHistory)) {
                is RavyLLM.LlmOutcome.PlainReply -> {
                    conversationHistory.add(query to outcome.text)
                    while (conversationHistory.size > 6) conversationHistory.removeAt(0)
                    RavyActionResult(action.requestId, ActionStatus.SUCCESS, action.action, outcome.text)
                }
                is RavyLLM.LlmOutcome.ActionRequest -> {
                    val resolvedType = try {
                        ActionType.valueOf(outcome.actionName)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                    if (resolvedType == null || resolvedType == ActionType.GENERAL_QA || resolvedType == ActionType.SEND_SMS ||
                        resolvedType == ActionType.GMAIL_TRASH || resolvedType == ActionType.GMAIL_REPLY) {
                        // Refuse to blindly recurse into GENERAL_QA, and refuse to let
                        // the LLM trigger a confirmation-gated action (SEND_SMS) on its
                        // own — sending a text still requires the normal "say yes" flow.
                        val fallback = RavyActionResult(action.requestId, ActionStatus.NOT_SUPPORTED, action.action, "I think you want an action I can't safely trigger that way, Sir — could you ask directly?")
                        fallback
                    } else {
                        // Re-execute for real — this is the only place the outcome
                        // becomes something the user is told actually happened.
                        val realResult = execute(RavyAction(resolvedType, outcome.parameters, reason = "Routed via AI fallback: $query"))
                        conversationHistory.add(query to realResult.message)
                        while (conversationHistory.size > 6) conversationHistory.removeAt(0)
                        realResult
                    }
                }
            }
        } catch (e: RavyLLM.LlmException) {
            RavyActionResult(action.requestId, ActionStatus.NEEDS_PERMISSION, action.action, e.message ?: "AI setup is required for that, Sir.")
        } catch (e: Exception) {
            RavyActionResult(action.requestId, ActionStatus.FAILED, action.action, "Could not reach the AI provider: ${e.message}")
        }
    }
}
