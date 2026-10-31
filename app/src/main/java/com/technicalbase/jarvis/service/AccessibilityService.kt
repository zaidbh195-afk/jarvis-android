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

        // --- THE BLACKLIST ---
        // Jarvis will COMPLETELY IGNORE these apps (no screen reading, no keywords)
        private val IGNORED_PACKAGES = setOf(
            // WhatsApp
            "com.whatsapp",
            "com.whatsapp.w4b", // WhatsApp Business
            
            // Payment & Banking (India)
            "com.google.android.apps.nbu.paisa.user", // GPay
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM
            "com.sbi.lotusintouch",                   // SBI
            "com.icicibank.pockets",                  // ICICI
            "com.msf.kbank.mobile",                   // Kotak
            "com.hdfc.mobilebanking",                 // HDFC
            
            // System UI & Our own apps
            "com.android.systemui",
            "com.termux",
            "com.technicalbase.jarvis"
        )
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
        val currentPkg = e.packageName?.toString() ?: ""

        // 1. If the current app is in the blacklist, IMMEDIATELY ignore it
        if (IGNORED_PACKAGES.contains(currentPkg)) {
            return
        }

        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPackage = currentPkg
        }
        
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            
            val root = rootInActiveWindow ?: return
            
            // Double check the root package just to be safe
            val rootPkg = root.packageName?.toString() ?: ""
            if (IGNORED_PACKAGES.contains(rootPkg)) return

            lastScreenText = collectAllText(root).take(10000)
        }
    }

    // --- Reading & Interaction Functions (Required by LocalServer) ---
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
        val rootPkg = root.packageName?.toString() ?: ""
        if (IGNORED_PACKAGES.contains(rootPkg)) return ""
        
        return collectAllText(root)
    }

    fun readFocusedText(): String {
        val root = rootInActiveWindow ?: return ""
        val rootPkg = root.packageName?.toString() ?: ""
        if (IGNORED_PACKAGES.contains(rootPkg)) return ""
        
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return focused?.text?.toString() ?: ""
    }

    fun currentForeground(): String = lastForegroundPackage

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
