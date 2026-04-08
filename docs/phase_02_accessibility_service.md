# Phase 2: Accessibility Service

**Status:** Completed

## Summary

Implemented the native Kotlin Accessibility Service that monitors screen content and blocks reels/shorts across 6 target apps using node tree traversal with keyword matching.

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

## Known Issues

- Detection accuracy depends on target apps not changing their view IDs (mitigated by multiple keywords)
- Toast may not show in all Android versions/OEMs
