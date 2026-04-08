import 'package:flutter_test/flutter_test.dart';
import 'package:focustime/models/blocked_app.dart';

void main() {
  group('BlockedApp', () {
    test('defaults returns all supported apps', () {
      final defaults = BlockedApp.defaults();
      expect(defaults.length, 6);
      expect(
        defaults.map((a) => a.packageName),
        contains('com.google.android.youtube'),
      );
    });

    test('copyWith toggles isEnabled', () {
      const app = BlockedApp(
        packageName: 'com.test',
        displayName: 'Test',
        isEnabled: true,
      );
      final toggled = app.copyWith(isEnabled: false);
      expect(toggled.isEnabled, false);
      expect(toggled.packageName, 'com.test');
    });

    test('toJson and fromJson round-trip', () {
      const app = BlockedApp(
        packageName: 'com.test',
        displayName: 'Test App',
        isEnabled: false,
      );
      final json = app.toJson();
      final restored = BlockedApp.fromJson(json);
      expect(restored.packageName, app.packageName);
      expect(restored.displayName, app.displayName);
      expect(restored.isEnabled, app.isEnabled);
    });
  });
}
