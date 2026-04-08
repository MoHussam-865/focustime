import 'package:get_it/get_it.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../repositories/settings_repository.dart';
import '../services/accessibility_service.dart';
import '../viewmodels/dashboard_viewmodel.dart';
import '../viewmodels/onboarding_viewmodel.dart';
import '../viewmodels/settings_viewmodel.dart';

final getIt = GetIt.instance;

Future<void> setupDependencies() async {
  // External
  final prefs = await SharedPreferences.getInstance();
  getIt.registerSingleton<SharedPreferences>(prefs);

  // Services
  getIt.registerLazySingleton<AccessibilityService>(
    () => AccessibilityService(),
  );

  // Repositories
  getIt.registerLazySingleton<SettingsRepository>(
    () => SettingsRepositoryImpl(getIt<SharedPreferences>()),
  );

  // ViewModels
  getIt.registerFactory<OnboardingViewModel>(
    () => OnboardingViewModel(
      accessibilityService: getIt<AccessibilityService>(),
      settingsRepository: getIt<SettingsRepository>(),
    ),
  );

  getIt.registerFactory<DashboardViewModel>(
    () => DashboardViewModel(
      accessibilityService: getIt<AccessibilityService>(),
      settingsRepository: getIt<SettingsRepository>(),
    ),
  );

  getIt.registerFactory<SettingsViewModel>(
    () => SettingsViewModel(
      accessibilityService: getIt<AccessibilityService>(),
      settingsRepository: getIt<SettingsRepository>(),
    ),
  );
}
