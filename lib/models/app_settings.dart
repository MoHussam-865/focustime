class AppSettings {
  final int cooldownMs;
  final bool showToastOnBlock;
  final bool blockLogEnabled;
  final bool pornBlockEnabled;
  final bool aiNsfwScanEnabled;
  final String aiBlockingLevel; // "porn", "nude", or "female"

  const AppSettings({
    this.cooldownMs = 2000,
    this.showToastOnBlock = true,
    this.blockLogEnabled = true,
    this.pornBlockEnabled = true,
    this.aiNsfwScanEnabled = false,
    this.aiBlockingLevel = 'porn',
  });

  AppSettings copyWith({
    int? cooldownMs,
    bool? showToastOnBlock,
    bool? blockLogEnabled,
    bool? pornBlockEnabled,
    bool? aiNsfwScanEnabled,
    String? aiBlockingLevel,
  }) {
    return AppSettings(
      cooldownMs: cooldownMs ?? this.cooldownMs,
      showToastOnBlock: showToastOnBlock ?? this.showToastOnBlock,
      blockLogEnabled: blockLogEnabled ?? this.blockLogEnabled,
      pornBlockEnabled: pornBlockEnabled ?? this.pornBlockEnabled,
      aiNsfwScanEnabled: aiNsfwScanEnabled ?? this.aiNsfwScanEnabled,
      aiBlockingLevel: aiBlockingLevel ?? this.aiBlockingLevel,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is AppSettings &&
          runtimeType == other.runtimeType &&
          cooldownMs == other.cooldownMs &&
          showToastOnBlock == other.showToastOnBlock &&
          blockLogEnabled == other.blockLogEnabled &&
          pornBlockEnabled == other.pornBlockEnabled &&
          aiNsfwScanEnabled == other.aiNsfwScanEnabled &&
          aiBlockingLevel == other.aiBlockingLevel;

  @override
  int get hashCode => Object.hash(
    cooldownMs,
    showToastOnBlock,
    blockLogEnabled,
    pornBlockEnabled,
    aiNsfwScanEnabled,
    aiBlockingLevel,
  );
}
