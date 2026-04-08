package com.focustime.focustime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.focustime.focustime.accessibility.ReelsBlockerAccessibilityService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.focustime/accessibility"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val KEY_BLOCKED_COUNT = "flutter.blocked_count"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isAccessibilityEnabled" -> {
                        result.success(isAccessibilityServiceEnabled())
                    }
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(true)
                    }
                    "requestBatteryOptimization" -> {
                        requestIgnoreBatteryOptimization()
                        result.success(true)
                    }
                    "isIgnoringBatteryOptimizations" -> {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        result.success(pm.isIgnoringBatteryOptimizations(packageName))
                    }
                    "getBlockedCount" -> {
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        val value = prefs.all[KEY_BLOCKED_COUNT]
                        val count = when (value) {
                            is Long -> value.toInt()
                            is Int -> value
                            is Number -> value.toInt()
                            else -> 0
                        }
                        result.success(count)
                    }
                    "setMonitoredApps" -> {
                        val packages = call.argument<List<String>>("packages")
                        if (packages != null) {
                            // Store in shared prefs so the service can read them
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            prefs.edit()
                                .putStringSet("flutter.monitored_packages", packages.toSet())
                                .apply()
                            result.success(true)
                        } else {
                            result.error("INVALID_ARG", "packages argument is required", null)
                        }
                    }
                    "setCooldown" -> {
                        val cooldownMs = call.argument<Int>("cooldownMs")
                        if (cooldownMs != null) {
                            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            prefs.edit().putLong("flutter.cooldown_ms", cooldownMs.toLong()).apply()
                            result.success(true)
                        } else {
                            result.error("INVALID_ARG", "cooldownMs argument is required", null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceComponent = ComponentName(this, ReelsBlockerAccessibilityService::class.java)
        val expectedFlat = serviceComponent.flattenToString()
        val expectedShort = serviceComponent.flattenToShortString()

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        Log.d("FocusTime", "Expected flat: $expectedFlat")
        Log.d("FocusTime", "Expected short: $expectedShort")
        Log.d("FocusTime", "Enabled services raw: $enabledServices")

        if (enabledServices.isNullOrEmpty()) return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentNameStr = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameStr)
            if (enabledComponent != null && enabledComponent == serviceComponent) {
                Log.d("FocusTime", "Service matched via ComponentName: $componentNameStr")
                return true
            }
        }

        Log.d("FocusTime", "Service NOT found in enabled list")
        return false
    }

    private fun requestIgnoreBatteryOptimization() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
