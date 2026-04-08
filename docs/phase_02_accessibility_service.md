# Phase 2: Accessibility Service

**Status:** Completed

## Summary

Implemented the native Kotlin Accessibility Service that monitors screen content and blocks reels/shorts across 6 target apps (listed below) using node tree traversal with keyword matching.

## Targeted Apps

- **YouTube Shorts** — `com.google.android.youtube`
- **Instagram Reels** — `com.instagram.android`
- **TikTok** — `com.zhiliaoapp.musically`
- **TikTok (Alt)** — `com.ss.android.ugc.trill`
- **Facebook Reels** — `com.facebook.katana`
- **Snapchat Spotlight** — `com.snapchat.android`

## Files Changed

### Created
- `android/app/src/main/kotlin/com/focustime/focustime/accessibility/ReelsBlockerAccessibilityService.kt`

## Key Decisions

- **Node tree traversal** with max depth of 15 to prevent deep recursion on complex UIs
- **Dual signal detection**: matches both viewIdResourceName and contentDescription against keywords
- **Cooldown mechanism** (default 2s) prevents rapid repeated GLOBAL_ACTION_BACK presses
- **SharedPreferences bridge**: service reads cooldown and blocked count from FlutterSharedPreferences so Flutter and native share state
- **7 keywords** for detection: shorts, reel, clips_viewer, spotlight, reel_player, short_video, reels_viewer
- **Toast notification** on block with try/catch to avoid crashes in restricted contexts
- Node recycling implemented throughout to prevent memory leaks

## Privacy & Security

### Permissions Required
- `BIND_ACCESSIBILITY_SERVICE` — Grants the service access to on-screen UI elements across all monitored apps.

### Data the Service Can Access
- Full screen node tree content (viewIdResourceName, contentDescription, className) of monitored apps
- App package names and interaction events (window state/content changes)

### How Data Is Handled
- **All processing is local** — node tree content is inspected in-memory and immediately discarded after keyword matching.
- **No user data is persisted** — the service does not log, store, or transmit any screen content.
- **No network access** — the service makes no network calls.
- **SharedPreferences bridge** stores only: cooldown duration (int) and blocked count (int). No screen content or personal data.
- **Toast notification** is the only user-facing side effect of detection.

### Privacy Impact Disclosure
This service requires broad access to on-screen UI elements to detect short-form video content. Users should be informed that the app can read screen content of the monitored apps listed above. A clear in-app disclosure and privacy policy should be provided before requesting Accessibility Service permission.

## Known Issues

- Detection accuracy depends on target apps not changing their view IDs (mitigated by multiple keywords)
- Toast may not show in all Android versions/OEMs
- **Play Store policy compliance**: The Accessibility Service (node tree traversal + keyword matching) may conflict with Google Play's accessibility API policies, which require primary use to assist users with disabilities. Review current [Play Store Accessibility Service requirements](https://support.google.com/googleplay/android-developer/answer/10964491). Compliance considerations: (1) justify the service's primary purpose with clear accessibility benefit framing, (2) use minimal permissions and scoped packageNames, (3) provide full transparency in UX disclosures. Fallback plan: if rejected, consider sideloading distribution, or replace with a usage-stats/notification-listener approach that does not require accessibility APIs.
