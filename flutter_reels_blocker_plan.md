# FocusTime — Reels & Shorts Blocker: Implementation Plan

## 1. Requirements Summary

**FocusTime** is an Android app built with Flutter that blocks short-form video content (Reels, Shorts, TikTok, etc.) using the Android Accessibility Service. The app:

- **Monitors the screen** via Accessibility Service to detect when reels/shorts are playing.
- **Automatically blocks** detected content by performing a global BACK action or navigating the user away.
- **Runs continuously** in the background as a foreground service.
- **Survives reboots** by auto-starting the blocking service on device boot.
- **Bypasses battery optimization** (Doze mode) so Android doesn't kill the service.
- **Requests all required permissions** from the user with clear guidance.

---

## 2. Proposed Architecture

### Why a Hybrid Approach (Flutter + Native Android)

Flutter handles the UI (settings, status, onboarding), but the Accessibility Service **must** be implemented in native Kotlin/Java because:

- `AccessibilityService` is an Android framework component that extends `android.accessibilityservice.AccessibilityService`.
- It runs independently of the Flutter engine in its own process lifecycle.
- Flutter cannot directly subclass Android services.

Communication between Flutter ↔ Native uses **MethodChannel** / **EventChannel**.

### High-Level Architecture

```
┌─────────────────────────────────────────────────┐
│                  Flutter UI Layer                │
│  - Onboarding / Permission request screens      │
│  - Service status dashboard                     │
│  - Blocked apps configuration                   │
│  - Block log / statistics                       │
└──────────────────────┬──────────────────────────┘
                       │ MethodChannel / EventChannel
┌──────────────────────▼──────────────────────────┐
│             Native Android (Kotlin)             │
│                                                 │
│  ┌─────────────────────────────────────────┐    │
│  │   ReelsBlockerAccessibilityService      │    │
│  │   - Listens to window/content changes   │    │
│  │   - Detects reels/shorts by package +   │    │
│  │     view IDs / content descriptions     │    │
│  │   - Performs GLOBAL_ACTION_BACK          │    │
│  ├─────────────────────────────────────────┤    │
│  │   BootReceiver (BroadcastReceiver)      │    │
│  │   - Starts service on BOOT_COMPLETED    │    │
│  ├─────────────────────────────────────────┤    │
│  │   ForegroundService (optional)          │    │
│  │   - Persistent notification             │    │
│  │   - Keeps process alive                 │    │
│  └─────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

---

## 3. Detection Strategy

### Target Apps & Identifiers

| App | Package Name | Detection Method |
|---|---|---|
| YouTube Shorts | `com.google.android.youtube` | View ID containing `shorts`, `reel_` prefixes, or content-desc "Shorts" |
| Instagram Reels | `com.instagram.android` | View ID containing `clips_viewer`, `reel`, content-desc "Reels" |
| TikTok | `com.zhiliaoapp.musically` / `com.ss.android.ugc.trill` | Any activity in the app (block entirely or detect feed) |
| Facebook Reels | `com.facebook.katana` | View ID or content-desc containing "Reels" |
| Snapchat Spotlight | `com.snapchat.android` | Content-desc containing "Spotlight" |

### Detection Logic (in the Accessibility Service)

1. **`onAccessibilityEvent()`** fires on `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED`.
2. Check `event.packageName` against the monitored list.
3. If package matches, traverse the node tree (`event.source` / `getRootInActiveWindow()`) looking for:
   - View IDs matching known reels/shorts identifiers.
   - Content descriptions containing keywords: `"Shorts"`, `"Reels"`, `"reel_player"`, etc.
   - Class names indicating vertical-swipe video players.
4. If detected → call `performGlobalAction(GLOBAL_ACTION_BACK)` to exit the content.
5. Optionally show a toast: *"Shorts blocked by FocusTime"*.

### Handling False Positives

- Only block when **both** the package name **and** a reels-specific view/ID are detected (not just opening YouTube).
- Maintain a configurable cooldown (e.g., 2 seconds) to avoid rapid repeated back-presses.
- Let users whitelist specific apps from the Flutter UI.

---

## 4. Step-by-Step Implementation Plan

### Phase 1: Project Setup & Native Foundation

#### Step 1.1 — Flutter Project Configuration

- [x] Flutter project already created (`focustime`).
- Add dependencies to `pubspec.yaml`:
  ```yaml
  dependencies:
    flutter:
      sdk: flutter
    shared_preferences: ^2.2.0    # Persist settings
    permission_handler: ^11.0.0   # Runtime permission requests
  ```
- Set `minSdkVersion` to **21** (or 24+ for better accessibility support) in `android/app/build.gradle.kts`.

#### Step 1.2 — Android Manifest Permissions & Declarations

Add to `AndroidManifest.xml`:

```xml
<!-- Battery optimization bypass -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- Auto-start on boot -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- Post notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Declare the Accessibility Service, Boot Receiver, and Foreground Service inside `<application>`:

```xml
<!-- Accessibility Service -->
<service
    android:name=".ReelsBlockerAccessibilityService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>

<!-- Boot Receiver -->
<receiver
    android:name=".BootReceiver"
    android:exported="true"
    android:enabled="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

#### Step 1.3 — Accessibility Service Config XML

Create `android/app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="300"
    android:packageNames=""
    android:description="@string/accessibility_service_description" />
```

> Leave `packageNames` empty to receive events from all apps, or populate it with the target list for efficiency.

---

### Phase 2: Native Kotlin Implementation

#### Step 2.1 — Accessibility Service (`ReelsBlockerAccessibilityService.kt`)

Location: `android/app/src/main/kotlin/<package>/ReelsBlockerAccessibilityService.kt`

```kotlin
class ReelsBlockerAccessibilityService : AccessibilityService() {

    private val targetPackages = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.facebook.katana",
        "com.snapchat.android"
    )

    private val reelsKeywords = listOf(
        "shorts", "reel", "clips_viewer", "spotlight"
    )

    private var lastBlockTime = 0L
    private val cooldownMs = 2000L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        if (pkg !in targetPackages) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < cooldownMs) return

        if (isReelsContent(event)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBlockTime = now
        }
    }

    private fun isReelsContent(event: AccessibilityEvent): Boolean {
        val root = rootInActiveWindow ?: return false
        return searchNodeTree(root)
    }

    private fun searchNodeTree(node: AccessibilityNodeInfo): Boolean {
        // Check view ID and content description
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (keyword in reelsKeywords) {
            if (keyword in viewId || keyword in contentDesc) {
                node.recycle()
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchNodeTree(child)) {
                node.recycle()
                return true
            }
        }

        node.recycle()
        return false
    }

    override fun onInterrupt() {}
}
```

#### Step 2.2 — Boot Receiver (`BootReceiver.kt`)

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            // The Accessibility Service auto-restarts if it was enabled.
            // Optionally start a foreground service here for reliability.
        }
    }
}
```

> **Key insight:** Android automatically restarts enabled Accessibility Services after a reboot. The Boot Receiver serves as a safety net and can start a companion foreground service.

#### Step 2.3 — Method Channel Bridge (`MainActivity.kt`)

```kotlin
class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.focustime/accessibility"

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
                        val pm = getSystemService(POWER_SERVICE) as PowerManager
                        result.success(pm.isIgnoringBatteryOptimizations(packageName))
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/.ReelsBlockerAccessibilityService"
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
```

---

### Phase 3: Flutter UI & Permission Flow

#### Step 3.1 — Permission Onboarding Screen

Create a multi-step onboarding flow in Flutter that walks the user through:

1. **Accessibility Service** — Explain why it's needed → button opens system Accessibility Settings.
2. **Battery Optimization** — Explain why → button triggers the exemption dialog.
3. **Notification Permission** (Android 13+) — Standard runtime permission request.

Each step shows a status indicator (enabled/disabled) that updates when the user returns to the app (check in `didChangeAppLifecycleState`).

#### Step 3.2 — Main Dashboard

- Service status: ON / OFF (green/red indicator).
- Blocked count today / total.
- List of monitored apps with toggles.
- Quick-action button to open Accessibility Settings if service is disabled.

#### Step 3.3 — Settings Screen

- Toggle individual apps to monitor.
- Cooldown duration slider (1–5 seconds).
- Block log history.
- Option to show/hide toast notifications on block.

---

### Phase 4: Background Persistence & Reliability

#### Step 4.1 — Foreground Service (Optional but Recommended)

Create a Kotlin foreground service with a persistent notification:

```kotlin
class BlockerForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusTime Active")
            .setContentText("Blocking reels & shorts")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}
```

This keeps the app process alive and indicates to the user that blocking is active.

#### Step 4.2 — Battery Optimization Bypass

- On first launch, call `requestIgnoreBatteryOptimization()` via MethodChannel.
- Check status with `isIgnoringBatteryOptimizations()` and show warning if not exempted.
- Handle manufacturer-specific battery managers (Xiaomi, Huawei, Samsung) by directing users to their custom autostart settings.

#### Step 4.3 — Auto-Start on Boot

- The Accessibility Service auto-restarts by the OS if enabled.
- The `BootReceiver` catches `BOOT_COMPLETED` and can start the foreground service as a secondary measure.
- For OEMs like Xiaomi/OPPO, guide the user to add the app to the "autostart" list in device settings.

---

### Phase 5: Testing & Debugging

#### Step 5.1 — Unit & Integration Testing

| Area | Testing Strategy |
|---|---|
| Detection keywords | Unit test the keyword matching logic with mock AccessibilityNodeInfo data |
| MethodChannel | Widget test that mocks the platform channel and verifies UI state |
| Permission flow | Integration test the onboarding screens |

#### Step 5.2 — Manual Testing Checklist

- [ ] Enable Accessibility Service → verify it appears in Settings.
- [ ] Open YouTube → navigate to Shorts → verify the app presses Back.
- [ ] Open Instagram → navigate to Reels → verify block.
- [ ] Open YouTube and watch a regular video → verify NO block (false positive test).
- [ ] Kill the app from recents → verify service still runs.
- [ ] Reboot device → verify service restarts automatically.
- [ ] Check battery usage after 24 hours → verify minimal drain.
- [ ] Test on Samsung, Xiaomi, Pixel (different OEM behaviors).

#### Step 5.3 — Debugging Tools

- `adb shell dumpsys accessibility` — Verify the service is registered and running.
- `adb shell settings get secure enabled_accessibility_services` — Check enabled services.
- `adb logcat -s ReelsBlocker` — Filter logs from the service.
- **Accessibility Inspector** (Android Studio → Layout Inspector) — Inspect view IDs and content descriptions of target apps for detection tuning.

---

## 5. Potential Challenges & Mitigations

| Challenge | Mitigation |
|---|---|
| **App updates change view IDs** | Use multiple detection signals (view ID + content-desc + class name). Ship keyword updates via SharedPreferences / remote config. |
| **OEM battery killers** (Xiaomi, Huawei, Samsung) | Detect manufacturer and show specific instructions to whitelist the app. Use [dontkillmyapp.com](https://dontkillmyapp.com) as reference. |
| **Google Play policy** | Accessibility Service apps face strict review. Justify the service's use clearly in the Play Store listing and accessibility service description. Consider sideloading / alternative distribution. |
| **False positives** | Require both package match AND reels-specific view detection. Add user-facing "undo" toast. |
| **Performance / battery drain** | Use `notificationTimeout` to throttle events. Only traverse the node tree when the package matches. Recycle nodes properly. |
| **Android 14+ restrictions** | New foreground service type requirements. Declare `foregroundServiceType="specialUse"` and provide justification. |
| **Node tree traversal depth** | Set a max depth (e.g., 15 levels) to prevent deep recursion on complex UIs. |

---

## 6. File Structure (Final)

```
lib/
├── main.dart                          # App entry point
├── app.dart                           # MaterialApp setup
├── screens/
│   ├── onboarding_screen.dart         # Permission setup wizard
│   ├── dashboard_screen.dart          # Main status screen
│   └── settings_screen.dart           # App configuration
├── services/
│   └── platform_channel_service.dart  # MethodChannel wrapper
├── models/
│   └── blocked_app.dart               # App config model
└── widgets/
    ├── permission_card.dart           # Permission status widget
    └── app_toggle_tile.dart           # App enable/disable tile

android/app/src/main/
├── kotlin/<package>/
│   ├── MainActivity.kt                # MethodChannel host
│   ├── ReelsBlockerAccessibilityService.kt
│   ├── BootReceiver.kt
│   └── BlockerForegroundService.kt
├── res/
│   ├── xml/
│   │   └── accessibility_service_config.xml
│   └── values/
│       └── strings.xml                # Service description
└── AndroidManifest.xml
```

---

## 7. Implementation Priority Order

1. **Accessibility Service** (core blocking logic) — this is the MVP.
2. **Permission request flow** (onboarding) — app is useless without permissions.
3. **MethodChannel bridge** — connect Flutter UI to native status.
4. **Dashboard UI** — show service status and controls.
5. **Boot Receiver + Foreground Service** — persistence across reboots.
6. **Battery optimization bypass** — prevent OS from killing service.
7. **Settings & customization** — app toggles, cooldown, logs.
8. **Testing & OEM-specific fixes** — harden for real-world use.
