import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../di/service_locator.dart';
import '../../viewmodels/settings_viewmodel.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => getIt<SettingsViewModel>()..loadSettings(),
      child: const _SettingsBody(),
    );
  }
}

class _SettingsBody extends StatelessWidget {
  const _SettingsBody();

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<SettingsViewModel>();
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: vm.isLoading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                // Cooldown
                Text(
                  'Block Cooldown',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Time to wait before blocking again: ${(vm.settings.cooldownMs / 1000).toStringAsFixed(1)}s',
                  style: theme.textTheme.bodyMedium,
                ),
                Slider(
                  value: vm.settings.cooldownMs.toDouble(),
                  min: 500,
                  max: 5000,
                  divisions: 9,
                  label:
                      '${(vm.settings.cooldownMs / 1000).toStringAsFixed(1)}s',
                  onChanged: (value) => vm.updateCooldown(value.round()),
                ),
                const Divider(),

                // Toggles
                SwitchListTile(
                  title: const Text('Show toast on block'),
                  subtitle: const Text(
                    'Display a message when content is blocked',
                  ),
                  value: vm.settings.showToastOnBlock,
                  onChanged: vm.toggleToast,
                ),
                SwitchListTile(
                  title: const Text('Block log'),
                  subtitle: const Text('Keep a history of blocked content'),
                  value: vm.settings.blockLogEnabled,
                  onChanged: vm.toggleBlockLog,
                ),
                const Divider(),

                // Content Filtering
                const SizedBox(height: 8),
                Text(
                  'Content Filtering',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                SwitchListTile(
                  title: const Text('Block adult content'),
                  subtitle: const Text(
                    'Automatically block pornographic websites and apps',
                  ),
                  value: vm.settings.pornBlockEnabled,
                  onChanged: vm.togglePornBlock,
                ),
                SwitchListTile(
                  title: const Text('AI image scanner'),
                  subtitle: Text(
                    vm.settings.pornBlockEnabled
                        ? 'Use AI to detect and block explicit images (Android 11+)'
                        : 'Enable "Block adult content" first',
                  ),
                  value: vm.settings.aiNsfwScanEnabled,
                  onChanged: vm.settings.pornBlockEnabled
                      ? vm.toggleAiNsfwScan
                      : null,
                ),
                const Divider(),

                // Monitored apps
                const SizedBox(height: 8),
                Text(
                  'Monitored Apps',
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 8),
                ...List.generate(vm.blockedApps.length, (index) {
                  final app = vm.blockedApps[index];
                  return SwitchListTile(
                    title: Text(app.displayName),
                    subtitle: Text(
                      app.packageName,
                      style: theme.textTheme.bodySmall,
                    ),
                    value: app.isEnabled,
                    onChanged: (_) => vm.toggleApp(index),
                  );
                }),
              ],
            ),
    );
  }
}
