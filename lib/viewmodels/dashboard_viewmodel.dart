import 'package:flutter/foundation.dart';

import '../models/blocked_app.dart';
import '../repositories/settings_repository.dart';
import '../services/accessibility_service.dart';

class DashboardViewModel extends ChangeNotifier {
  final AccessibilityService _accessibilityService;
  final SettingsRepository _settingsRepository;

  bool _isServiceRunning = false;
  bool get isServiceRunning => _isServiceRunning;

  int _blockedCount = 0;
  int get blockedCount => _blockedCount;

  List<BlockedApp> _blockedApps = [];
  List<BlockedApp> get blockedApps => _blockedApps;

  bool _isLoading = false;
  bool get isLoading => _isLoading;

  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  DashboardViewModel({
    required AccessibilityService accessibilityService,
    required SettingsRepository settingsRepository,
  })  : _accessibilityService = accessibilityService,
        _settingsRepository = settingsRepository;

  Future<void> loadData() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      _isServiceRunning = await _accessibilityService.isEnabled();
      _blockedCount = await _accessibilityService.getBlockedCount();
      _blockedApps = await _settingsRepository.getBlockedApps();
    } catch (e) {
      _errorMessage = 'Failed to load data';
    }

    _isLoading = false;
    notifyListeners();
  }

  Future<void> toggleApp(int index) async {
    final app = _blockedApps[index];
    _blockedApps[index] = app.copyWith(isEnabled: !app.isEnabled);
    notifyListeners();

    await _settingsRepository.saveBlockedApps(_blockedApps);

    final enabledPackages = _blockedApps
        .where((a) => a.isEnabled)
        .map((a) => a.packageName)
        .toList();
    await _accessibilityService.setMonitoredApps(enabledPackages);
  }

  Future<void> openAccessibilitySettings() async {
    await _accessibilityService.openAccessibilitySettings();
  }
}
