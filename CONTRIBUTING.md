# Contributing to D-TV

Everyone is welcome here, including people who have never contributed to an open-source project
before. If you are fixing a bug that has been annoying you, you are exactly the person this
project is for.

## Code and licensing

D-TV is an independent fork distributed under GPL-3.0-or-later. Please keep contributions
compatible with that license, preserve required attribution, and do not submit proprietary or
leaked code. When adapting code from another open-source project, identify its source and license
so its attribution can be preserved.

## Reporting a bug

The most valuable bug report names a **device**. Most failures here are device-specific — a
particular box, Android version, or provider. Please include:

- Device and Android version
- D-TV version
- Source type: Xtream login or M3U playlist
- What happened and what you expected
- Logs if available: `adb logcat -s D-TV:* EpgRepository:* SyncWorker:*`

**Never paste your provider's URL, username or password into an issue.** Redact them.

## Setting up

```bash
git clone https://github.com/bryjenko/dtv.git
cd dtv
./gradlew test
./gradlew assembleDebug
```

JDK 17+ and Android SDK API 35 are required. Android Studio can install both.

## Pull requests

- Keep each pull request focused.
- Add a test when a change is testable.
- Explain why the change is useful.
- Run `./gradlew test` before submitting when possible.

## Code of conduct

Be decent. Assume the person replying is doing their best with less context than you have.
