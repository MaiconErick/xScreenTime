# xScreenTime

A screen-time tracker for Android that reads the system's own usage events — no
accessibility service, no background polling. Built as a lightweight replacement for
StayFree, focused on accurate tracking and clean charts.

## Features

- **System usage events** — reads `UsageStatsManager` events directly, so numbers match
  Android's own Digital Wellbeing closely.
- **Overview** — today's screen time, unlocks, per-app breakdown and hourly distribution.
- **Timeline** — a chronological view of app sessions through the day.
- **Trends** — 7/30-day charts per app and per category.
- **Games tab** — filters game packages and can export `playtime_s23u.db` for my other
  trackers.
- **Local only** — all data stays in Room; optional Dropbox upload of the games export.
- **Themes** — dark and neon variants.

## Tech

Kotlin · Jetpack Compose · Room · UsageStatsManager · `minSdk 28`, `targetSdk 34`

## Architecture

```
app/src/main/java/com/maiconerick/xscreentime/
├── data/    usage event reader, Room database, Dropbox export
└── ui/      Overview, Timeline, Trends, Games, Settings, theme
```

## Permissions

`PACKAGE_USAGE_STATS` (granted manually in system settings). Dropbox upload is optional and
needs your own credentials in `data/Dropbox.kt`.

## Build

Requirements: JDK 17, Android SDK 34.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
