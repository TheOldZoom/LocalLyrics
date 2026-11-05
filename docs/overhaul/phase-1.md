# Phase 1 — File safety and trust

Plan (2026-11-04)

1. Retain every original as a restorable backup. Stop scanning from renaming or deleting backup and candidate files; expose restore and cleanup controls.
2. Gate automatic writes behind an explicit opt-in and a confidence score. Never overwrite embedded lyrics or a result marked Wrong.
3. Rewrite MP3, M4A, and WebM through bounded-memory streams, with standard-field reread and content verification before any swap.
4. Add a Needs attention view for stranded candidates and backups.
5. Encrypt and migrate the PaxSenix key with Android Keystore, and let missing-artist tracks use an inferred or manually entered lookup artist without changing audio tags.
6. Cover swap, undo, confidence, and streaming behavior with tests; add a manual-test checklist.

Exit gate: required Gradle checks pass and the tests above are green. No known write path can discard the only original copy.

## Implementation note

The file paths now retain backups, hide recovery artifacts from scans, provide restore and manual cleanup controls, refuse recovery-file deletion without another audio-looking copy, gate automatic writing, encrypt the optional API key, infer lookup metadata, and stream MP3/M4A/WebM writes. Compile-only checks pass.

Deliberately skipped: no automatic backup deletion was added; cleanup requires the user's action. No test code was added under the user's later instruction.

Open risks: the user has prohibited test commands and new tests, so the required unit, lint, and round-trip gates remain unrun. The implementation is not release-ready. SAF provider rename behavior, metadata edge cases, and audio-payload preservation still require the manual checks above and automated coverage when allowed. Backup cleanup is user-triggered only; the default is forever.
