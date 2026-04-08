# FocusTime — Development Roles & Guidelines

## 1. Architectural Pattern: MVVM

All features must follow **Model-View-ViewModel (MVVM)**.

### Layers

| Layer | Responsibility | Location |
|---|---|---|
| **Model** | Data classes, entities, repository interfaces | `lib/models/`, `lib/repositories/` |
| **View** | Widgets and screens — UI only, no business logic | `lib/views/` |
| **ViewModel** | Business logic, state, and platform channel calls | `lib/viewmodels/` |

### Rules

- **Views** never call repositories, services, or platform channels directly.
- **Views** observe a ViewModel via `ChangeNotifier` + `ListenableBuilder` (or `Consumer` if using Provider).
- **ViewModels** never import Flutter widget classes (`material.dart`, etc.). They depend only on models, repositories, and services.
- **Models** are plain Dart classes with no framework dependencies.

### Directory Structure

```
lib/
├── main.dart
├── app.dart
├── di/
│   └── service_locator.dart          # Dependency injection setup
├── models/
│   ├── blocked_app.dart
│   ├── block_event.dart
│   └── app_settings.dart
├── repositories/
│   ├── settings_repository.dart      # Interface
│   └── settings_repository_impl.dart # Implementation
├── services/
│   ├── accessibility_service.dart    # Platform channel wrapper
│   └── permission_service.dart       # Permission logic
├── viewmodels/
│   ├── onboarding_viewmodel.dart
│   ├── dashboard_viewmodel.dart
│   └── settings_viewmodel.dart
├── views/
│   ├── onboarding/
│   │   └── onboarding_screen.dart
│   ├── dashboard/
│   │   └── dashboard_screen.dart
│   └── settings/
│       └── settings_screen.dart
└── widgets/
    ├── permission_card.dart
    └── app_toggle_tile.dart
```

---

## 2. State Management: ChangeNotifier

All ViewModels extend `ChangeNotifier`.

### ViewModel Template

```dart
class DashboardViewModel extends ChangeNotifier {
  final AccessibilityService _accessibilityService;
  final SettingsRepository _settingsRepository;

  bool _isServiceRunning = false;
  bool get isServiceRunning => _isServiceRunning;

  DashboardViewModel({
    required AccessibilityService accessibilityService,
    required SettingsRepository settingsRepository,
  })  : _accessibilityService = accessibilityService,
        _settingsRepository = settingsRepository;

  Future<void> checkServiceStatus() async {
    _isServiceRunning = await _accessibilityService.isEnabled();
    notifyListeners();
  }
}
```

### Rules

- Call `notifyListeners()` only when state actually changes.
- Keep ViewModels focused — one ViewModel per screen/feature.
- Expose state via getters, not public fields.
- Async operations must handle errors and set loading/error states.

### State Pattern

Every ViewModel that loads data should expose:

```dart
bool isLoading = false;
String? errorMessage;
```

---

## 3. Dependency Injection

Use a **Service Locator** pattern via the `get_it` package.

### Setup (`lib/di/service_locator.dart`)

```dart
import 'package:get_it/get_it.dart';

final getIt = GetIt.instance;

void setupDependencies() {
  // Services (singletons)
  getIt.registerLazySingleton<AccessibilityService>(
    () => AccessibilityService(),
  );
  getIt.registerLazySingleton<PermissionService>(
    () => PermissionService(),
  );

  // Repositories (singletons)
  getIt.registerLazySingleton<SettingsRepository>(
    () => SettingsRepositoryImpl(),
  );

  // ViewModels (factory — new instance per screen)
  getIt.registerFactory<DashboardViewModel>(
    () => DashboardViewModel(
      accessibilityService: getIt<AccessibilityService>(),
      settingsRepository: getIt<SettingsRepository>(),
    ),
  );
}
```

### Rules

- Register services and repositories as **singletons** (`registerLazySingleton`).
- Register ViewModels as **factories** (`registerFactory`) — each screen gets a fresh instance.
- All dependencies are injected via constructor parameters — never instantiate inside a class.
- Views obtain their ViewModel from `getIt` or a Provider wired to `getIt`.

---

## 4. Code Quality Standards

### Naming Conventions

| Element | Convention | Example |
|---|---|---|
| Files | `snake_case` | `dashboard_viewmodel.dart` |
| Classes | `PascalCase` | `DashboardViewModel` |
| Variables / functions | `camelCase` | `isServiceRunning` |
| Constants | `camelCase` (Dart convention) | `defaultCooldownMs` |
| Private members | Prefix `_` | `_isLoading` |

### Code Rules

1. **No business logic in Views.** Views call ViewModel methods and render state.
2. **No UI code in ViewModels.** ViewModels never use `BuildContext`, `Navigator`, or widget classes.
3. **Single Responsibility.** Each class has one clear purpose.
4. **DRY.** Extract shared logic into services or utility classes only when used in 3+ places.
5. **Favor composition over inheritance** for widgets and ViewModels.
6. **Use `const` constructors** wherever possible.
7. **Avoid `dynamic`** — always declare types explicitly.
8. **Max method length:** ~40 lines. Extract longer methods into private helpers.
9. **Max file length:** ~300 lines. Split larger files by responsibility.

### Linting

Use the existing `analysis_options.yaml`. Enforce at minimum:

```yaml
linter:
  rules:
    - prefer_const_constructors
    - prefer_const_declarations
    - avoid_print
    - require_trailing_commas
    - prefer_final_locals
    - always_declare_return_types
```

---

## 5. Native Code Guidelines (Kotlin)

### Structure

```
android/app/src/main/kotlin/<package>/
├── MainActivity.kt                          # MethodChannel host
├── accessibility/
│   ├── ReelsBlockerAccessibilityService.kt  # Core service
│   └── NodeTreeScanner.kt                   # Tree traversal logic
├── receiver/
│   └── BootReceiver.kt                      # BOOT_COMPLETED handler
└── service/
    └── BlockerForegroundService.kt          # Persistent notification
```

### Rules

- Keep `AccessibilityService` lean — delegate detection logic to a separate scanner class.
- Recycle `AccessibilityNodeInfo` objects to prevent memory leaks.
- Use structured logging with a TAG for each class (`Log.d(TAG, ...)`).
- Never hard-code view IDs inline — keep detection keywords in a centralized list.

---

## 6. Testing Guidelines

### Test Structure

```
test/
├── viewmodels/
│   ├── onboarding_viewmodel_test.dart
│   ├── dashboard_viewmodel_test.dart
│   └── settings_viewmodel_test.dart
├── repositories/
│   └── settings_repository_test.dart
├── services/
│   └── accessibility_service_test.dart
└── widgets/
    └── permission_card_test.dart
```

### What to Test

| Layer | What | How |
|---|---|---|
| **ViewModel** | State transitions, method behavior, error handling | Unit tests with mocked dependencies |
| **Repository** | Data persistence and retrieval | Unit tests with mocked SharedPreferences |
| **Service** | Platform channel calls | Unit tests with mocked MethodChannel |
| **Widget** | Renders correctly for given state | Widget tests with mocked ViewModel |
| **Accessibility Service** | Detection accuracy | Manual tests + adb-based validation |

### Rules

- Every ViewModel must have corresponding unit tests.
- Mock all external dependencies using `Mockito` (`mockito` + `build_runner`).
- Test both success and error paths.
- Tests must be independent — no shared mutable state between test cases.
- Run tests before every commit: `flutter test`.

---

## 7. Platform Channel Contract

### Channel Name

```
com.focustime/accessibility
```

### Methods

| Method | Direction | Parameters | Returns |
|---|---|---|---|
| `isAccessibilityEnabled` | Flutter → Android | — | `bool` |
| `openAccessibilitySettings` | Flutter → Android | — | `bool` |
| `requestBatteryOptimization` | Flutter → Android | — | `bool` |
| `isIgnoringBatteryOptimizations` | Flutter → Android | — | `bool` |
| `getBlockedCount` | Flutter → Android | — | `int` |
| `setMonitoredApps` | Flutter → Android | `List<String>` package names | `bool` |
| `onBlockEvent` | Android → Flutter | `{package: String, timestamp: int}` | — |

### Rules

- All MethodChannel calls must be wrapped in a Dart service class (e.g., `AccessibilityService`).
- Never call `MethodChannel` directly from a ViewModel or View.
- Handle `PlatformException` in the service layer, not in ViewModels.

---

## 8. Git Workflow

### Branch Naming

```
feature/<short-description>    # New features
fix/<short-description>        # Bug fixes
refactor/<short-description>   # Code improvements
```

### Commit Messages

Follow conventional commits:

```
feat: add accessibility service detection logic
fix: prevent double back-press on cooldown
refactor: extract node tree scanner from service
test: add dashboard viewmodel unit tests
docs: update phase 2 changelog
```

### Pre-Commit Checklist

- [ ] `flutter analyze` passes with no issues.
- [ ] `flutter test` passes.
- [ ] No `print()` statements in committed code.
- [ ] New code follows MVVM structure.
- [ ] Dependencies injected via constructor.

---

## 9. Documentation Requirements

Every implementation phase must produce a summary document in `docs/`:

```
docs/
├── phase_01_project_setup.md
├── phase_02_accessibility_service.md
├── phase_03_method_channel.md
├── phase_04_permission_flow.md
├── phase_05_dashboard_ui.md
├── phase_06_background_persistence.md
├── phase_07_settings_and_config.md
└── phase_08_testing.md
```

Each phase doc must include:

1. **Summary** — What was implemented.
2. **Files Changed** — List of added/modified files.
3. **Key Decisions** — Any architectural choices made.
4. **Known Issues** — Limitations or TODO items.

---

## 10. Scalability Considerations

- **Remote keyword updates:** Store detection keywords in SharedPreferences so they can be updated without an app release.
- **Plugin extraction:** If the Accessibility Service logic grows complex, consider extracting it into a standalone Flutter plugin package.
- **Feature flags:** Use a simple config map to enable/disable features (e.g., TikTok blocking, toast notifications).
- **Localization:** Use Flutter's `intl` package from the start for all user-facing strings.
- **Database upgrade path:** If block logs grow, migrate from SharedPreferences to SQLite (`sqflite` or `drift`).
