# Phase 3: Method Channel Bridge

**Status:** Not Started

## Summary

Implement the MethodChannel and EventChannel bridge between Flutter and native Android to query service status, manage permissions, and receive block events.

## Planned Changes

### Native Side (`MainActivity.kt`)

- Register MethodChannel `com.focustime/accessibility`
- Implement handlers:
  - `isAccessibilityEnabled` — Check if the service is active
  - `openAccessibilitySettings` — Launch system accessibility settings
  - `requestBatteryOptimization` — Request battery optimization exemption
  - `isIgnoringBatteryOptimizations` — Check exemption status
  - `getBlockedCount` — Return total blocks performed
  - `setMonitoredApps` — Update the list of apps to monitor

### Flutter Side (`lib/services/`)

- `AccessibilityService` — Dart wrapper around the MethodChannel
- `PermissionService` — Encapsulates all permission-related channel calls

### Contract

- All channel calls wrapped in try/catch for `PlatformException`
- ViewModels never call MethodChannel directly

## Files Changed

_To be filled after implementation._

## Key Decisions

_To be filled after implementation._

## Known Issues

_To be filled after implementation._
