# Lyrics codebase handoff

Updated 2026-11-04 from the code. This file did not exist at the start of the overhaul; code remains authoritative if it changes later.

## Project

`app` is the Android/Compose application (`com.music.lyricsapp`), min API 26, target/compile API 36, Java 17. `lyricsCore` holds LRC parsing, matching, and 17 selectable third-party lyric sources. The project is GPL-3.0 and uses no Play Services, Firebase, or analytics SDK. The first-run privacy dialog gates online lookup. Only LRCLIB and lrc.red are selected by default; the user can reorder and enable other sources.

## Main paths

- `MainActivity.kt` coordinates navigation and user actions. `LibraryScreen.kt`, `TrackScreen.kt`, and `SettingsScreen.kt` render the screens; `LyricsTheme.kt` chooses light, dark, system, or dynamic colors.
- `LibraryWorker.kt` scans the user-selected SAF tree, skips recovery artifacts, derives missing artist search hints, observes file signatures, and fetches due sources. It is scheduled on app open/manual rescan and for six-hour constrained background work. `ProviderHealth.kt` tracks backoff and circuit state. `LyricsRepository.kt` applies per-provider timeouts.
- `LyricStore.kt` remains the synchronous SQLite write adapter for `lyrics.db` schema v6. `LyricsRoomDatabase.kt` provides Room Flow reads and explicit migrations from v3–v5. `LyricsViewModel.kt` exposes StateFlow for the UI. Keep the schemas in `app/schemas` when changing the database.
- `LyricConfidence.kt` scores selected results. Automatic writing is off on new installs and requires a score of at least 80, provider agreement, no embedded lyrics, and no Wrong review. `PaxKeyVault.kt` migrates the former plaintext PaxSenix preference to Android Keystore-backed AES-GCM.
- `ApprovedWriter.kt` is the only SAF write path. It checks the scanned signature, copies the original into app cache, checks the standard field and rejects private-only lyrics, writes a separate candidate, verifies the standard field and byte-for-byte candidate transfer, renames the original to a `.lyrics-backup-*` file, then swaps the candidate into the original name. Rename failures leave a clearly named `.tagged-*` candidate and attempt restoration. `RecoveryManager.kt` lists, restores, and manually cleans recovery files. Scans never treat them as library tracks.
- `StandardLyricsField.kt` maps MP3/AAC to ID3 `USLT`, FLAC/Ogg/Opus to Vorbis `LYRICS`, M4A/MP4 to `©lyr`, and WebM/MKV to Matroska `LYRICS`. The supported write paths today are MP3, FLAC, M4A, audio MP4, and WebM. AAC and Ogg/Opus are mapped but **not enabled for writing**. `StandardLyricsReader.kt` reads standard fields for post-write verification; `EmbeddedLyrics.kt` also reads optional private word timing for playback.

## Release state

The overhaul implementation is committed at `16a142c`, and the review/timing follow-up at `edc3882`. The user prohibited test commands while an earlier rule requires a full test/lint gate before each commit; these later commits therefore have no test/lint evidence. The former Phase 0 plan was removed at the user's request. Compile-only debug checks and an unsigned minified release build succeeded. R8 emitted Kotlin metadata parsing warnings. No runtime migration, writer round-trip, SAF failure, provider fixture, accessibility, reproducibility, or F-Droid validation was performed during the overhaul. Automated publication was later requested despite these open verification gates; review `docs/overhaul/SUMMARY.md` and `manual-tests.md` before pushing to `master`.

Release signing reads `LYRICS_KEYSTORE_FILE`, `LYRICS_KEYSTORE_PASSWORD`, `LYRICS_KEY_ALIAS`, and `LYRICS_KEY_PASSWORD`. On each `master` push, the workflow decodes `LYRICS_KEYSTORE_BASE64` into a temporary keystore, builds a signed APK, and publishes a GitHub Release tagged `build-<commit SHA>`. Missing secrets fail the release job. The version name includes the nearest `vMAJOR.MINOR.PATCH` Git tag; the version code is the full-history commit count so each push can update the previous APK. `fastlane/metadata/android/en-US` contains text and screenshot placeholders.
