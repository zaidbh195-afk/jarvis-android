package com.technicalbase.jarvis.service

import android.accessibilityservice.AccessibilityService as BaseAS
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AccessibilityService : BaseAS() {

    companion object {
        private const val TAG = "Jarvis.A11y"

        @Volatile
        var instance: AccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    @Volatile var lastForegroundPackage: String = ""
        private set
    @Volatile var lastScreenText: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        // Track foreground package
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            e.packageName?.let { lastForegroundPackage = it.toString() }
        }

        // Snapshot visible text (cheap — only on major events)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val root = rootInActiveWindow ?: return
            lastScreenText = collectText(root).take(8000)
        }
    }

    // ---------------------------------------------------------------
    private fun collectText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        walk(node, sb, 0)
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 25) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), sb, depth + 1)
        }
    }

    private fun findNodeByText(root: AccessibilityNodeInfo?, needle: String): AccessibilityNodeInfo? {
        if (root == null || needle.isBlank()) return null
        val target = needle.lowercase().trim()

        // pass 1: exact match on text
        findByText(root, target, exact = true)?.let { return it }
        // pass 2: exact match on content description
        findByDesc(root, target, exact = true)?.let { return it }
        // pass 3: partial match
        findByText(root, target, exact = false)?.let { return it }
        // pass 4: partial on desc
        findByDesc(root, target, exact = false)?.let { return it }
        return null
    }

    private fun findByText(node: AccessibilityNodeInfo?, target: String, exact: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString()?.lowercase()?.trim()
        if (t != null) {
            if (exact && t == target) return node
            if (!exact && t.contains(target)) return node
        }
        for (i in 0 until node.childCount) {
            findByText(node.getChild(i), target, exact)?.let { return it }
        }
        return null
    }

    private fun findByDesc(node: AccessibilityNodeInfo?, target: String, exact: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val d = node.contentDescription?.toString()?.lowercase()?.trim()
        if (d != null) {
            if (exact && d == target) return node
            if (!exact && d.contains(target)) return node
        }
        for (i in 0 until node.childCount) {
            findByDesc(node.getChild(i), target, exact)?.let { return it }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var up = 0
        while (cur != null && up < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            up++
        }
        return node
    }

    // ---------------------------------------------------------------
    // Public API — called by LocalServer
    // ---------------------------------------------------------------

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false
        val clickable = findClickableAncestor(node) ?: return false

        // Try direct click first
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        // Fallback: gesture tap at center
        val r = Rect()
        clickable.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = android.graphics.Path()
        path.moveTo(r.exactCenterX(), r.exactCenterY())
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0L, 80L
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return lastScreenText
        return collectText(root)
    }

    fun readFocusedText(): String {
        val root = rootInActiveWindow ?: return ""
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return ""
        return focused.text?.toString() ?: ""
    }

    fun currentForeground(): String = lastForegroundPackage
}
