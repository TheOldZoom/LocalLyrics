# Overhaul summary — review required

## Implemented in the working tree, not released

- Retained SAF backups, a restore and recovery screen, encrypted PaxSenix key migration, conservative confidence gating with auto-write off by default, streamed MP3/M4A/WebM writes, and standard-field post-write checks.
- Incremental source lookups, timeout/backoff/negative-result caching, reorderable sources, first-run privacy consent, and provider risk labels.
- Room Flow reads and v3–v5 migrations, StateFlow UI, separate Compose screens, constrained scanning, progress, and a prioritized lookup queue.
- Search, sorting, review queue and bulk actions, proposed lyric editing and sync offset, appearance choices, partial string extraction, and accessibility descriptions.
- Audio MP4 through the M4A container path; tag-based version metadata, optional release signing, R8 release configuration, F-Droid text, contributor/security docs, and this handoff.

## Skipped or incomplete

- Phase 0 was removed at the user's request. No new tests or test commands are permitted by the current instruction. Therefore writer, migration, provider-contract, and CI test gates are unmet. No new CI test/lint gate was added.
- AAC and Ogg Vorbis/Opus writes remain disabled because their standard-field and audio-preservation evidence is unavailable. Full extraction of conditional and interpolated UI strings, screenshots, and runtime accessibility review remain open.
- The changes after commit `ffdc7e0` remain uncommitted because the required pre-commit test/lint gate cannot be run. No public release was made.

## Known risks and verification

Debug Kotlin compilation and unsigned R8 release assembly succeeded. These are build checks, not evidence that user files, Room migrations, or SAF recovery work on devices. R8 emitted Kotlin metadata warnings. The M4A and WebM writers still lack independent round-trip and malformed-file coverage. Runtime behavior of the minified APK, signed build, F-Droid dependency audit, and reproducibility have not been verified. Complete `manual-tests.md` with disposable music before any release; prioritize file integrity above format coverage.
