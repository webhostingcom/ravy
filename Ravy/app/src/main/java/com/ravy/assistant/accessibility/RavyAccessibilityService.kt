package com.ravy.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the CURRENT screen's visible text when asked ("Ravy, read the screen").
 * This is the legitimate, disclosed purpose of this service for a blind or
 * low-vision user: turning on-screen content into speech on request.
 *
 * It does not tap, swipe, type, or act on the user's behalf here — this
 * scaffold only captures text. Any future action-performing capability
 * (e.g. "tap the Send button") should be added deliberately, one action
 * at a time, in ActionEngine, with its own confirmation rules — not folded
 * silently into this class.
 *
 * IMPORTANT: The user must enable this manually — go to
 * Settings > Accessibility > Installed apps > Ravy > turn it on.
 * Ravy cannot enable it for itself; MainActivity's "Enable screen reading"
 * button just opens that settings screen for you.
 */
class RavyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        RavyAccessibilityStore.serviceRef = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally does nothing automatically on every event — Ravy
        // only reads the screen when the user explicitly asks it to,
        // not continuously, to avoid narrating everything the user does.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        RavyAccessibilityStore.serviceRef = null
        super.onDestroy()
    }

    /** Walks the current window's node tree and collects visible text. */
    fun captureScreenText(): String {
        val root: AccessibilityNodeInfo = rootInActiveWindow
            ?: return ""
        val collected = StringBuilder()
        collectText(root, collected)
        return collected.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        node.text?.let {
            if (it.isNotBlank()) out.append(it).append(". ")
        }
        node.contentDescription?.let {
            if (it.isNotBlank()) out.append(it).append(". ")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }
}

/** Lets ActionEngine reach the live service instance, if it's enabled and running. */
object RavyAccessibilityStore {
    var serviceRef: RavyAccessibilityService? = null
    val isEnabled: Boolean get() = serviceRef != null
}
