# Phase 8: Testing

**Status:** Not Started

## Summary

Write unit tests for ViewModels, repository, and service layers. Compile manual testing checklist for Accessibility Service validation.

## Planned Changes

### Unit Tests

| File | Tests |
|---|---|
| `dashboard_viewmodel_test.dart` | Service status check, block count retrieval, error handling |
| `onboarding_viewmodel_test.dart` | Permission state transitions, refresh on resume |
| `settings_viewmodel_test.dart` | Load/save settings, monitored apps update |
| `settings_repository_test.dart` | SharedPreferences read/write correctness |
| `accessibility_service_test.dart` | MethodChannel mock — all method calls and error paths |

### Manual Testing Checklist

- [ ] Enable Accessibility Service via onboarding
- [ ] Open YouTube → Shorts tab → verify back action triggers
- [ ] Open Instagram → Reels tab → verify back action triggers
- [ ] Watch a regular YouTube video → verify no false positive
- [ ] Disable a specific app in settings → verify it is no longer blocked
- [ ] Kill app from recents → verify service continues running
- [ ] Reboot device → verify service restarts
- [ ] Check battery usage after 24h
- [ ] Test on Samsung, Xiaomi, Pixel devices
- [ ] Verify `adb shell dumpsys accessibility` shows the service

### Dependencies

- `mockito` + `build_runner` for mock generation
- `flutter_test` (built-in)

## Files Changed

_To be filled after implementation._

## Key Decisions

_To be filled after implementation._

## Known Issues

_To be filled after implementation._
