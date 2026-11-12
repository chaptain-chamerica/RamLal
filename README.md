# Likkho — Android Jetpack Compose Application

A private, secure, and encrypted diary and rich note-taking app crafted with Cormorant typography and refined editorial aesthetics.

## Features

- **Encrypted Diary Vault & Covert Decoy Vault:** Secure passcode lock with attempt tracking, cooldown protection (5th failure = 30s, 6th+ = 30s × attempts), and dual-PIN vault partitioning (Primary Vault and Decoy Vault).
- **Workspace Canvas & Rich Text Editing:** Interactive diary editor with title, timestamp, word count, customizable formatting controls, mathematical symbols palette, and canvas media attachments.
- **Studios & Utilities:** Image Studio, Audio Studio, Profile Studio (PFP), and Word Analysis tooling.
- **Home Hub & Organization:** Instant Lock shortcut, quick search filtering, diary entry pinning, 3-dot management menu, and floating status banners.
- **Backup & Vault Management:** Passphrase-protected backup vault creation, restoration, selective diary recovery, and archive exports.
- **Customizable Appearance:** Wikipedia-inspired light and dark themes, typographic scales, fullscreen toggle, and status bar theming.

## Architecture

- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Architecture Pattern:** MVVM (`AppViewModel`, `FeatureHooks`)
- **Application ID & Package:** `com.likkho.app`
- **Build System:** Gradle (Kotlin DSL), Android SDK 34, AGP, Java 17/21

## Directory Structure

```
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/likkho/diary/
│       │   ├── AppViewModel.kt       # State management & business logic
│       │   ├── BackupScreens.kt      # Backup, restore & vault explorer
│       │   ├── Common.kt             # Shared UI widgets, buttons & text fields
│       │   ├── Dialogs.kt            # Unlock, delete, export & status dialogs
│       │   ├── FeatureHooks.kt       # Interface & implementation hooks
│       │   ├── HomeScreen.kt         # Main diary list, search & header
│       │   ├── LegalContent.kt       # Privacy policy & terms
│       │   ├── MainActivity.kt       # Lifecycle, security flags & window handling
│       │   ├── MathSymbols.kt        # LaTeX symbol picker
│       │   ├── Models.kt             # Data classes & domain models
│       │   ├── PasscodeScreen.kt     # PIN pad, cooldown & biometric entry
│       │   ├── RichToolbar.kt        # Editor toolbar & formatting popovers
│       │   ├── SampleData.kt         # Initial sample entries
│       │   ├── SettingsScreen.kt     # Security, mic, passwords & settings
│       │   ├── Studios.kt            # Media studios & analysis
│       │   ├── Theme.kt              # Wikipedia colors & typography
│       │   └── Workspace.kt          # Editor canvas & entry writer
│       └── res/
│           ├── drawable/             # Adaptive icon assets
│           ├── mipmap-*/             # Launcher icons (standard & round)
│           └── values/               # strings.xml & themes.xml
├── build.gradle.kts
├── metadata.json
└── settings.gradle.kts
```
