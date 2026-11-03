# Google Drive backup setup

D-TV stores one encrypted configuration file in Google Drive's private `appDataFolder`. It is
not shown in the normal Drive file list. The app requests only the Drive app-data permission; it
does not read or modify the user's other Drive files.

Before the backup buttons can obtain access on a built APK, the app identity must be registered
in a Google Cloud project:

1. Enable the Google Drive API in the project.
2. Configure the Google Auth consent screen, add the `drive.appdata` scope, and add the Google
   accounts that will test the app if the consent screen is still in testing mode.
3. Create an **Android** OAuth client for package name `app.opentv` and the SHA-1 fingerprint of
   the certificate that signs the installed release APK. The release APK is signed by the D-TV
   release key. For a debug APK, create a separate client for package `app.opentv.debug` and its
   debug certificate.
4. Install the APK that matches the registered package and certificate, then use the same Google
   account on both TVs.

### `UNREGISTERED_ON_API_CONSOLE` during Google account access

This means Google does not recognize the installed app's package-name and signing-certificate
pair for the selected Cloud project. Create or correct the Android OAuth client using package
`app.opentv` and the release APK's SHA-1 fingerprint. Also check that the Drive API is enabled and
that the `drive.appdata` scope is configured on the consent screen. Debug APKs use a different
package name and certificate, so a debug client does not authorize the release APK.

To get the release certificate SHA-1 from the APK, download `dtv-v2.0.2.apk` from GitHub Releases
and run this with the JDK's `keytool`:

```text
keytool -printcert -jarfile dtv-v2.0.2.apk
```

Use the SHA-1 value shown in the output when creating the Android OAuth client. Google documents
the Android package-name and certificate-fingerprint fields in its
[OAuth client setup guide](https://support.google.com/cloud/answer/15549257?hl=en).

The six digit code is used locally to encrypt/decrypt the backup with AES-GCM and a salted,
iterated HMAC-SHA256 key derivation. Google account authorization is still required to access the
private backup. If the code is lost, the encrypted backup cannot be recovered.
