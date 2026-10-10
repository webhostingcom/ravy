package com.ravy.assistant

import android.app.Application
import com.ravy.assistant.core.RavyCore

class RavyApplication : Application() {
    /** Shared across MainActivity, the always-on listener, and the overlay conversation. */
    val ravyCore: RavyCore by lazy { RavyCore(this) }
}
