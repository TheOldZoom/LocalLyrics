# Lyrics

A native Android app that scans a folder of local music, fetches lyrics, and embeds them in the audio files. It runs on the phone with no app server. Network requests go directly from the device to the selected lyric providers; audio files stay on the device.

## Build

Install Android SDK 36 and JDK 17, then run:

```sh
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on a device running Android 8.0 or newer.

GitHub Actions also builds a debug APK on each push or pull request. You can start it manually from **Actions → Build Android APK → Run workflow** and download the `lyrics-debug-apk` artifact from the run.

## Use

Choose a music folder with Android's folder picker. Lyrics scans it immediately and checks for new files periodically. Files without embedded lyrics are fetched and tagged automatically by default. You can turn automatic writing off under **Sources**, review results, export LRC or plain text, edit a title or artist, retry a match, and write a result manually. Existing embedded lyrics are preserved unless you deliberately retry and replace them.

MP3, FLAC, M4A, and WebM can be tagged. Other audio files appear as unsupported. The MP3, M4A, and WebM writers hold files in memory and limit input to 64 MiB. FLAC is streamed. A tagged file is written and verified before the original is replaced. If a folder provider cannot rename files, the tagged copy remains alongside the original. The app needs continuing access to the chosen folder, so keep its Android document permission.

The app contains the [lyrics providers and matching logic from BitChord](https://github.com/kushagrasinghx/BitChord), with only the HTTP and YouTube Music requests needed for lyrics. It also includes adapted metadata readers and writers. The source is distributed under [GPL-3.0](LICENSE). Provider availability can vary by region, credentials, and service changes. A provider's HTTP 402 response cannot be bypassed locally; the app uses the source list and fallback logic in the included lyrics code.
