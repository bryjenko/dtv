# D-TV privacy

D-TV collects no analytics, crash reports, or telemetry and operates no server or user account.

## What is stored

Working data is stored in the app's private SQLite database on the device. This includes provider
addresses and credentials, channel and on-demand catalogues, programme guide data, favourites,
hidden channels, and resume positions.

By default, this data stays on the device. If you use backup, D-TV encrypts the configuration
with a six digit code and transfers it directly to a phone over your local network. The phone
copies the encrypted text to its clipboard for pasting on another TV. D-TV has no cloud storage
or backup server.

## Network connections

D-TV connects to the provider and to media or artwork URLs supplied by the provider. The backup
transfer uses a temporary local-network connection only while its screen is open.

## Cleartext HTTP

The app allows plain HTTP because many IPTV panels do not offer HTTPS. On an HTTP connection,
your provider username and password are sent unencrypted; anyone able to observe that network
traffic may read them. Use HTTPS when your provider offers it.

## Uninstalling

Removing D-TV removes its local database and settings. Encrypted backup text copied to a phone
clipboard remains there until replaced or cleared by the phone.

## Reporting a privacy problem

Open an issue in the D-TV repository. Do not include provider credentials or other secrets.
