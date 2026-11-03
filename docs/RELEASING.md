# Releasing

A versioned tag starts the GitHub Actions release workflow. The workflow runs unit tests, builds
and signs the APK with the configured D-TV release keystore, then attaches it to a GitHub release.

For each release, increment `versionCode` and set `versionName` in `app/build.gradle.kts`, add a
section to `CHANGELOG.md`, then tag and push the version. Android requires every release to use
a higher version code. Releases also need to use the persistent signing key configured in GitHub
Actions so Android accepts future updates over the installed app.

The package ID is `io.github.bryjenko.dtv`. Because this differs from earlier builds, version
2.0.3 installs as a separate app and starts with fresh local data. Google Drive backup also
requires an Android OAuth client configured for this package and the release key's SHA-1; see
[Google Drive backup setup](BACKUP.md).
