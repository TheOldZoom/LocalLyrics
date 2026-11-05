# Phase 2 — Lookup quality and privacy

Plan (2026-11-04)

1. Persist provider order and query only newly enabled or expired providers instead of invalidating every result.
2. Apply per-provider deadlines, backoff, a circuit breaker, and a TTL for misses without making requests to disabled sources.
3. Start fresh installs with a small provider set and explain metadata disclosure before the first lookup. Label source risk in the UI and document copyright and unofficial routes.
4. Preserve enhanced timing on rescan while the standard field remains authoritative for write verification.

The Phase 1 test exit remains pending under the user's explicit no-test-command instruction. Implementation proceeds under the later instruction to continue through the remaining phases; release readiness remains blocked on verification.

## Implementation note

Changed: per-provider freshness records replace whole-library invalidation when a source is toggled; misses expire after six hours and errors after fifteen minutes. Provider-wide exponential backoff and per-provider deadlines were added. Source order is persisted, the fresh-install set is LRCLIB and lrc.red, and lookup waits for explicit consent. The Sources view labels route risk and the README explains lyric copyright and unofficial routes. Enhanced timing is read for display on track open.

Deliberately skipped: no provider was removed. No live network probe was added. The existing provider parsing code remains because changing each route without fixture tests would risk regressions.

Open risks: several provider adapters convert HTTP errors into null results, so an outage may be cached as a six-hour miss. The no-test instruction leaves migration, timeout, and privacy-contract tests unrun. Compile-only checks pass.
