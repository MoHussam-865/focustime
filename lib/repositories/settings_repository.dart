import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_settings.dart';
import '../models/blocked_app.dart';

abstract class SettingsRepository {
  Future<List<BlockedApp>> getBlockedApps();
  Future<void> saveBlockedApps(List<BlockedApp> apps);
  Future<AppSettings> getSettings();
  Future<void> saveSettings(AppSettings settings);
  Future<bool> isOnboardingCompleted();
  Future<void> setOnboardingCompleted(bool completed);
}

class SettingsRepositoryImpl implements SettingsRepository {
  static const _keyBlockedApps = 'blocked_apps';
  static const _keyCooldownMs = 'cooldown_ms';
  static const _keyShowToast = 'show_toast';
  static const _keyBlockLog = 'block_log';
  static const _keyPornBlock = 'porn_block_enabled';
  static const _keyAiNsfwScan = 'ai_nsfw_scan_enabled';
  static const _keyAiBlockingLevel = 'ai_blocking_level';
  static const _keyOnboardingCompleted = 'onboarding_completed';

  final SharedPreferences _prefs;

  SettingsRepositoryImpl(this._prefs);

  @override
  Future<List<BlockedApp>> getBlockedApps() async {
    final jsonString = _prefs.getString(_keyBlockedApps);
    if (jsonString == null) {
      return BlockedApp.defaults();
    }
    try {
      final List<dynamic> jsonList = json.decode(jsonString) as List<dynamic>;
      return jsonList
          .map((e) => BlockedApp.fromJson(e as Map<String, dynamic>))
          .toList();
    } on FormatException {
      await _prefs.remove(_keyBlockedApps);
      return BlockedApp.defaults();
    } catch (_) {
      await _prefs.remove(_keyBlockedApps);
      return BlockedApp.defaults();
    }
  }

  @override
  Future<void> saveBlockedApps(List<BlockedApp> apps) async {
    final jsonString = json.encode(apps.map((e) => e.toJson()).toList());
    await _prefs.setString(_keyBlockedApps, jsonString);
  }

  @override
  Future<AppSettings> getSettings() async {
    return AppSettings(
      cooldownMs: _prefs.getInt(_keyCooldownMs) ?? 2000,
      showToastOnBlock: _prefs.getBool(_keyShowToast) ?? true,
      blockLogEnabled: _prefs.getBool(_keyBlockLog) ?? true,
      pornBlockEnabled: _prefs.getBool(_keyPornBlock) ?? true,
      aiNsfwScanEnabled: _prefs.getBool(_keyAiNsfwScan) ?? false,
      aiBlockingLevel: _prefs.getString(_keyAiBlockingLevel) ?? 'porn',
    );
  }

  @override
  Future<void> saveSettings(AppSettings settings) async {
    await _prefs.setInt(_keyCooldownMs, settings.cooldownMs);
    await _prefs.setBool(_keyShowToast, settings.showToastOnBlock);
    await _prefs.setBool(_keyBlockLog, settings.blockLogEnabled);
    await _prefs.setBool(_keyPornBlock, settings.pornBlockEnabled);
    await _prefs.setBool(_keyAiNsfwScan, settings.aiNsfwScanEnabled);
    await _prefs.setString(_keyAiBlockingLevel, settings.aiBlockingLevel);
  }

  @override
  Future<bool> isOnboardingCompleted() async {
    return _prefs.getBool(_keyOnboardingCompleted) ?? false;
  }

  @override
  Future<void> setOnboardingCompleted(bool completed) async {
    await _prefs.setBool(_keyOnboardingCompleted, completed);
  }
}
