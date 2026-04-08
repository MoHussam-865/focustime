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
      final result =
          await _channel.invokeMethod<bool>('isIgnoringBatteryOptimizations');
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
      final result = await _channel.invokeMethod<bool>(
        'setMonitoredApps',
        {'packages': packageNames},
      );
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
      final result = await _channel.invokeMethod<bool>(
        'setCooldown',
        {'cooldownMs': cooldownMs},
      );
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }
}
