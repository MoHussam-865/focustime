import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:focustime/services/accessibility_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late AccessibilityService service;
  late List<MethodCall> log;

  setUp(() {
    service = AccessibilityService();
    log = [];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('com.focustime/accessibility'),
          (MethodCall methodCall) async {
            log.add(methodCall);
            switch (methodCall.method) {
              case 'isAccessibilityEnabled':
                return true;
              case 'isIgnoringBatteryOptimizations':
                return false;
              case 'getBlockedCount':
                return 42;
              case 'openAccessibilitySettings':
              case 'requestBatteryOptimization':
              case 'setMonitoredApps':
              case 'setCooldown':
                return true;
              default:
                return null;
            }
          },
        );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('com.focustime/accessibility'),
          null,
        );
  });

  group('AccessibilityService', () {
    test('isEnabled returns true when service is enabled', () async {
      final result = await service.isEnabled();
      expect(result, true);
      expect(log.last.method, 'isAccessibilityEnabled');
    });

    test('isIgnoringBatteryOptimizations returns false', () async {
      final result = await service.isIgnoringBatteryOptimizations();
      expect(result, false);
      expect(log.last.method, 'isIgnoringBatteryOptimizations');
    });

    test('getBlockedCount returns count from native', () async {
      final result = await service.getBlockedCount();
      expect(result, 42);
      expect(log.last.method, 'getBlockedCount');
    });

    test('openAccessibilitySettings returns true on success', () async {
      final result = await service.openAccessibilitySettings();
      expect(result, true);
      expect(log.last.method, 'openAccessibilitySettings');
    });

    test('openAccessibilitySettings returns false on PlatformException',
        () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel('com.focustime/accessibility'),
        (MethodCall methodCall) async {
          throw PlatformException(code: 'ERROR');
        },
      );

      final result = await service.openAccessibilitySettings();
      expect(result, false);
    });

    test('requestBatteryOptimization returns true on success', () async {
      final result = await service.requestBatteryOptimization();
      expect(result, true);
      expect(log.last.method, 'requestBatteryOptimization');
    });

    test('setMonitoredApps returns true on success', () async {
      final result =
          await service.setMonitoredApps(['com.google.android.youtube']);
      expect(result, true);
      expect(log.last.method, 'setMonitoredApps');
      expect(log.last.arguments, {
        'packages': ['com.google.android.youtube'],
      });
    });

    test('setMonitoredApps returns false on PlatformException', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel('com.focustime/accessibility'),
        (MethodCall methodCall) async {
          throw PlatformException(code: 'ERROR');
        },
      );

      final result =
          await service.setMonitoredApps(['com.google.android.youtube']);
      expect(result, false);
    });

    test('setCooldown sends cooldown value', () async {
      final result = await service.setCooldown(3000);
      expect(result, true);
      expect(log.last.method, 'setCooldown');
      expect(log.last.arguments, {'cooldownMs': 3000});
    });

    test('setCooldown throws ArgumentError for negative value', () async {
      expect(() => service.setCooldown(-1), throwsArgumentError);
    });

    test('isEnabled returns false on PlatformException', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.focustime/accessibility'),
            (MethodCall methodCall) async {
              throw PlatformException(code: 'ERROR');
            },
          );

      final result = await service.isEnabled();
      expect(result, false);
    });

    test('getBlockedCount returns 0 on PlatformException', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('com.focustime/accessibility'),
            (MethodCall methodCall) async {
              throw PlatformException(code: 'ERROR');
            },
          );

      final result = await service.getBlockedCount();
      expect(result, 0);
    });
  });
}
