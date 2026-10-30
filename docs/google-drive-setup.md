# Google Drive backup: one-off setup

The app backs up to your own Google Drive "app data" folder (scope `drive.appdata`): only this app can read it and it does not appear in your normal Drive files. No secret is compiled into the APK. Google recognises the app by its package name plus the SHA-1 of the key that signed it, so you configure that once in Google Cloud.

1. Go to <https://console.cloud.google.com/> and create a project (e.g. "Training Log").
2. **APIs & Services > Library**: search for "Google Drive API" and click **Enable**.
3. **APIs & Services > OAuth consent screen** (may be called "Google Auth Platform"):
   - User type **External**, app name "Training Log", your email as support and developer contact.
   - **Scopes**: add `https://www.googleapis.com/auth/drive.appdata` (listed as "See, create, and delete its own configuration data in your Google Drive").
   - **Test users**: add your own Google account (the one on the phone).
4. **APIs & Services > Credentials > Create credentials > OAuth client ID**:
   - Application type **Android**.
   - Package name `com.calum.traininglog`.
   - SHA-1: from the RELEASE keystore (the one used for GitHub releases):
     `keytool -list -v -keystore your-release.jks -alias traininglog` and copy the `SHA1:` line.
5. Install the signed release APK, open Backup & sync and tap **Connect Google Drive**. Accept the "Google hasn't verified this app" warning (Advanced > Continue; it is your own app) and allow Drive app data.

## Notes

- **Debug builds use a different SHA-1** (the debug keystore, `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`). To use Drive from a debug build, create a second Android OAuth client with the same package name and the debug SHA-1. Without a matching client the card shows "Drive isn't set up yet."
- **Testing mode expires**: apps left in "Testing" have their consent revoked after 7 days, so the card will ask you to sign in again weekly. To avoid this, on the OAuth consent screen click **Publish app** (status "In production"). It stays unverified, which is fine for personal use (you just see the unverified-app warning once).
- Backups are `training-data-backup.json` (latest) plus a monthly `training-data-YYYY-MM.json`. You can revoke access any time at <https://myaccount.google.com/permissions>, which also lets you delete the hidden app data.
