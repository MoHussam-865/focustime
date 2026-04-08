# Phase 4: Permission Flow and Onboarding

**Status:** Completed

## Summary

Implemented the onboarding screen with a multi-step permission wizard that guides users through enabling Accessibility Service and Battery Optimization bypass.

## Files Changed

### Created
- `lib/views/onboarding/onboarding_screen.dart` - Full onboarding UI
- `lib/viewmodels/onboarding_viewmodel.dart` - Permission state management

## Features

- Permission status cards with green check/gray circle indicators
- Tapping a disabled permission opens the relevant system settings
- Refresh Status button to recheck permissions after returning from settings
- Continue button enabled only when all permissions granted
- Skip for now option for users who want to configure later
- ChangeNotifier-based state with isLoading indicator

## Key Decisions

- AppLifecycleState.resumed handling in DashboardScreen (not onboarding) to auto-refresh on return
- Skip option provided to avoid blocking users from exploring the app

## Known Issues

- Android 13+ notification permission (POST_NOTIFICATIONS) is not yet handled as a runtime permission. On targetSdk >= 33, a dedicated runtime flow should be added: check/request the POST_NOTIFICATIONS permission during onboarding, handle rationale and permanently-denied states (direct user to app settings), and update the onboarding UI to include a notification permission step. Currently relies on the system-level prompt only.
