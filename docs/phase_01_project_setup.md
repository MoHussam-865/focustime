# Phase 1: Project Setup

**Status:** Completed

## Summary

Configured the Flutter project with MVVM architecture, added all dependencies, set up Android manifest with permissions and service declarations, created resource files, and established the full Dart folder structure with models, services, repositories, ViewModels, views, and DI.

## Files Changed

### Modified
- `pubspec.yaml` - Added get_it, shared_preferences, provider dependencies
- `android/app/build.gradle.kts` - Set minSdk to 24
- `android/app/src/main/AndroidManifest.xml` - Added permissions, service/receiver declarations
- `analysis_options.yaml` - Existing linting rules retained

### Created
- `android/app/src/main/res/xml/accessibility_service_config.xml` - Accessibility service configuration
- `android/app/src/main/res/values/strings.xml` - Service description string
- `lib/main.dart` - New entry point with DI initialization
- `lib/app.dart` - MaterialApp with theme and onboarding/dashboard routing
- `lib/di/service_locator.dart` - get_it dependency injection setup
- `lib/models/blocked_app.dart` - BlockedApp data model with defaults
- `lib/models/block_event.dart` - BlockEvent data model
- `lib/models/app_settings.dart` - AppSettings data model
- `lib/services/accessibility_service.dart` - MethodChannel wrapper
- `lib/repositories/settings_repository.dart` - Settings persistence (interface + impl)
- `lib/viewmodels/onboarding_viewmodel.dart` - Onboarding permission state
- `lib/viewmodels/dashboard_viewmodel.dart` - Dashboard state and app toggling
- `lib/viewmodels/settings_viewmodel.dart` - Settings management

## Key Decisions

- **minSdk 24** chosen for reliable Accessibility Service and foreground service support
- **get_it** for DI instead of Provider-only approach, allowing ViewModels to be resolved outside widget tree
- **SharedPreferences** shared between Flutter and native Kotlin via `FlutterSharedPreferences` naming convention
- Interface + implementation split for SettingsRepository to enable testing with mocks

## Known Issues

- None
