package com.rama.riya

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Presses WhatsApp's Send button, only for ~20 seconds after Riya opens a prefilled chat. */
class RiyaAccessibility : AccessibilityService() {

    companion object {
        @Volatile var armedUntil = 0L
        fun arm() { armedUntil = System.currentTimeMillis() + 20000 }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val root = rootInActiveWindow ?: return
        val ids = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty() && click(nodes[0])) { armedUntil = 0; return }
        }
        val byText = root.findAccessibilityNodeInfosByText("Send")
        if (byText.isNotEmpty() && click(byText[0])) armedUntil = 0
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
        }
        return false
    }

    override fun onInterrupt() {}
}
