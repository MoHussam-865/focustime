import 'package:flutter_test/flutter_test.dart';
import 'package:focustime/models/app_settings.dart';

void main() {
  group('AppSettings', () {
    test('default values', () {
      const settings = AppSettings();
      expect(settings.cooldownMs, 2000);
      expect(settings.showToastOnBlock, true);
      expect(settings.blockLogEnabled, true);
    });

    test('copyWith updates only specified fields', () {
      const settings = AppSettings();
      final updated = settings.copyWith(cooldownMs: 3000);
      expect(updated.cooldownMs, 3000);
      expect(updated.showToastOnBlock, true);
      expect(updated.blockLogEnabled, true);
    });

    test('copyWith all fields', () {
      const settings = AppSettings();
      final updated = settings.copyWith(
        cooldownMs: 1000,
        showToastOnBlock: false,
        blockLogEnabled: false,
      );
      expect(updated.cooldownMs, 1000);
      expect(updated.showToastOnBlock, false);
      expect(updated.blockLogEnabled, false);
    });
  });
}
