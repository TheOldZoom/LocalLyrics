# Lyrics

A native Android app that scans a folder of local music, fetches lyrics, and embeds them in the audio files. It runs on the phone with no app server. Network requests go directly from the device to the selected lyric providers; audio files stay on the device.

## Build

Install Android SDK 36 and JDK 17, then run:

```sh
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on a device running Android 8.0 or newer.

GitHub Actions builds a debug APK on pushes and pull requests. Every push to `master` also builds a minified signed APK and publishes a GitHub Release tagged `build-<commit SHA>`. This requires the `LYRICS_KEYSTORE_BASE64`, `LYRICS_KEYSTORE_PASSWORD`, `LYRICS_KEY_ALIAS`, and `LYRICS_KEY_PASSWORD` repository secrets; the release job fails if they are missing. Keep the signing key backed up outside the repository. Gradle uses the nearest `vMAJOR.MINOR.PATCH` tag in the version name and the full-history Git commit count as the increasing Android version code. The release automation does not run the file-safety tests that remain prohibited by the current maintainer instruction; review `docs/overhaul/manual-tests.md` before pushing to `master`.

Set the four secrets under **Repository Settings → Secrets and variables → Actions** before the first release push. `LYRICS_KEYSTORE_BASE64` is the Base64 encoding of the release keystore file; the other values are its store password, key alias, and key password. Keep the original keystore and passwords backed up so later APKs can update earlier ones. Existing debug-signed installs use a different certificate and cannot be updated in place by the signed release APK.

## Use

Choose a music folder with Android's folder picker. Lyrics scans it on open, on manual request, and in constrained background work. Online lookup starts only after you accept the first-run explanation of what track metadata is sent. Automatic writing is off by default; if enabled, it requires provider agreement and strong timing and duration evidence, and skips files with embedded lyrics. You can search and sort the library, review results, edit lyrics or timing, export LRC or plain text, edit a lookup title or artist, retry a match, and write a result manually. Existing embedded lyrics are preserved unless you deliberately retry and replace them.

The library shows fetched results awaiting review, results you marked correct, results you marked wrong, lyrics already in files, and files that need attention. Open a track to read all fetched lyrics and mark the result **Correct** or **Wrong**. A wrong result is kept visible for comparison and cannot be written manually until you change its review or search again. If automatic writing is enabled, lyrics can be added before review; marking them wrong does not remove them from the audio file. Search again to find a replacement.

Under **Settings**, choose and reorder providers. A newly enabled provider is queried without clearing saved results from other providers. Open a track to compare provider results, select the version you want, and mark each provider's result correct or wrong. You can play the local audio while line and word timing follows it, tap a lyric line to seek, copy LRC or plain words to the clipboard, and export either format. **Fetch again** refreshes that track's provider results. Appearance offers light, dark, system, and dynamic colors.

MP3, FLAC, M4A, audio MP4, and WebM can be tagged. AAC and Ogg/Opus remain visible but unsupported for writing pending file-preservation evidence. Writers stream audio payloads and inspect bounded metadata. A tagged file is written and its standard lyrics field verified before it replaces the original. The original is retained as a named backup, available through **Restore original** and **Needs attention**. If a folder provider cannot rename files, the tagged copy remains alongside the original. The app needs continuing access to the chosen folder, so keep its Android document permission.

**Release status:** this overhaul compiles and an unsigned minified release APK builds, but required tests and manual file-safety checks have not been run. It is not ready for public release. See [overhaul summary](docs/overhaul/SUMMARY.md) for the exact gaps.

## Legal and sources

Lyrics and sound recordings can be separate copyrighted works. A copy of a lyric in your own audio file does not grant permission to publish or redistribute it; check the rights and terms that apply to you. See the [U.S. Copyright Office overview of musical works and recordings](https://copyright.gov/engage/docs/recording.pdf).

Only enabled providers receive lookup metadata. Most routes in this app are unofficial or scrape third-party pages and may change, disappear, or carry terms that differ from the app's GPL license. The **Sources** dialog labels each route's operational risk and lets you turn it off. The app ships no provider lyrics or audio catalogue.

The app contains the [lyrics providers and matching logic from BitChord](https://github.com/kushagrasinghx/BitChord), with only the HTTP and YouTube Music requests needed for lyrics. It also includes adapted metadata readers and writers. The source is distributed under [GPL-3.0](LICENSE). Provider availability can vary by region, credentials, and service changes. A provider's HTTP 402 response cannot be bypassed locally; the app uses the source list and fallback logic in the included lyrics code.
