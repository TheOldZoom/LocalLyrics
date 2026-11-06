# Lyrics UI audit

## Direction

The library should feel like a music collection: album artwork leads, track status is easy to scan, and lyrics receive the most reading space. Use deep ink, sea glass and warm amber in a restrained Material 3 palette. Keep actions close to the content they affect. Share spacing, shape and type rules across screens.

## Screens

| Screen | Current issue | Planned change |
| --- | --- | --- |
| Library | Controls occupy the first viewport; all tracks appear at once. | Compact header, album-first browsing, search results, sort menu and filtered album contents. |
| Album tracks | No album level exists. | Add album destination with artwork, metadata and track rows leading to details and editing. |
| Track details | Lyrics and actions have weak hierarchy. | Group actions; put lyrics in a readable card with section markers and RTL support. |
| Edit track details | Original values repeat above every field. | Group fields and show changed originals as supporting text with reset actions. |
| Lookup | Large plain title and stacked controls. | Top app bar, structured search and result states. |
| Maker drafts | Sparse rows and unclear metadata. | Show line count and edit date in concise draft rows. |
| Maker editor | Repetitive line cards; clipped action rows. | Dense line editor, reordering and swipe undo; actions in a persistent bar. |
| Settings | One scrolling list; provider arrow controls; Save at bottom. | Group cards, compact provider rows with drag handles, fixed Save/Cancel bar. |
| Compare providers | Status alignment is inconsistent. | Aligned provider list items with clear trailing state. |
| Needs attention | Actions and recovery states are visually flat. | Sectioned recovery list with clear action priority. |

## Performance and safety

Room already emits cached tracks, but the UI re-filters the full collection on state changes and artwork extraction opens audio files. Measure startup and scan timing; make cached albums visible immediately, move only read work off the main thread, and retain current signatures, lyric reads and all write safeguards. Verify at 360dp / 200% font scale, landscape, RTL, TalkBack and reduced motion.

## Implementation and verification

Album browsing, wide-screen track detail, shared visual components, grouped settings, compact Maker editing, and aligned provider states are implemented. Artwork extraction no longer holds a global lock; track mapping, filtering, sorting, and album grouping now run away from the Compose thread. The write and recovery paths remain unchanged.

The build, unit tests, lint, and Android test APK are checked by Gradle. Instrumented layout tests cover album navigation, lyrics with Arabic text, and the Settings Save action at 360dp and 200% text. Run those tests on a device or emulator before release, then inspect the remaining screens in portrait and landscape with TalkBack and reduced motion. No Android device or emulator is attached to this workspace, so runtime layout checks and scan timing measurements cannot be recorded here.
