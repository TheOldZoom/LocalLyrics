# Phase 4 — Review and accessibility

Plan (2026-11-04)

1. Add library search, sort, and a focused review queue.
2. Add bulk actions limited by confidence and explicit selection.
3. Support LRC sync offset and manual lyric edits before writing, without modifying audio until the user applies them.
4. Add light and dynamic color, content descriptions, and move user-facing text into resources.

Earlier phase verification remains pending because test commands are prohibited. Compile-only checks will continue; release readiness remains blocked on runtime and test verification.

## Implementation note

Changed: search and sort are available in the library, with a review-next action, selected Correct marking, and an explicit confirmation before bulk writing eligible high-confidence tracks. Proposed lyrics can be edited and LRC timestamps shifted in milliseconds before writing. Editing clears the provider selection and review mark so the modified text cannot be auto-written. Light, dark, system, and Android dynamic color options are available. Many fixed UI labels were moved to Android string resources, and source-order and selection controls gained accessibility descriptions.

Deliberately skipped: full extraction of interpolated and conditional UI text remains; those strings still need resource parameters and a translation review. No test code was added under the user's instruction.

Open risks: these UI flows have only passed Kotlin compilation, not device interaction or accessibility inspection. Bulk writes and editor changes need manual verification before release.
