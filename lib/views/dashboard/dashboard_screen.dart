import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../di/service_locator.dart';
import '../../viewmodels/dashboard_viewmodel.dart';
import '../settings/settings_screen.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => getIt<DashboardViewModel>()..loadData(),
      child: const _DashboardBody(),
    );
  }
}

class _DashboardBody extends StatefulWidget {
  const _DashboardBody();

  @override
  State<_DashboardBody> createState() => _DashboardBodyState();
}

class _DashboardBodyState extends State<_DashboardBody>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      context.read<DashboardViewModel>().loadData();
    }
  }

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<DashboardViewModel>();
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('FocusTime'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const SettingsScreen()),
            ),
          ),
        ],
      ),
      body: vm.isLoading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: vm.loadData,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  // Status card
                  Card(
                    color: vm.isServiceRunning
                        ? Colors.green.shade50
                        : Colors.red.shade50,
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        children: [
                          Icon(
                            vm.isServiceRunning
                                ? Icons.shield
                                : Icons.shield_outlined,
                            size: 48,
                            color: vm.isServiceRunning
                                ? Colors.green.shade700
                                : Colors.red.shade700,
                          ),
                          const SizedBox(height: 12),
                          Text(
                            vm.isServiceRunning
                                ? 'Protection Active'
                                : 'Protection Disabled',
                            style: theme.textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                              color: vm.isServiceRunning
                                  ? Colors.green.shade700
                                  : Colors.red.shade700,
                            ),
                          ),
                          if (!vm.isServiceRunning) ...[
                            const SizedBox(height: 12),
                            FilledButton(
                              onPressed: vm.openAccessibilitySettings,
                              child: const Text('Enable Accessibility Service'),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // Stats card
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.block, color: theme.colorScheme.primary),
                          const SizedBox(width: 12),
                          Text(
                            '${vm.blockedCount}',
                            style: theme.textTheme.headlineLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text(
                            'reels blocked',
                            style: theme.textTheme.bodyLarge,
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 16),

                  // Monitored apps
                  Text(
                    'Monitored Apps',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  ...List.generate(vm.blockedApps.length, (index) {
                    final app = vm.blockedApps[index];
                    return Card(
                      child: SwitchListTile(
                        title: Text(app.displayName),
                        subtitle: Text(
                          app.packageName,
                          style: theme.textTheme.bodySmall,
                        ),
                        value: app.isEnabled,
                        onChanged: (_) => vm.toggleApp(index),
                      ),
                    );
                  }),

                  if (vm.errorMessage != null) ...[
                    const SizedBox(height: 16),
                    Text(
                      vm.errorMessage!,
                      style: TextStyle(color: theme.colorScheme.error),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ],
              ),
            ),
    );
  }
}
