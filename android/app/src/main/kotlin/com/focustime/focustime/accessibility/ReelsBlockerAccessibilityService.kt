package com.focustime.focustime.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focustime.focustime.service.BlockerForegroundService

class ReelsBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val KEY_COOLDOWN = "flutter.cooldown_ms"
        private const val KEY_BLOCKED_COUNT = "flutter.blocked_count"
        private const val KEY_MONITORED_PACKAGES = "flutter.monitored_packages"
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

    // TikTok packages are entirely short-form video — block when app is active
    private val fullBlockPackages = setOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill"
    )

    // View resource ID substrings that prove a shorts/reels PLAYER is on screen.
    // These are specific to each app's player layout and won't match tab labels
    // or navigation elements.
    private val playerViewIdPatterns = listOf(
        // YouTube Shorts player
        "reel_player_page_container",
        "reel_recycler",
        "reel_multi_format_player",
        "reel_player_overlay",
        "shorts_player_controls",
        "shorts_shelf",
        // Instagram Reels player
        "clips_viewer_view_pager",
        "clips_viewer",
        "reels_viewer_container",
        "reel_viewer_subtitle",
        // Facebook Reels player
        "reel_player_container",
        "reels_screen",
        "reel_video_surface",
        // Snapchat Spotlight player
        "spotlight_feed_container",
        "spotlight_player"
    )

    private var lastBlockTime = 0L
    private var cooldownMs = 2000L
    private var monitoredPackages: Set<String> = targetPackages
    private lateinit var prefs: SharedPreferences

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_MONITORED_PACKAGES -> loadMonitoredPackages()
            KEY_COOLDOWN -> cooldownMs = safeLong(KEY_COOLDOWN, 2000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        cooldownMs = safeLong(KEY_COOLDOWN, 2000L)
        loadMonitoredPackages()
        startBlockerService()
        Log.d(TAG, "Accessibility Service connected. Cooldown: ${cooldownMs}ms, Packages: $monitoredPackages")
    }

    private fun startBlockerService() {
        try {
            val serviceIntent = Intent(this, BlockerForegroundService::class.java)
            startForegroundService(serviceIntent)
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground service", e)
        }
    }

    private fun stopBlockerService() {
        try {
            val serviceIntent = Intent(this, BlockerForegroundService::class.java)
            stopService(serviceIntent)
            Log.d(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop foreground service", e)
        }
    }

    private fun loadMonitoredPackages() {
        try {
            val value = prefs.all[KEY_MONITORED_PACKAGES]
            monitoredPackages = when (value) {
                is Set<*> -> value.filterIsInstance<String>().toSet().ifEmpty { targetPackages }
                else -> targetPackages
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load monitored packages, using defaults", e)
            monitoredPackages = targetPackages
        }
        Log.d(TAG, "Monitored packages updated: $monitoredPackages")
    }

    /** Safely read a Long from prefs, handling stale Int values */
    private fun safeLong(key: String, default: Long): Long {
        return try {
            val value = prefs.all[key]
            when (value) {
                is Long -> value
                is Int -> value.toLong()
                is Number -> value.toLong()
                else -> default
            }
        } catch (e: Exception) {
            default
        }
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

        if (isReelsContent(pkg)) {
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

    private fun isReelsContent(pkg: String): Boolean {
        // TikTok apps are entirely short-form video — always block
        if (pkg in fullBlockPackages) return true

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

        // Only match on view resource IDs that belong to a shorts/reels PLAYER.
        // We intentionally do NOT match on contentDescription or text, because
        // generic labels like "Shorts" appear on navigation tabs even when the
        // user is watching regular content.
        for (pattern in playerViewIdPatterns) {
            if (pattern in viewId) {
                Log.d(TAG, "Player match: pattern='$pattern' viewId='$viewId'")
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
        val count = safeLong(KEY_BLOCKED_COUNT, 0L) + 1
        prefs.edit().putLong(KEY_BLOCKED_COUNT, count).apply()
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
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        stopBlockerService()
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed")
    }
}
