# Phase 3 — Architecture

Plan (2026-11-04)

1. Introduce Room over the current `lyrics.db` schema with explicit migrations from versions 3–5, preserving tracks, review marks, and provider results. Expose Flow queries and StateFlow view models.
2. Split the large activity into screen files and use Navigation Compose for library, track, recovery, and settings destinations.
3. Replace unconditional fifteen-minute work with scan-on-open, manual rescan, and constrained maintenance work. Add progress and bounded, prioritized lookup batches for large libraries.
4. Remove stale BitChord comments and dead code while keeping GPL attribution.

Earlier phase verification remains pending because test commands are prohibited. Compile-only checks will be used for implementation feedback, and the release will remain unverified.

## Implementation note

Changed: Room v6 observes the existing track and provider tables through Flow. Explicit migrations from v3, v4, and v5 copy every track row into a schema with a non-null URI and preserve provider rows. Legacy write calls notify Room's invalidation tracker. The activity uses a StateFlow view model and Navigation Compose; library, track, recovery, theme, and settings UI have separate files. Settings is a full screen. Background work runs every six hours with battery-not-low and optional unmetered-network constraints; scans also run on open and on demand. Scan progress and a prioritized lookup batch are visible. Stale comments and unused WebM and store helpers were removed.

Deliberately skipped: the legacy synchronous store is retained as the write adapter while Room owns observation. No destructive migration fallback was added.

Open risks: the Room migrations have not been exercised on real v3 databases under the no-test instruction. SAF scans still copy each changed file to a temporary file for safe metadata inspection, which can be slow for a 10k-track initial scan. Compile-only checks pass.
