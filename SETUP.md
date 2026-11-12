# D-TV repository and releases

This repository is the independently maintained D-TV project at [github.com/bryjenko/dtv](https://github.com/bryjenko/dtv).
The Android package is `io.github.bryjenko.dtv`. Earlier D-TV APKs use a different package and
will remain installed separately; settings and provider entries do not transfer automatically.

## Build locally

Use JDK 17+ and Android SDK API 35. From the repository root:

```bash
./gradlew test
./gradlew assembleDebug
```

GitHub Actions also runs the project checks and builds release APKs. A tagged version such as
`v2.0.3` starts the release workflow, which attaches the APK to the GitHub release.

## Download

The current APK is attached to the [latest GitHub release](https://github.com/bryjenko/dtv/releases/latest).
