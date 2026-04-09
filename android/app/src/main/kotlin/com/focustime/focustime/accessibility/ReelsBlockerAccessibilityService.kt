package com.matrixlab.focustime.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.matrixlab.focustime.filter.BlockLists
import com.matrixlab.focustime.filter.ContentFilterManager
import com.matrixlab.focustime.filter.NsfwDetector
import com.matrixlab.focustime.service.BlockerForegroundService
import java.security.MessageDigest
import java.util.concurrent.Executors

class ReelsBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val KEY_COOLDOWN = "flutter.cooldown_ms"
        private const val KEY_BLOCKED_COUNT = "flutter.blocked_count"
        private const val KEY_MONITORED_PACKAGES = "flutter.monitored_packages"
        private const val KEY_PORN_BLOCK_ENABLED = "flutter.porn_block_enabled"
        private const val KEY_AI_NSFW_SCAN_ENABLED = "flutter.ai_nsfw_scan_enabled"
        private const val MAX_TREE_DEPTH = 15
        private const val AI_SCAN_COOLDOWN_MS = 750L
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
    private var lastPornBlockTime = 0L
    private var lastAiScanTime = 0L
    private var cooldownMs = 2000L
    private val pornCooldownMs = 3000L
    private var monitoredPackages: Set<String> = targetPackages
    private var pornBlockEnabled = true
    private var aiNsfwScanEnabled = false
    private lateinit var prefs: SharedPreferences
    private val contentFilter = ContentFilterManager()
    private var nsfwDetector: NsfwDetector? = null
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private val aiBackPressExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var aiScanInProgress = false
    @Volatile private var screenOn = true
    private var lastScreenshotHash = ""

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_MONITORED_PACKAGES -> loadMonitoredPackages()
            KEY_COOLDOWN -> cooldownMs = safeLong(KEY_COOLDOWN, 2000L)
            KEY_PORN_BLOCK_ENABLED -> loadPornBlockEnabled()
            KEY_AI_NSFW_SCAN_ENABLED -> loadAiNsfwScanEnabled()
        }
    }

    // Pauses AI scanning when screen is off / device locked
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    Log.d(TAG, "Screen OFF — AI scanning paused")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    Log.d(TAG, "Screen ON — AI scanning resumed")
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    Log.d(TAG, "Device unlocked — AI scanning resumed")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        cooldownMs = safeLong(KEY_COOLDOWN, 2000L)
        loadMonitoredPackages()
        loadPornBlockEnabled()
        loadAiNsfwScanEnabled()
        applyDynamicPackageFilter()
        registerScreenReceiver()
        startBlockerService()
        Log.d(TAG, "Accessibility Service connected. Cooldown: ${cooldownMs}ms, Packages: $monitoredPackages")
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        // Check initial state
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenOn = pm.isInteractive
        Log.d(TAG, "Screen receiver registered, screenOn=$screenOn")
    }

    /**
     * Dynamically set the packages the service monitors.
     * Merges reels targets + browser packages + porn app packages
     * so the service receives events from all of them.
     */
    private fun applyDynamicPackageFilter() {
        try {
            // When AI NSFW scan is on, monitor ALL apps (no package filter)
            if (aiNsfwScanEnabled && pornBlockEnabled) {
                serviceInfo = serviceInfo.apply {
                    packageNames = null
                }
                Log.d(TAG, "Dynamic package filter: monitoring ALL apps (AI scan enabled)")
                return
            }
            var allPackages = monitoredPackages.toMutableSet()
            if (pornBlockEnabled) {
                allPackages += BlockLists.browserPackages
                allPackages += BlockLists.pornAppPackages
            }
            serviceInfo = serviceInfo.apply {
                packageNames = allPackages.toTypedArray()
            }
            Log.d(TAG, "Dynamic package filter applied: ${allPackages.size} packages (pornBlock=$pornBlockEnabled)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dynamic package filter", e)
        }
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
        // Re-apply dynamic filter so browser + porn app packages are always included
        if (::prefs.isInitialized) {
            applyDynamicPackageFilter()
        }
    }

    private fun loadPornBlockEnabled() {
        pornBlockEnabled = try {
            prefs.getBoolean(KEY_PORN_BLOCK_ENABLED, true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load porn block setting, defaulting to true", e)
            true
        }
        Log.d(TAG, "Porn block enabled: $pornBlockEnabled")
        if (::prefs.isInitialized) {
            applyDynamicPackageFilter()
        }
    }

    private fun loadAiNsfwScanEnabled() {
        val wasEnabled = aiNsfwScanEnabled
        aiNsfwScanEnabled = try {
            prefs.getBoolean(KEY_AI_NSFW_SCAN_ENABLED, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load AI NSFW scan setting, defaulting to false", e)
            false
        }
        Log.d(TAG, "AI NSFW scan enabled: $aiNsfwScanEnabled (was: $wasEnabled)")
        
        // Re-apply package filter (null = all apps when AI is on)
        if (::prefs.isInitialized) {
            applyDynamicPackageFilter()
        }
        
        // Initialize or release the detector based on the setting
        if (aiNsfwScanEnabled && nsfwDetector == null) {
            try {
                Log.d(TAG, "Initializing NsfwDetector (YOLO)...")
                nsfwDetector = NsfwDetector(this)
                Log.d(TAG, "✓ NsfwDetector initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to initialize NsfwDetector", e)
                aiNsfwScanEnabled = false
            }
        } else if (!aiNsfwScanEnabled && wasEnabled) {
            Log.d(TAG, "Releasing NsfwDetector...")
            nsfwDetector?.close()
            nsfwDetector = null
            Log.d(TAG, "✓ NsfwDetector released")
        }
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

        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = System.currentTimeMillis()

        // ── Layer 1: Known porn app — block immediately ──────────────
        if (pornBlockEnabled && contentFilter.isPornApp(pkg)) {
            if (now - lastPornBlockTime < pornCooldownMs) return
            Log.d(TAG, "Porn app detected: $pkg — blocking")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastPornBlockTime = now
            incrementBlockedCount()
            showToast("Content blocked by FocusTime")
            return
        }

        // ── Layer 2: Browser — check URL / keywords ──────────────────
        if (pornBlockEnabled && contentFilter.isBrowserApp(pkg)) {
            if (now - lastPornBlockTime < pornCooldownMs) return
            val root = rootInActiveWindow ?: return
            try {
                if (contentFilter.shouldBlockBrowserContent(root)) {
                    Log.d(TAG, "Porn content detected in browser $pkg — blocking")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    lastPornBlockTime = now
                    incrementBlockedCount()
                    showToast("Content blocked by FocusTime")
                    return
                }
                // ── Layer 2.5: AI YOLO scan in browser ──────────────────
                if (aiNsfwScanEnabled && screenOn && !aiScanInProgress) {
                    if (now - lastAiScanTime >= AI_SCAN_COOLDOWN_MS) {
                        lastAiScanTime = now
                        triggerAiScreenshotScan(pkg)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking browser content", e)
            }
            // Browser events don't fall through to reels check
            return
        }

        // ── Layer 2.5b: AI YOLO scan in any other app ──────────────
        if (pornBlockEnabled && aiNsfwScanEnabled && screenOn && !aiScanInProgress) {
            if (now - lastAiScanTime >= AI_SCAN_COOLDOWN_MS) {
                lastAiScanTime = now
                triggerAiScreenshotScan(pkg)
            }
        }

        // ── Layer 3: Reels / Shorts blocking (existing logic) ────────
        if (pkg !in monitoredPackages) return
        if (now - lastBlockTime < cooldownMs) return

        if (isReelsContent(pkg)) {
            Log.d(TAG, "Reels/Shorts detected in $pkg — blocking")
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBlockTime = now
            incrementBlockedCount()
            showToast("Blocked by FocusTime")
        }
    }

    private fun showToast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not show toast", e)
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
                // Log.d(TAG, "Player match: pattern='$pattern' viewId='$viewId'")
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

    // ── AI YOLO Scanning ──────────────────────────────────────────

    /**
     * Takes a screenshot, checks if content changed via hash.
     * Only runs YOLO detection if screen content is new (hash differs).
     * No cropping needed — YOLO spatially detects objects in the full frame.
     */
    private fun triggerAiScreenshotScan(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "AI scan skipped: requires API 30+")
            return
        }
        val detector = nsfwDetector ?: run {
            Log.w(TAG, "AI scan: detector is null")
            return
        }
        aiScanInProgress = true

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            aiExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hwBmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (hwBmp == null) { Log.w(TAG, "AI scan: null hwBmp"); return }

                        val softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                        hwBmp.recycle()
                        result.hardwareBuffer.close()
                        if (softBmp == null) { Log.w(TAG, "AI scan: copy failed"); return }

                        // Check if screen content changed via hash
                        val currentHash = screenshotHash(softBmp)
                        if (currentHash == lastScreenshotHash) {
                            // Log.d(TAG, "AI scan: screen unchanged (same hash) — skipping")
                            softBmp.recycle()
                            aiScanInProgress = false
                            return
                        }
                        lastScreenshotHash = currentHash

                        // Log.d(TAG, "AI scan: screenshot ${softBmp.width}x${softBmp.height} for $pkg (hash=$currentHash)")

                        val result = detector.detect(softBmp)

                        // DEBUG: save annotated image (commented out)
                        // detector.saveDebugImage(softBmp, result)

                        softBmp.recycle()

                        if (result.isUnsafe) {
                            // Confirmation scan: wait for screen to settle, re-scan to avoid
                            // false positives from scroll blur / half-rendered frames
                            Thread.sleep(300)
                            val confirmed = confirmUnsafe(detector)
                            if (!confirmed) {
                                Log.d(TAG, "AI scan: first scan UNSAFE but confirmation SAFE — false positive, ignoring")
                                aiScanInProgress = false
                                return
                            }
                            Log.d(TAG, "AI scan: *** NSFW CONFIRMED in $pkg *** — pressing back until safe")
                            lastPornBlockTime = System.currentTimeMillis()
                            incrementBlockedCount()
                            android.os.Handler(mainLooper).post {
                                showToast("Content blocked by FocusTime (AI)")
                            }
                            pressBackUntilSafe(pkg, detector)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "AI scan failed", e)
                    } finally {
                        aiScanInProgress = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "AI scan: screenshot failed code=$errorCode")
                    aiScanInProgress = false
                }
            }
        )
    }

    /**
     * Confirmation scan: takes a fresh screenshot and runs detection.
     * Returns true if the new scan is also UNSAFE.
     * Blocks the calling thread (runs on aiExecutor so that's fine).
     */
    private fun confirmUnsafe(detector: NsfwDetector): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val latch = java.util.concurrent.CountDownLatch(1)
        var isUnsafe = false

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            aiBackPressExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hwBmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?: return
                        val softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                        hwBmp.recycle()
                        result.hardwareBuffer.close()
                        if (softBmp == null) return

                        val detectResult = detector.detect(softBmp)
                        softBmp.recycle()
                        isUnsafe = detectResult.isUnsafe
                    } catch (e: Exception) {
                        Log.e(TAG, "Confirmation scan failed", e)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Confirmation screenshot failed code=$errorCode")
                    latch.countDown()
                }
            }
        )

        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return isUnsafe
    }

    /**
     * Press back, take a new screenshot, check if still NSFW, repeat until safe or max attempts.
     * Runs on the AI executor thread so no delays block the main thread.
     */
    private fun pressBackUntilSafe(pkg: String, detector: NsfwDetector, maxAttempts: Int = 10) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            performGlobalAction(GLOBAL_ACTION_BACK)
            // Log.d(TAG, "AI back-press #$attempts for $pkg")

            // Brief wait for the screen to update after back press
            try { Thread.sleep(350) } catch (_: InterruptedException) { return }

            // Take a new screenshot synchronously via a blocking latch
            val latch = java.util.concurrent.CountDownLatch(1)
            var stillUnsafe = false
            var scanFailed = false

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                aiBackPressExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            if (hwBmp == null) { scanFailed = true; return }
                            val softBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                            hwBmp.recycle()
                            result.hardwareBuffer.close()
                            if (softBmp == null) { scanFailed = true; return }

                            val detectResult = detector.detect(softBmp)
                            // detector.saveDebugImage(softBmp, detectResult)
                            softBmp.recycle()
                            stillUnsafe = detectResult.isUnsafe
                            // Log.d(TAG, "AI back-press #$attempts re-scan: ${if (stillUnsafe) "STILL UNSAFE" else "NOW SAFE"}")
                        } catch (e: Exception) {
                            Log.e(TAG, "AI back-press re-scan failed", e)
                            scanFailed = true
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "AI back-press screenshot failed code=$errorCode")
                        scanFailed = true
                        latch.countDown()
                    }
                }
            )

            // Wait for the screenshot callback
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { break }

            if (scanFailed || !stillUnsafe) {
                if (!stillUnsafe) Log.d(TAG, "AI: screen is now safe after $attempts back presses")
                break
            }
        }

        if (attempts >= maxAttempts) {
            Log.d(TAG, "AI: max back presses reached ($maxAttempts), going home")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        aiScanInProgress = false
    }

    /**
     * Compute SHA-256 hash of bitmap bytes for change detection.
     * Returns first 8 chars of hash for logging.
     */
    private fun screenshotHash(bitmap: Bitmap): String {
        val bytes = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(bytes, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val digest = MessageDigest.getInstance("SHA-256")
        val byteData = ByteArray(bytes.size * 4)
        for (i in bytes.indices) {
            byteData[i * 4] = (bytes[i] shr 24).toByte()
            byteData[i * 4 + 1] = (bytes[i] shr 16).toByte()
            byteData[i * 4 + 2] = (bytes[i] shr 8).toByte()
            byteData[i * 4 + 3] = bytes[i].toByte()
        }
        val hash = digest.digest(byteData)
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        nsfwDetector?.close()
        nsfwDetector = null
        aiExecutor.shutdownNow()
        stopBlockerService()
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed")
    }
}
