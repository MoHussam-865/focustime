# Phase 4: Permission Flow & Onboarding

**Status:** Not Started

## Summary

Implement the onboarding screen that guides users through enabling all required permissions: Accessibility Service, battery optimization bypass, and notifications.

## Planned Changes

### Views

- `OnboardingScreen` — Multi-step permission wizard
- Each step shows permission status (enabled/disabled) with a button to open the relevant settings

### ViewModel

- `OnboardingViewModel` — Tracks permission states, refreshes on app resume via `AppLifecycleState`

### Permission Steps

1. **Accessibility Service** — Explain purpose → open Accessibility Settings
2. **Battery Optimization** — Explain purpose → trigger exemption dialog
3. **Notifications** (Android 13+) — Standard runtime permission request

### UX

- Permission statuses refresh automatically when returning from system settings
- "Continue" button only enabled when all required permissions are granted
- Skip option with warning for non-critical permissions

## Files Changed

_To be filled after implementation._

## Key Decisions

_To be filled after implementation._

## Known Issues

_To be filled after implementation._
