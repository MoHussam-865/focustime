# Phase 6: Background Persistence

**Status:** Not Started

## Summary

Implement foreground service and boot receiver to ensure the blocking service survives app closure and device reboots.

## Planned Changes

### Native Files

- `BlockerForegroundService.kt` — Persistent notification service with `START_STICKY`
- `BootReceiver.kt` — BroadcastReceiver for `BOOT_COMPLETED` and `QUICKBOOT_POWERON`
- Notification channel creation for foreground service

### Manifest Updates

- Register `BlockerForegroundService` with `foregroundServiceType="specialUse"`
- Register `BootReceiver` with boot intent filters

### Behavior

- Foreground service starts when user enables blocking
- Persistent notification shows "FocusTime Active — Blocking reels & shorts"
- Boot receiver ensures foreground service restarts after reboot
- Accessibility Service auto-restarts by OS if enabled

## Files Changed

_To be filled after implementation._

## Key Decisions

_To be filled after implementation._

## Known Issues

_To be filled after implementation._
