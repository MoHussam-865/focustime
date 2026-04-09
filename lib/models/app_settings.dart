class AppSettings {
  final int cooldownMs;
  final bool showToastOnBlock;
  final bool blockLogEnabled;
  final bool pornBlockEnabled;
  final bool aiNsfwScanEnabled;

  const AppSettings({
    this.cooldownMs = 2000,
    this.showToastOnBlock = true,
    this.blockLogEnabled = true,
    this.pornBlockEnabled = true,
    this.aiNsfwScanEnabled = false,
  });

  AppSettings copyWith({
    int? cooldownMs,
    bool? showToastOnBlock,
    bool? blockLogEnabled,
    bool? pornBlockEnabled,
    bool? aiNsfwScanEnabled,
  }) {
    return AppSettings(
      cooldownMs: cooldownMs ?? this.cooldownMs,
      showToastOnBlock: showToastOnBlock ?? this.showToastOnBlock,
      blockLogEnabled: blockLogEnabled ?? this.blockLogEnabled,
      pornBlockEnabled: pornBlockEnabled ?? this.pornBlockEnabled,
      aiNsfwScanEnabled: aiNsfwScanEnabled ?? this.aiNsfwScanEnabled,
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
          aiNsfwScanEnabled == other.aiNsfwScanEnabled;

  @override
  int get hashCode => Object.hash(
    cooldownMs,
    showToastOnBlock,
    blockLogEnabled,
    pornBlockEnabled,
    aiNsfwScanEnabled,
  );
}
