# D-TV privacy

D-TV collects no analytics, crash reports, or telemetry and operates no server or user account.

## What is stored

Working data is stored in the app's private SQLite database on the device. This includes provider
addresses and credentials, channel and on-demand catalogues, programme guide data, favourites,
hidden channels, and resume positions.

By default, this data stays on the device. If you explicitly use Google Drive backup, D-TV
encrypts the configuration on the device with your six digit code before uploading it to your
Google account's private app data area. D-TV does not operate that storage, and backup is not
automatic sync.

## Network connections

D-TV connects to the provider and to media or artwork URLs supplied by the provider. Google Drive
is contacted only when you use the backup or restore feature. The app does not send data to a
D-TV-operated service.

## Cleartext HTTP

The app allows plain HTTP because many IPTV panels do not offer HTTPS. On an HTTP connection,
your provider username and password are sent unencrypted; anyone able to observe that network
traffic may read them. Use HTTPS when your provider offers it.

## Uninstalling

Removing D-TV removes its local database and settings. A separately created encrypted Google
Drive backup remains in your Google account until you delete it there.

## Reporting a privacy problem

Open an issue in the D-TV repository. Do not include provider credentials or other secrets.
