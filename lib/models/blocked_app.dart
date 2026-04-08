class BlockedApp {
  final String packageName;
  final String displayName;
  final bool isEnabled;

  const BlockedApp({
    required this.packageName,
    required this.displayName,
    this.isEnabled = true,
  });

  BlockedApp copyWith({bool? isEnabled}) {
    return BlockedApp(
      packageName: packageName,
      displayName: displayName,
      isEnabled: isEnabled ?? this.isEnabled,
    );
  }

  Map<String, dynamic> toJson() => {
    'packageName': packageName,
    'displayName': displayName,
    'isEnabled': isEnabled,
  };

  factory BlockedApp.fromJson(Map<String, dynamic> json) => BlockedApp(
    packageName: json['packageName'] as String,
    displayName: json['displayName'] as String,
    isEnabled: json['isEnabled'] as bool? ?? true,
  );

  static List<BlockedApp> defaults() => const [
    BlockedApp(
      packageName: 'com.google.android.youtube',
      displayName: 'YouTube Shorts',
    ),
    BlockedApp(
      packageName: 'com.instagram.android',
      displayName: 'Instagram Reels',
    ),
    BlockedApp(packageName: 'com.zhiliaoapp.musically', displayName: 'TikTok'),
    BlockedApp(
      packageName: 'com.ss.android.ugc.trill',
      displayName: 'TikTok (Alt)',
    ),
    BlockedApp(
      packageName: 'com.facebook.katana',
      displayName: 'Facebook Reels',
    ),
    BlockedApp(
      packageName: 'com.snapchat.android',
      displayName: 'Snapchat Spotlight',
    ),
  ];
}
