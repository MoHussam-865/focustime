import 'package:flutter/foundation.dart';

import '../repositories/settings_repository.dart';
import '../services/accessibility_service.dart';

class OnboardingViewModel extends ChangeNotifier {
  final AccessibilityService _accessibilityService;
  final SettingsRepository _settingsRepository;

  bool _isAccessibilityEnabled = false;
  bool get isAccessibilityEnabled => _isAccessibilityEnabled;

  bool _isBatteryOptimized = false;
  bool get isBatteryOptimizationDisabled => _isBatteryOptimized;

  bool _isLoading = false;
  bool get isLoading => _isLoading;

  OnboardingViewModel({
    required AccessibilityService accessibilityService,
    required SettingsRepository settingsRepository,
  })  : _accessibilityService = accessibilityService,
        _settingsRepository = settingsRepository;

  Future<void> checkPermissions() async {
    _isLoading = true;
    notifyListeners();

    try {
      _isAccessibilityEnabled = await _accessibilityService.isEnabled();
      _isBatteryOptimized =
          await _accessibilityService.isIgnoringBatteryOptimizations();
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> openAccessibilitySettings() async {
    await _accessibilityService.openAccessibilitySettings();
    await checkPermissions();
  }

  Future<void> requestBatteryOptimization() async {
    await _accessibilityService.requestBatteryOptimization();
    await checkPermissions();
  }

  bool get allPermissionsGranted =>
      _isAccessibilityEnabled && _isBatteryOptimized;

  Future<void> completeOnboarding() async {
    await _settingsRepository.setOnboardingCompleted(true);
  }
}
