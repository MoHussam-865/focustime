import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class AccessibilityService {
  static const _channel = MethodChannel('com.focustime/accessibility');

  Future<bool> isEnabled() async {
    try {
      final result = await _channel.invokeMethod('isAccessibilityEnabled');
      debugPrint(
        'FocusTime: isAccessibilityEnabled raw result=$result (${result.runtimeType})',
      );
      if (result is bool) return result;
      return false;
    } catch (e) {
      debugPrint('FocusTime: isEnabled error: $e');
      return false;
    }
  }

  Future<bool> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod<bool>('openAccessibilitySettings');
      return true;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> isIgnoringBatteryOptimizations() async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'isIgnoringBatteryOptimizations',
      );
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> requestBatteryOptimization() async {
    try {
      await _channel.invokeMethod<bool>('requestBatteryOptimization');
      return true;
    } on PlatformException {
      return false;
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

  Future<bool> setMonitoredApps(List<String> packageNames) async {
    try {
      final result = await _channel.invokeMethod<bool>('setMonitoredApps', {
        'packages': packageNames,
      });
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> setCooldown(int cooldownMs) async {
    if (cooldownMs < 0) {
      throw ArgumentError.value(
        cooldownMs,
        'cooldownMs',
        'must be non-negative',
      );
    }
    try {
      final result = await _channel.invokeMethod<bool>('setCooldown', {
        'cooldownMs': cooldownMs,
      });
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> hasNotificationPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'hasNotificationPermission',
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<bool> requestNotificationPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'requestNotificationPermission',
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<bool> setPornBlockEnabled(bool enabled) async {
    try {
      final result = await _channel.invokeMethod<bool>('setPornBlockEnabled', {
        'enabled': enabled,
      });
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }
}
