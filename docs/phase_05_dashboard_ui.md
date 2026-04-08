# Phase 5: Dashboard UI

**Status:** Completed

## Summary

Built the main dashboard screen with service status indicator, block counter, and monitored apps list with toggle switches.

## Files Changed

### Created
- `lib/views/dashboard/dashboard_screen.dart` - Full dashboard with status, stats, and app toggles
- `lib/viewmodels/dashboard_viewmodel.dart` - Dashboard state management

## Features

- Large status card: green (Protection Active) or red (Protection Disabled)
- Enable service button shown when service is disabled
- Block count display with icon
- Monitored apps list with SwitchListTile for each app
- Pull-to-refresh to reload all data
- WidgetsBindingObserver auto-refreshes on app resume (returning from settings)
- Settings gear icon in AppBar navigates to SettingsScreen
- Error message display

## Key Decisions

- ChangeNotifierProvider created at DashboardScreen level, not globally
- loadData() called on creation and on resume for always-fresh state

## Known Issues

- None
