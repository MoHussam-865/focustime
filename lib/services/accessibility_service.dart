import 'package:flutter/services.dart';

class AccessibilityService {
  static const _channel = MethodChannel('com.focustime/accessibility');

  Future<bool> isEnabled() async {
    try {
      final result = await _channel.invokeMethod<bool>('isAccessibilityEnabled');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<void> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod<bool>('openAccessibilitySettings');
    } on PlatformException {
      // Settings could not be opened
    }
  }

  Future<bool> isIgnoringBatteryOptimizations() async {
    try {
      final result =
          await _channel.invokeMethod<bool>('isIgnoringBatteryOptimizations');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<void> requestBatteryOptimization() async {
    try {
      await _channel.invokeMethod<bool>('requestBatteryOptimization');
    } on PlatformException {
      // Request could not be made
    }
  }

  Future<int> getBlockedCount() async {
    try {
      final result = await _channel.invokeMethod<int>('getBlockedCount');
      return result ?? 0;
    } on PlatformException {
      return 0;
    }
  }

  Future<void> setMonitoredApps(List<String> packageNames) async {
    try {
      await _channel.invokeMethod<bool>(
        'setMonitoredApps',
        {'packages': packageNames},
      );
    } on PlatformException {
      // Could not update monitored apps
    }
  }

  Future<void> setCooldown(int cooldownMs) async {
    try {
      await _channel.invokeMethod<bool>(
        'setCooldown',
        {'cooldownMs': cooldownMs},
      );
    } on PlatformException {
      // Could not update cooldown
    }
  }
}
