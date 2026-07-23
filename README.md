# ARK Phone

A modern Android phone app built with Kotlin, Jetpack Compose, and Material 3.
ARK Phone can replace your device's default phone app: it provides a dial pad,
incoming and ongoing call screens, recent calls, and contacts.

## Features

- Default phone app (dialer role) with full in-call UI
- Dial pad with T9 contact search
- Recent calls from the system call log
- Contacts with favorites and search
- Dynamic system colors (Material You) on Android 12+, dark theme on older devices
- English by default, Finnish localization selected automatically from the device language

## Requirements

- Android 8.0 (API 26) or newer
- Android Studio (latest stable) or a local Android SDK to build

## Building

```
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Testing

```
./gradlew :app:testDebugUnitTest
```

## License

GPL-3.0 — see [LICENSE](LICENSE).
