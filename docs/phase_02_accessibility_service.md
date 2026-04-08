# Phase 2: Accessibility Service

**Status:** Not Started

## Summary

Implement the native Kotlin Accessibility Service that monitors screen content and detects reels/shorts across target apps.

## Planned Changes

### Native Files

- `ReelsBlockerAccessibilityService.kt` — Core service with `onAccessibilityEvent` handler
- `NodeTreeScanner.kt` — Extracted tree traversal logic for detecting reels-specific views
- Accessibility service declaration in `AndroidManifest.xml`

### Detection Logic

- Listen for `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED` events
- Match `event.packageName` against target app list
- Traverse node tree searching for reels/shorts view IDs and content descriptions
- Perform `GLOBAL_ACTION_BACK` when reels content is detected
- Implement cooldown mechanism to prevent rapid repeated actions

### Target Apps

| App | Package Name |
|---|---|
| YouTube | `com.google.android.youtube` |
| Instagram | `com.instagram.android` |
| TikTok | `com.zhiliaoapp.musically` |
| Facebook | `com.facebook.katana` |
| Snapchat | `com.snapchat.android` |

## Files Changed

_To be filled after implementation._

## Key Decisions

_To be filled after implementation._

## Known Issues

_To be filled after implementation._
