# Phase 0 — Safety net

Plan (2026-11-04)

1. Establish a local baseline with the required Gradle checks and document any environment blockers.
2. Centralize the standard lyrics field mapping, make every writer require a nonblank standard value, and make post-write verification read only that field.
3. Add independent container parsers and generated fixtures for MP3, FLAC, M4A, and WebM. Cover round trips, payload and unrelated metadata preservation, repeated writes, private-only rejection, and malformed input.
4. Exercise the document swap sequence with a fake provider, including rename and interruption failures. Fix any case that could lose the original.
5. Add recorded provider contract fixtures, CI test/lint/style gates, and run the required checks before committing each logical step.

Exit gate: local test and lint gates pass, CI runs the same gates, and no known writer path can complete without a verified standard lyrics field.
