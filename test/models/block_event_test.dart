import 'package:flutter_test/flutter_test.dart';
import 'package:focustime/models/block_event.dart';

void main() {
  group('BlockEvent', () {
    test('toJson and fromJson round-trip', () {
      final event = BlockEvent(
        packageName: 'com.google.android.youtube',
        timestamp: DateTime(2026, 4, 8, 12, 0),
      );
      final json = event.toJson();
      final restored = BlockEvent.fromJson(json);
      expect(restored.packageName, event.packageName);
      expect(restored.timestamp, event.timestamp);
    });
  });
}
