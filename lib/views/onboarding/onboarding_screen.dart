import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../viewmodels/onboarding_viewmodel.dart';

class OnboardingScreen extends StatelessWidget {
  final VoidCallback onComplete;

  const OnboardingScreen({super.key, required this.onComplete});

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<OnboardingViewModel>();
    final theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 48),
              Icon(
                Icons.shield_outlined,
                size: 80,
                color: theme.colorScheme.primary,
              ),
              const SizedBox(height: 24),
              Text(
                'Welcome to FocusTime',
                style: theme.textTheme.headlineMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 12),
              Text(
                'We need a few permissions to block reels and shorts for you.',
                style: theme.textTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 48),
              _PermissionTile(
                icon: Icons.accessibility_new,
                title: 'Accessibility Service',
                subtitle: 'Required to detect and block reels/shorts',
                isEnabled: vm.isAccessibilityEnabled,
                onTap: () async {
                  await vm.openAccessibilitySettings();
                },
              ),
              const SizedBox(height: 16),
              _PermissionTile(
                icon: Icons.battery_saver,
                title: 'Battery Optimization',
                subtitle: 'Keeps the blocker running in the background',
                isEnabled: vm.isBatteryOptimizationDisabled,
                onTap: () async {
                  await vm.requestBatteryOptimization();
                },
              ),
              const Spacer(),
              if (vm.isLoading)
                const Center(child: CircularProgressIndicator())
              else ...[
                FilledButton.icon(
                  onPressed: () async {
                    await vm.checkPermissions();
                  },
                  icon: const Icon(Icons.refresh),
                  label: const Text('Refresh Status'),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: vm.allPermissionsGranted
                      ? () async {
                          try {
                            await vm.completeOnboarding();
                          } catch (_) {
                            // Continue even if persistence fails
                          }
                          onComplete();
                        }
                      : null,
                  child: const Text('Continue'),
                ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () async {
                    try {
                      await vm.completeOnboarding();
                    } catch (_) {
                      // Continue even if persistence fails
                    }
                    onComplete();
                  },
                  child: const Text('Skip for now'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _PermissionTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final bool isEnabled;
  final VoidCallback onTap;

  const _PermissionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.isEnabled,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: ListTile(
        leading: Icon(icon, color: theme.colorScheme.primary),
        title: Text(title),
        subtitle: Text(subtitle),
        trailing: isEnabled
            ? Icon(Icons.check_circle, color: Colors.green.shade600)
            : const Icon(Icons.circle_outlined, color: Colors.grey),
        onTap: isEnabled ? null : onTap,
      ),
    );
  }
}
