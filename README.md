# MyTVLauncher (tvOS style)

A light tvOS / Liquid-Glass style launcher for Android TV (Arabic and English).

- Home screen with a pages bar, dock, clock, weather and prayer times (Adhan).
- Apps, Weather and Wallpapers pages; Control Center; customisable glass, text contrast and clock.
- Wallpapers: Bing daily 4K pictures and Wikimedia Commons featured pictures.
- TV Manager (built in): stop / disable / remove apps and choose the default launcher, over the TV's own ADB (Kadb).

Build: `./gradlew assembleRelease` (CI builds the `tvos-latest` release). Tests: `./gradlew testDebugUnitTest` (Robolectric).
