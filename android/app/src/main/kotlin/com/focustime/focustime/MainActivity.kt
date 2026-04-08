package com.focustime.focustime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
                        result.success(prefs.getInt(KEY_BLOCKED_COUNT, 0))
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
                            prefs.edit().putInt("flutter.cooldown_ms", cooldownMs).apply()
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
        val service = "$packageName/.accessibility.ReelsBlockerAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun requestIgnoreBatteryOptimization() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}
