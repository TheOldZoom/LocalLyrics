# Phase 5 — Formats and release

Plan (2026-11-04)

1. Reuse the MP4 container path for `.mp4` audio after verifying its standard `©lyr` field in the same copy-before-swap pipeline.
2. Evaluate AAC and Ogg/Opus against the no-test instruction and priority of never damaging music; enable writes only where the format can be safely validated.
3. Add tag-based version metadata, environment-based release signing, R8 rules, F-Droid metadata, and contributor/security guidance.
4. Update README and create HANDOFF.md from the actual code. Record verification gaps in SUMMARY.md.

No test commands or new tests are permitted by the latest user instruction. Compile-only checks may be used. Any format that cannot meet the requested round-trip and payload-preservation evidence will stay unsupported.

## Implementation note

Changed: audio-MIME `.mp4` files reuse the M4A writer, `©lyr` verification, and copy-before-swap path. Version names derive from Git tags and version codes from full-history commit counts. Release builds enable R8 and resource shrinking, but the GitHub workflow now builds and publishes debug APK prereleases from `master` pushes without release signing secrets. F-Droid fastlane text and screenshot placeholders, CONTRIBUTING, SECURITY, README, HANDOFF, and the final summary were added or updated. An unsigned minified release APK assembled successfully.

Follow-up: at the user's request, the workflow publishes a debug GitHub prerelease for every `master` push, using a unique `debug-<commit SHA>` tag. The version code uses the full-history commit count; a changing CI debug key can still prevent in-place updates. This automated publication does not close the outstanding file-safety verification gates.

Deliberately skipped: AAC and Ogg/Opus writers because no round-trip, independent standard-field, or audio-payload evidence can be gathered under the user's no-tests instruction. The old WebM and M4A writer paths remain available but have the same missing test evidence. Screenshots remain placeholders. No public release was tagged or published.

Open risks: R8 reported Kotlin metadata parsing warnings despite a successful build; runtime behavior of the minified APK is unverified. Byte-for-byte reproducibility, FOSS dependency audit, and SAF/device verification are pending. Automated debug publication can occur before those gates pass, so `master` pushes require deliberate review.

## Lyrics workspace feature branch (2026-11-05)

The new Maker exports line LRC with title and artist headers; enhanced word timing is a separate export and is supplied as private data only to container writers that already support it. The existing MP3 writer keeps portable USLT lyrics but does not persist enhanced word timing. Single-file embedding asks for its parent tree and reuses the existing verified writer. Drafts and shared lookup cache use a separate Room database and schema. Scans now expose discovered and inspected counts with placeholder rows during inspection. Debug Kotlin compilation succeeds. Device checks for SAF providers, 10k-file responsiveness, cache offline behavior, accessibility, and audio playback remain open in `manual-tests.md`; no test command or new test was used.

## Device-wide library and details editor (2026-11-05)

MediaStore audio permission and index merge were added without a schema rebuild or deletion of SAF rows and drafts. The metadata editor prepares MP3, FLAC, M4A, and audio MP4 candidates with JAudioTagger, verifies common tags, preserved unrelated tag fields, portable lyrics, artwork, and audio payload, then uses SAF backup-and-swap or per-file MediaStore approval. MediaStore writes keep app-private originals and durable recovery records; Needs attention lists both backup types. Other formats remain read-only for metadata editing. The LGPL JAudioTagger dependency and the GPL PixelPlayerOSS workflow review are documented in README and HANDOFF. Debug APK assembly succeeded; runtime and file-safety checks are still open.
