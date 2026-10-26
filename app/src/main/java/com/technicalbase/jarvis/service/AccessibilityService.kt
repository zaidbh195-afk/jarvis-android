package com.technicalbase.jarvis.service

import android.accessibilityservice.AccessibilityService as BaseAS
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityService : BaseAS() {

    companion object {
        @Volatile
        var instance: AccessibilityService? = null
            private set
    }

    @Volatile var lastForegroundPackage: String = ""
        private set
    @Volatile var lastScreenText: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            e.packageName?.let { lastForegroundPackage = it.toString() }
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val root = rootInActiveWindow ?: return
            lastScreenText = collectAllText(root).take(10000) // Capture a large chunk
        }
    }

    // Grabs ALL text and content descriptions recursively
    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        walk(node, sb, 0)
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 30) return
        
        // 1. Get normal text
        node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        
        // 2. Get content description (used by YouTube/Chrome icons)
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        
        // 3. Get Hint text (what's written in the search bar before typing)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        }

        // 4. Recurse
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), sb, depth + 1)
        }
    }

    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return lastScreenText
        return collectAllText(root)
    }

    fun readFocusedText(): String {
        val root = rootInActiveWindow ?: return ""
        // Find the field the user is typing into
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.text?.toString() ?: ""
    }

    fun currentForeground(): String = lastForegroundPackage
}
