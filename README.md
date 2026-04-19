# FocusTime

FocusTime is an Android application built with Flutter and Kotlin designed to help users minimize distractions by blocking short-form video content and optionally scanning for NSFW content.

## Features

- **Short-form Content Blocking**: Automatically detects and blocks short-form videos across major social media apps using the Android Accessibility Service.
- **Supported Apps**:
  - YouTube (Shorts)
  - Instagram (Reels)
  - TikTok
  - Facebook (Reels)
  - Snapchat (Spotlight)
- **AI NSFW Image Scanner**: Uses an on-device 18-class YOLOv8 NudeNet model (640x640) with dynamic square tiling to detect and block explicit content in real-time without sending data to a server.
- **Configurable Blocking Levels**: Choose between 3 tiers of AI blocking:
  - **Porn**: Blocks explicit graphical content.
  - **Nude**: Blocks nudity and explicit content.
  - **Female**: Blocks female presence in addition to nude and explicit content.
- **Background Persistence**: Runs reliably in the background using a Foreground Service and Battery Optimization exemptions.
- **Privacy First**: All screen reading and AI processing is done entirely on-device. No screen content or personal data is collected, stored, or transmitted.

## Architecture

- **Frontend**: Flutter using MVVM (Model-View-ViewModel) architecture.
- **Dependency Injection**: Managed via `get_it`.
- **Native Android**: Kotlin-based Accessibility Service for screen monitoring and an on-device TensorFlow Lite model for AI tracking.
- **Data Bridge**: Flutter and Native Kotlin communicate via `MethodChannel` and a shared `SharedPreferences` instance to sync settings (like block counts, cooldowns, and AI blocking levels) instantly.

## Getting Started

1. Clone the repository.
2. Run `flutter pub get` to install dependencies.
3. Build and run on an Android device (minSdk 24 required):
   ```bash
   flutter run
   ```

Note: The app requires Accessibility Service, Battery Optimization, and Notification permissions to function correctly. These are requested via an onboarding flow.
