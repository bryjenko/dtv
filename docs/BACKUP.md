# Google Drive backup setup

D-TV stores one encrypted configuration file in Google Drive's private `appDataFolder`. It is
not shown in the normal Drive file list. The app requests only the Drive app-data permission; it
does not read or modify the user's other Drive files.

Before the backup buttons can obtain access on a built APK, the app identity must be registered
in a Google Cloud project:

1. Enable the Google Drive API in the project.
2. Configure the OAuth consent screen and add the Google accounts that will test the app if the
   consent screen is still in testing mode.
3. Create an Android OAuth client for package name `app.opentv`, using the SHA-1 certificate
   fingerprint that signs the APK. Create a separate client for the debug certificate when
   testing debug builds.
4. Install the APK signed by that certificate and use the same Google account on both TVs.

The six digit code is used locally to encrypt/decrypt the backup with AES-GCM and a salted,
iterated HMAC-SHA256 key derivation. Google account authorization is still required to access the
private backup. If the code is lost, the encrypted backup cannot be recovered.
