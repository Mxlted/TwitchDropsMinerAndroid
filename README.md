# Twitch Drops Miner Android

Twitch Drops Miner Android is an unofficial Android Twitch Drops Miner app for tracking Twitch Drops campaigns, choosing game priorities, keeping eligible watch progress active, and claiming completed drops from a phone.

It is intended as a mobile Android alternative to desktop Twitch Drops mining tools for users who want to manage Twitch Drops, campaigns, game priorities, and claimable drops without leaving a desktop miner running.

## Features

* Twitch device-code login
* Campaign and drop tracking
* Game priority list
* Auto Mode when no priority games are selected
* Optional fallback from priority games to other eligible campaigns
* Foreground service for active mining
* Persistent notification while mining is running
* Activity and log screens
* Local logs with copy and clear actions
* Keep active screen mode for long sessions
* Secure local session storage
* Completed drop claim attempts from the app

## Current Status

This is an early Android version. The main app flow, local settings, foreground service, campaign views, progress tracking, and claim handling are in place.

Live Twitch behavior still needs more testing across different accounts, campaigns, devices, and Android versions. Twitch can change private API behavior at any time, so some features may need updates if Twitch changes login, campaign, progress, or claim behavior.

## Requirements

* Android Studio 2026.1.1 or newer recommended
* Android SDK with the project compile SDK installed
* JDK 21 recommended for command-line builds
* Android 8.0 or newer
* Twitch account with eligible Drops campaigns

On Windows, Android Studio's bundled JDK and SDK usually work:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

## Open In Android Studio

1. Open Android Studio.
2. Choose **Open**.
3. Select this project folder.
4. Let Gradle sync finish.
5. Run the `app` configuration on an emulator or connected device.

## Build

From the project folder on Windows:

```powershell
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
chmod +x ./gradlew
./gradlew assembleDebug
```

The debug APK is created at:

```text
app/build/outputs/apk/debug/
```

## Install On A Device

Connect a device with USB debugging enabled, then run:

```powershell
.\gradlew.bat installDebug
```

You can also install and run the app directly from Android Studio.

## How It Works

The app signs in with Twitch using device-code login. It then loads Drops campaign data, checks for eligible drops, follows the selected game priority order, looks for eligible live channels, and keeps watch activity active while mining is running.

When a drop is complete, the app attempts to claim it from the Android runtime. Claim results are shown in the app's activity and logs.

## Game Priorities

The Campaigns screen lets you choose which games should be tried first.

When priority games are selected, the app follows that order. If no games are prioritized, Auto Mode chooses from available eligible campaigns.

Settings allow fallback behavior when priority games are complete or when they do not have an eligible live channel.

## Keep Active Screen

Keep active screen mode shows a black fullscreen screen while keeping the display awake. This can help during long sessions where the app needs to stay active.

This mode uses more battery and should only be enabled when needed.

## Notes And Limitations

* Active mining works best through the foreground service.
* Android 13 and newer require notification permission for foreground notifications.
* Battery optimization can interrupt background work on some devices.
* Network changes, VPNs, captive portals, and device vendor policies can interrupt watch progress.
* Websocket progress updates are not fully ported yet.
* Claim behavior still needs more live testing with real completed claimable drops.
* Optional sample data is only for testing and does not represent live Twitch campaign state.

## Security

The app does not ask for your Twitch password.

Login uses Twitch device-code authorization, which sends you to Twitch to approve access. Session data is stored locally with Android encrypted preferences. Using Reset Session clears stored session data and local selections.

## Credits

This project was inspired by Twitch Drops Miner by rangermix, which is based on the original TwitchDropsMiner project by DevilXD.

This Android app is a separate unofficial project focused on bringing a similar drops workflow to Android.

## Disclaimer

This project is not affiliated with Twitch.

It uses Twitch endpoints and behavior that may change without notice. If Twitch changes request formats, login behavior, progress reporting, or claim behavior, parts of the app may stop working until they are updated.
