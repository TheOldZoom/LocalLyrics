# Phase 5 — Formats and release

Plan (2026-11-04)

1. Reuse the MP4 container path for `.mp4` audio after verifying its standard `©lyr` field in the same copy-before-swap pipeline.
2. Evaluate AAC and Ogg/Opus against the no-test instruction and priority of never damaging music; enable writes only where the format can be safely validated.
3. Add tag-based version metadata, environment-based release signing, R8 rules, F-Droid metadata, and contributor/security guidance.
4. Update README and create HANDOFF.md from the actual code. Record verification gaps in SUMMARY.md.

No test commands or new tests are permitted by the latest user instruction. Compile-only checks may be used. Any format that cannot meet the requested round-trip and payload-preservation evidence will stay unsupported.

## Implementation note

Changed: audio-MIME `.mp4` files reuse the M4A writer, `©lyr` verification, and copy-before-swap path. Release signing is read from environment variables; version names derive from Git tags and version codes from full-history commit counts. Release builds enable R8 and resource shrinking. The GitHub workflow publishes signed APKs from `master` pushes with commit-based release tags when signing secrets are configured. F-Droid fastlane text and screenshot placeholders, CONTRIBUTING, SECURITY, README, HANDOFF, and the final summary were added or updated. An unsigned minified release APK assembled successfully.

Follow-up: at the user's request, the workflow now publishes a signed GitHub Release for every `master` push, using a unique commit-based tag. The version code uses the full-history commit count to support updates between pushes. This automated publication does not close the outstanding file-safety verification gates; missing signing secrets fail the release job.

Deliberately skipped: AAC and Ogg/Opus writers because no round-trip, independent standard-field, or audio-payload evidence can be gathered under the user's no-tests instruction. The old WebM and M4A writer paths remain available but have the same missing test evidence. Screenshots remain placeholders. No public release was tagged or published.

Open risks: R8 reported Kotlin metadata parsing warnings despite a successful build; runtime behavior of the minified APK is unverified. Signed build, byte-for-byte reproducibility, FOSS dependency audit, and SAF/device verification are pending. Automated publication can occur before those gates pass, so `master` pushes require deliberate review.
