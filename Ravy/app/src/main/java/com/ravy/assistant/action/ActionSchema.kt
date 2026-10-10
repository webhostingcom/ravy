package com.ravy.assistant.action

import java.util.UUID

/**
 * The structured contract between the "brain" (intent parsing) and the
 * Action Engine (real Android execution). The brain never performs actions
 * itself — it only ever emits one of these, and only ActionEngine touches
 * Android APIs.
 */
enum class ActionType {
    OPEN_APP,
    OPEN_APP_BY_NAME,
    TOGGLE_FLASHLIGHT,
    GET_BATTERY_INFO,
    GET_DEVICE_INFO,

    SEARCH_CONTACT,
    CALL_CONTACT,
    DIAL_NUMBER,

    GET_SMS_COUNT,
    SEND_SMS,

    CREATE_CALENDAR_EVENT,

    GET_LOCATION,

    LIST_RECENT_FILES,

    GET_NOTIFICATION_SUMMARY,
    READ_APP_NOTIFICATIONS,

    READ_SCREEN,

    GMAIL_SIGN_IN,
    GMAIL_INBOX_SUMMARY,
    GMAIL_READ_MESSAGE,

    GENERAL_QA,

    SET_SERIOUS_MODE,
    CREATE_REMINDER,

    // Navigation (real AccessibilityService global actions)
    GO_BACK,
    GO_HOME,
    GO_RECENTS,

    // Web / Maps
    SEARCH_WEB,
    OPEN_MAPS,

    // Timers / alarms (system Clock app intents)
    SET_TIMER,
    SET_ALARM,

    // Camera vision
    DESCRIBE_CAMERA_VIEW,

    // Call log / phone activity
    GET_CALL_LOG,
    PHONE_ACTIVITY_REPORT,

    // SMS extensions
    SEARCH_SMS,

    // Gmail extensions
    GMAIL_SEARCH,
    GMAIL_OPEN_MESSAGE,
    GMAIL_ARCHIVE,
    GMAIL_TRASH,
    GMAIL_MARK_READ,
    GMAIL_MARK_UNREAD,
    GMAIL_REPLY,

    // Files
    OPEN_FILE_BY_NAME,

    // Media
    MEDIA_CONTROL,

    UNKNOWN
}

enum class ActionStatus {
    SUCCESS,
    FAILED,
    NEEDS_PERMISSION,
    NEEDS_CONFIRMATION,
    NOT_SUPPORTED
}

data class RavyAction(
    val action: ActionType,
    val parameters: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val requestId: String = UUID.randomUUID().toString(),
    val reason: String = ""
)

data class RavyActionResult(
    val requestId: String,
    val status: ActionStatus,
    val action: ActionType,
    val message: String
)
