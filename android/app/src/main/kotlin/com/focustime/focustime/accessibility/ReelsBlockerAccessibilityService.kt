package com.focustime.focustime.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class ReelsBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val KEY_COOLDOWN = "flutter.cooldown_ms"
        private const val KEY_BLOCKED_COUNT = "flutter.blocked_count"
        private const val MAX_TREE_DEPTH = 15
    }

    private val targetPackages = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana",
        "com.snapchat.android"
    )

    private val reelsKeywords = listOf(
        "shorts", "reel", "clips_viewer", "spotlight",
        "reel_player", "short_video", "reels_viewer"
    )

    private var lastBlockTime = 0L
    private var cooldownMs = 2000L
    private var monitoredPackages: Set<String> = targetPackages
    private lateinit var prefs: SharedPreferences

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        cooldownMs = prefs.getInt(KEY_COOLDOWN, 2000).toLong()
        Log.d(TAG, "Accessibility Service connected. Cooldown: ${cooldownMs}ms")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        if (pkg !in monitoredPackages) return

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < cooldownMs) return

        if (isReelsContent()) {
            Log.d(TAG, "Reels/Shorts detected in $pkg — blocking")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBlockTime = now
            incrementBlockedCount()

            try {
                Toast.makeText(this, "Blocked by FocusTime", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Could not show toast", e)
            }
        }
    }

    private fun isReelsContent(): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = searchNodeTree(root, 0)
        return found
    }

    private fun searchNodeTree(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > MAX_TREE_DEPTH) {
            node.recycle()
            return false
        }

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        for (keyword in reelsKeywords) {
            if (keyword in viewId || keyword in contentDesc) {
                Log.d(TAG, "Match found: keyword='$keyword' viewId='$viewId' desc='$contentDesc'")
                node.recycle()
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child: AccessibilityNodeInfo
            try {
                child = node.getChild(i) ?: continue
            } catch (e: Exception) {
                continue
            }
            if (searchNodeTree(child, depth + 1)) {
                node.recycle()
                return true
            }
        }

        node.recycle()
        return false
    }

    private fun incrementBlockedCount() {
        val count = prefs.getInt(KEY_BLOCKED_COUNT, 0) + 1
        prefs.edit().putInt(KEY_BLOCKED_COUNT, count).apply()
    }

    fun updateMonitoredPackages(packages: List<String>) {
        monitoredPackages = packages.toSet()
        Log.d(TAG, "Updated monitored packages: $monitoredPackages")
    }

    fun updateCooldown(ms: Int) {
        cooldownMs = ms.toLong()
        Log.d(TAG, "Updated cooldown: ${cooldownMs}ms")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed")
    }
}
