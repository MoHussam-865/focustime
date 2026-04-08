class BlockEvent {
  final String packageName;
  final DateTime timestamp;

  const BlockEvent({required this.packageName, required this.timestamp});

  Map<String, dynamic> toJson() => {
    'packageName': packageName,
    'timestamp': timestamp.millisecondsSinceEpoch,
  };

  factory BlockEvent.fromJson(Map<String, dynamic> json) => BlockEvent(
    packageName: json['packageName'] as String,
    timestamp: DateTime.fromMillisecondsSinceEpoch(json['timestamp'] as int),
  );
}
