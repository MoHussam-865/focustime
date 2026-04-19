import 'package:flutter/material.dart';

/// Shows the prominent disclosure dialog for AccessibilityService API usage.
///
/// Returns `true` if the user gave affirmative consent ("I Agree"),
/// or `false` if the user declined, tapped outside, or pressed Back.
Future<bool> showAccessibilityDisclosureDialog(BuildContext context) async {
  final result = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (context) => const _AccessibilityDisclosureDialog(),
  );
  // If dialog was dismissed without a result (should not happen with
  // barrierDismissible: false, but guard anyway), treat as decline.
  return result ?? false;
}

class _AccessibilityDisclosureDialog extends StatelessWidget {
  const _AccessibilityDisclosureDialog();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    // PopScope with canPop: false ensures hardware Back does NOT dismiss
    // the dialog automatically. We handle it manually as a "Decline".
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) {
          Navigator.of(context).pop(false);
        }
      },
      child: AlertDialog(
        title: Row(
          children: [
            Icon(
              Icons.accessibility_new_rounded,
              color: theme.colorScheme.primary,
            ),
            const SizedBox(width: 12),
            const Expanded(child: Text('Accessibility Service Disclosure')),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'This app needs to use the AccessibilityService API to detect '
                'when short-form video content (such as Reels, Shorts, or '
                'TikTok videos) is opened in other apps, so it can '
                'automatically block that content and help you stay focused.',
                style: theme.textTheme.bodyMedium?.copyWith(height: 1.5),
              ),
              const SizedBox(height: 16),
              Text(
                'We do not collect or share any personal data through this '
                'service.',
                style: theme.textTheme.bodyMedium?.copyWith(
                  fontWeight: FontWeight.w600,
                  height: 1.5,
                ),
              ),
              const SizedBox(height: 16),
              Text(
                'By tapping "I Agree" you consent to enabling the '
                'Accessibility Service. You will be taken to your device\'s '
                'Accessibility settings to complete the setup.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Decline'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('I Agree'),
          ),
        ],
      ),
    );
  }
}
