# Phase 7: Settings & Configuration

**Status:** Not Started

## Summary

Implement the settings screen for app customization: per-app toggles, cooldown duration, toast preferences, and block log.

## Planned Changes

### Views

- `SettingsScreen` — User configuration UI

### ViewModel

- `SettingsViewModel` — Read/write settings via repository

### Repository

- `SettingsRepository` / `SettingsRepositoryImpl` — SharedPreferences-backed persistence

### Settings

| Setting | Type | Default |
|---|---|---|
| Monitored apps (per-app toggle) | `Map<String, bool>` | All enabled |
| Cooldown duration | `int` (ms) | 2000 |
| Show toast on block | `bool` | true |
| Block log enabled | `bool` | true |

### Data Flow

- Settings changes saved to SharedPreferences immediately
- Updated app list sent to native service via MethodChannel
- Cooldown value read by Accessibility Service from SharedPreferences (native side)

## Files Changed

_To be filled after implementation._

## Key Decisions

_To be filled after implementation._

## Known Issues

_To be filled after implementation._
