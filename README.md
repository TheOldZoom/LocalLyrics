# Lyrics

A native Android app that shows indexed device music and music from a selected folder, fetches lyrics for folder tracks, and edits audio metadata with explicit file approval. It runs on the phone with no app server. Network requests go directly from the device to the selected lyric providers; audio files stay on the device.

## Build

Install Android SDK 36 and JDK 17, then run:

```sh
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on a device running Android 8.0 or newer.

GitHub Actions builds a debug APK on pushes and pull requests. After a successful `master` build, it also creates a signed APK and a regular GitHub release with the next tag: `v1.0`, `v2.0`, and so on. You do not need to create tags. The release APK has that version name and an increasing Android version code. Download the APK from the new GitHub release and install it over the previous **signed release** APK to update the app.

One-time signing setup:

```sh
./scripts/release-signing.sh generate
gh auth login -h github.com
./scripts/release-signing.sh upload
```

The key is stored locally in ignored `release-signing.p12`; its password is in ignored `key.properties`. Back up **both** securely and keep them for every future release. The upload command stores the key and password as GitHub Actions repository secrets. A previously installed debug APK has a different signing key and cannot be updated in place by the first signed release; uninstalling it erases its app data. Later APKs from these signed releases can update one another without uninstalling. The debug build artifact remains available for development, but use the signed release APK for updates.

The workflow publishes regular releases but does not run the file-safety checks. Review `docs/overhaul/manual-tests.md` before distributing the app.

## Use

The workspace has **Library**, **Lookup**, **Maker**, and **Settings** tabs. Start a blank Maker draft, paste text, or search by title and artist in Lookup without choosing a folder. Choose a provider result to save it as a draft. Drafts autosave across restarts. In Maker, edit and reorder lines in Text, then attach one local audio file and use Timing to stamp lines and words. Export portable line LRC with title and artist headers, plain text, or enhanced LRC separately. Embedding asks for the audio file's parent folder and uses the same verified backup-and-swap writer as Library; saving a draft or exporting never changes audio.

Tap **Show device music** and grant audio read access once to see indexed tracks across shared storage. Choose a music folder with Android's folder picker for files the index misses. Lyrics scans the library on open and on manual request. Online lookup for folder tracks starts only after you accept the first-run explanation of what track metadata is sent; indexed tracks do not start provider searches. Automatic writing is off by default and never writes indexed tracks. You can search and sort the library, review folder results, edit lyrics or timing, export LRC or plain text, edit a lookup title or artist, retry a match, and write a result manually. Existing embedded lyrics are preserved unless you deliberately retry and replace them.

Open a track and choose **Edit track details** to review and change title, artist, album artist, album, genre, year, track and disc numbers, composer, comment, lyrics, and cover art. Choose a local JPEG or PNG, or copy embedded cover art from another audio file. The source file is only read. MP3, FLAC, M4A, and audio MP4 are editable; other formats show read-only values. Save prepares and verifies a tagged copy before requesting access to the target file. MediaStore writes require per-file approval on Android 10+ or the legacy write permission on Android 8–9, and retain an app-private original in **Needs attention**. Folder writes retain a sibling backup. The filename stays the same. Editing track details does not enable automatic writing or start a provider search.

The library shows fetched results awaiting review, results you marked correct, results you marked wrong, lyrics already in files, and files that need attention. Open a track to read all fetched lyrics and mark the result **Correct** or **Wrong**. A wrong result is kept visible for comparison and cannot be written manually until you change its review or search again. If automatic writing is enabled, lyrics can be added before review; marking them wrong does not remove them from the audio file. Search again to find a replacement.

Under **Settings**, choose and reorder providers. A newly enabled provider is queried without clearing saved results from other providers. Open a track to compare provider results, select the version you want, and mark each provider's result correct or wrong. You can play the local audio while line and word timing follows it, tap a lyric line to seek, copy LRC or plain words to the clipboard, and export either format. **Fetch again** refreshes that track's provider results. Appearance offers light, dark, system, and dynamic colors.

MP3, FLAC, M4A, audio MP4, and WebM can be tagged. AAC and Ogg/Opus remain visible but unsupported for writing pending file-preservation evidence. Writers stream audio payloads and inspect bounded metadata. A tagged file is written and its standard lyrics field verified before it replaces the original. The original is retained as a named backup, available through **Restore original** and **Needs attention**. If a folder provider cannot rename files, the tagged copy remains alongside the original. The app needs continuing access to the chosen folder, so keep its Android document permission.

**Release status:** required tests and manual file-safety checks have not been run. See [overhaul summary](docs/overhaul/SUMMARY.md) for the exact gaps.

## Legal and sources

Lyrics and sound recordings can be separate copyrighted works. A copy of a lyric in your own audio file does not grant permission to publish or redistribute it; check the rights and terms that apply to you. See the [U.S. Copyright Office overview of musical works and recordings](https://copyright.gov/engage/docs/recording.pdf).

Only enabled providers receive lookup metadata. Most routes in this app are unofficial or scrape third-party pages and may change, disappear, or carry terms that differ from the app's GPL license. The **Sources** dialog labels each route's operational risk and lets you turn it off. The app ships no provider lyrics or audio catalogue.

The app contains the [lyrics providers and matching logic from BitChord](https://github.com/kushagrasinghx/BitChord), with only the HTTP and YouTube Music requests needed for lyrics. It also includes adapted metadata readers and writers. The source is distributed under [GPL-3.0](LICENSE). Provider availability can vary by region, credentials, and service changes. A provider's HTTP 402 response cannot be bypassed locally; the app uses the source list and fallback logic in the included lyrics code.

The track details editor uses [JAudioTagger](https://jthink.net/jaudiotagger/) under its LGPL license. The [GPL PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS) repository was reviewed for workflow design; its code and assets were not copied. The proprietary PixelPlayer repository is not used.
