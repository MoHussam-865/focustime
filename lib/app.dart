import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'di/service_locator.dart';
import 'repositories/settings_repository.dart';
import 'viewmodels/onboarding_viewmodel.dart';
import 'views/onboarding/onboarding_screen.dart';
import 'views/dashboard/dashboard_screen.dart';

class FocusTimeApp extends StatelessWidget {
  const FocusTimeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FocusTime',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6C63FF),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6C63FF),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const _EntryPoint(),
    );
  }
}

class _EntryPoint extends StatefulWidget {
  const _EntryPoint();

  @override
  State<_EntryPoint> createState() => _EntryPointState();
}

class _EntryPointState extends State<_EntryPoint> {
  bool? _onboardingCompleted;

  @override
  void initState() {
    super.initState();
    _checkOnboarding();
  }

  Future<void> _checkOnboarding() async {
    final repo = getIt<SettingsRepository>();
    final completed = await repo.isOnboardingCompleted();
    setState(() => _onboardingCompleted = completed);
  }

  @override
  Widget build(BuildContext context) {
    if (_onboardingCompleted == null) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    if (_onboardingCompleted!) {
      return const DashboardScreen();
    }

    return ChangeNotifierProvider(
      create: (_) => getIt<OnboardingViewModel>()..checkPermissions(),
      child: OnboardingScreen(
        onComplete: () {
          setState(() => _onboardingCompleted = true);
        },
      ),
    );
  }
}
