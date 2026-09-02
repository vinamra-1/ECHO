package com.example.echo

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Handles UI inspection (reading screen content) and automation
 * (tap/type/gesture). Body is intentionally empty until Phase 5
 * of the roadmap — see PROJECT_CONTEXT.md.
 */
class EchoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: EchoAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO Phase 5: traverse rootInActiveWindow, extract text nodes
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
