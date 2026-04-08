import 'package:flutter/foundation.dart';

import '../models/app_settings.dart';
import '../models/blocked_app.dart';
import '../repositories/settings_repository.dart';
import '../services/accessibility_service.dart';

class SettingsViewModel extends ChangeNotifier {
  final AccessibilityService _accessibilityService;
  final SettingsRepository _settingsRepository;

  AppSettings _settings = const AppSettings();
  AppSettings get settings => _settings;

  List<BlockedApp> _blockedApps = [];
  List<BlockedApp> get blockedApps => _blockedApps;

  bool _isLoading = false;
  bool get isLoading => _isLoading;

  SettingsViewModel({
    required AccessibilityService accessibilityService,
    required SettingsRepository settingsRepository,
  }) : _accessibilityService = accessibilityService,
       _settingsRepository = settingsRepository;

  Future<void> loadSettings() async {
    _isLoading = true;
    notifyListeners();

    try {
      _settings = await _settingsRepository.getSettings();
      _blockedApps = await _settingsRepository.getBlockedApps();
      // Sync the porn block setting to native side on load
      await _accessibilityService.setPornBlockEnabled(_settings.pornBlockEnabled);
    } catch (_) {
      _settings = const AppSettings();
      _blockedApps = BlockedApp.defaults();
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> updateCooldown(int cooldownMs) async {
    _settings = _settings.copyWith(cooldownMs: cooldownMs);
    notifyListeners();

    await _settingsRepository.saveSettings(_settings);
    await _accessibilityService.setCooldown(cooldownMs);
  }

  Future<void> toggleToast(bool value) async {
    _settings = _settings.copyWith(showToastOnBlock: value);
    notifyListeners();
    await _settingsRepository.saveSettings(_settings);
  }

  Future<void> toggleBlockLog(bool value) async {
    _settings = _settings.copyWith(blockLogEnabled: value);
    notifyListeners();
    await _settingsRepository.saveSettings(_settings);
  }

  Future<void> togglePornBlock(bool value) async {
    _settings = _settings.copyWith(pornBlockEnabled: value);
    notifyListeners();
    await _settingsRepository.saveSettings(_settings);
    await _accessibilityService.setPornBlockEnabled(value);
  }

  Future<void> toggleApp(int index) async {
    if (index < 0 || index >= _blockedApps.length) return;

    final app = _blockedApps[index];
    // Create a new list so Flutter detects the change
    _blockedApps = List<BlockedApp>.from(_blockedApps);
    _blockedApps[index] = app.copyWith(isEnabled: !app.isEnabled);
    notifyListeners();

    try {
      await _settingsRepository.saveBlockedApps(_blockedApps);

      final enabledPackages = _blockedApps
          .where((a) => a.isEnabled)
          .map((a) => a.packageName)
          .toList();
      await _accessibilityService.setMonitoredApps(enabledPackages);
    } catch (e) {
      // Rollback on failure
      _blockedApps = List<BlockedApp>.from(_blockedApps);
      _blockedApps[index] = app;
      notifyListeners();
    }
  }
}
