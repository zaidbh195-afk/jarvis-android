package com.technicalbase.jarvis.service

import android.accessibilityservice.AccessibilityService as BaseAS
import android.os.Bundle
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
            lastScreenText = collectAllText(root).take(10000)
        }
    }

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        walk(node, sb, 0)
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 30) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.let { if (it.isNotBlank()) sb.append(it).append('\n') }
        }

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
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.text?.toString() ?: ""
    }

    fun currentForeground(): String = lastForegroundPackage

    // --- RESTORED FUNCTIONS FOR LOCALSERVER.KT ---
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = text.lowercase().trim()

        fun findNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val t = node.text?.toString()?.lowercase()?.trim()
            val d = node.contentDescription?.toString()?.lowercase()?.trim()
            if (t == target || d == target || (t != null && t.contains(target)) || (d != null && d.contains(target))) {
                return node
            }
            for (i in 0 until node.childCount) {
                findNode(node.getChild(i))?.let { return it }
            }
            return null
        }

        val node = findNode(root) ?: return false
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up < 5) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
            up++
        }
        return false
    }
}
