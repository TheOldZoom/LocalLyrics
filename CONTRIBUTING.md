# Contributing to Lyrics

Lyrics is GPL-3.0 software. Contributions must be compatible with that license and with F-Droid's free-software requirements. Do not add Google Play Services, Firebase, analytics SDKs, proprietary binaries, or network calls outside user-enabled lyric providers.

Use JDK 17 and Android SDK 36. Build with `./gradlew :app:assembleDebug`. Before proposing a file-writing change, provide independent round-trip evidence for a valid file, a malformed file, preservation of unrelated metadata, and byte-identical audio payload. Every writer must update the standard field in `StandardLyricsField`, and `ApprovedWriter` must verify that field before swapping SAF documents. Keep source music untouched until a complete candidate and restorable backup exist.

Please include the provider's data-sharing behavior and route stability when changing a lyric source. Keep user-facing text in Android resources. Update `HANDOFF.md` for architecture changes and the relevant `docs/overhaul` phase note for remaining risks.

The current overhaul has unrun tests at the maintainer's direction. Do not interpret a compile-only build as approval for a release or a new writer.
