class AppSettings {
  final int cooldownMs;
  final bool showToastOnBlock;
  final bool blockLogEnabled;

  const AppSettings({
    this.cooldownMs = 2000,
    this.showToastOnBlock = true,
    this.blockLogEnabled = true,
  });

  AppSettings copyWith({
    int? cooldownMs,
    bool? showToastOnBlock,
    bool? blockLogEnabled,
  }) {
    return AppSettings(
      cooldownMs: cooldownMs ?? this.cooldownMs,
      showToastOnBlock: showToastOnBlock ?? this.showToastOnBlock,
      blockLogEnabled: blockLogEnabled ?? this.blockLogEnabled,
    );
  }
}
