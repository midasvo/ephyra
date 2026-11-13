# Google Play

How Ephyra gets onto Google Play (issue #177). Phone and TV share one application ID
(`nl.midasvo.findroid.ce`, ADR 0005). The TV bundle has a versionCode one higher than the phone
bundle (`EphyraVersion.TV_CODE`) and goes to the Android TV form factor track.

The phone bundle is the `proprietary` flavor and the TV bundle is `libre` (#641). Cast needs Google
Play services, which Play devices have, and the TV app has no `proprietary` flavor. Every other
channel (F-Droid, Amazon Appstore) ships `libre`. All flavors share the application ID and the
signing key, so a user can switch between a Play install and a GitHub or F-Droid APK in either
direction.

## Signing

Use Play App Signing with our **existing** release key, the one in the `KEYSTORE_BASE64` secret. If
Google generates the app signing key instead, installs from GitHub and F-Droid can't update to the
Play build or the other way around. This choice can't be undone.

1. In the Play Console, on the first upload, choose **Use existing app signing key** and then
   **Export and upload a key from Java keystore**. Download `pepk.jar` and the encryption key, run
   the command shown with our release `.jks`, and upload the zip it writes.
2. Make a separate upload key for CI and register its certificate in the same screen:

   ```bash
   keytool -genkeypair -keystore ephyra-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   keytool -export -rfc -keystore ephyra-upload.jks -alias upload -file upload_certificate.pem
   ```

Keep both keystores out of the repo. If the upload key is lost, Google can reset it; the app signing
key can't be replaced.

## Building and uploading

`fastlane android deploy` builds both bundles and uploads them (see the comments in
`fastlane/Fastfile`). It signs with whatever the `KEYSTORE_*` variables point at, so point them at
the **upload** key, and set `PLAY_API_CREDENTIALS` to a service account JSON key with release access
to the app.

Until the app has its first live release, the Play API only accepts drafts, and the TV track only
exists once Android TV is added under *Test and release → Advanced settings → Form factors*:

```bash
fastlane android deploy tv:false release_status:draft
```

By hand, without fastlane:

```bash
./gradlew :app:phone:bundleProprietaryRelease :app:tv:bundleLibreRelease
```

The bundles are in `app/phone/build/outputs/bundle/proprietaryRelease/` and
`app/tv/build/outputs/bundle/libreRelease/`.

## Store listing

The listing comes from `fastlane/metadata/android/en-US/`: title, short and full description,
icon, feature graphic, TV banner and the changelog per versionCode.

All four screenshot sets in `images/` are Ephyra's own, five each (`1_en-US.png` to
`5_en-US.png`), taken on emulators signed in to the Jellyfin demo server
(`demo.jellyfin.org/stable`, user `demo`, no password), which only has public-domain films. Keep
it that way when retaking them: never a personal server, since third-party artwork in listing
screenshots risks an IP rejection.

| Set | Emulator | Size | Shots |
|---|---|---|---|
| `phoneScreenshots/` | `ce_phone_tmp` (`pixel_6`), dark theme | 1080×2160 (cropped from 1080×2400 for Play's 2:1 limit), player 2160×1080 | Home, Sahara, Movies, Pioneer One Season 1, player with controls |
| `sevenInchScreenshots/` | `ce_tablet_8` set to 2048×1280 at 320 dpi (1024×640 dp), landscape, dark theme | 2048×1160 (taskbar cropped), player 2048×1280 | Home, Sahara, Movies, Pioneer One Season 1 in list-detail, player with controls |
| `tenInchScreenshots/` | `ce_tablet_10` (`pixel_tablet`), landscape, dark theme | 2560×1480 (taskbar cropped), player 2560×1600 | as the seven-inch set |
| `tvScreenshots/` | a throwaway `tv_1080p` AVD (`android-34;android-tv;x86`), x86 TV APK | 1920×1080 | Home with a focused card, Movies with the A–Z bar, Pioneer One Season 1, Sahara, player overlay |

The phone and tablets run with the status bar in demo mode (12:00, full battery and Wi-Fi), and
the player shots are taken a tap after playback starts so the controls show. On TV, the D-pad
drives everything (`adb shell input keyevent DPAD_*`), the player overlay comes up with Down,
and the open drawer is never captured. [docs/tablet/6-test-matrix.md](tablet/6-test-matrix.md)
has the AVD script, the demo-mode commands and the crop.

## App content forms

| Form | Answer |
|---|---|
| Privacy policy | https://midasvo.nl/ephyra/privacy.html (source: `site/privacy.html`) |
| App access | Login needed: the Jellyfin demo server, user `demo`, empty password |
| Ads | No ads |
| Content rating | Media player; no user-generated content shared by the app |
| Target audience | 18 and over only, so the Families policy doesn't apply |
| Data safety | No data collected or shared; data goes only to the user's own server. **Review before the first proprietary upload:** the phone bundle now contains the Google Play services Cast SDK, so check the Data safety form and the Play Console's SDK and declaration pages against what that SDK does, and update the answer if needed |
| Foreground services | `dataSync` for downloads, `mediaPlayback` for playback, each with a short video |

## Closed test

New personal developer accounts need a closed test with at least 12 opted-in testers for 14 days in
a row before they can apply for production. Testers join with their Google account through a Google
Group or an opt-in link.
