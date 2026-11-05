# Security policy

## Reporting

For a possible music-file corruption, data loss, credential leak, or unsafe lyric source, open a private GitHub Security Advisory for this repository. Include the app version, Android version, music format, storage provider, and exact steps with a **disposable copy** of a file. Do not upload personal music or API keys. If private reporting is unavailable, open a minimal public issue without sensitive data and ask for a private channel.

## Scope

The app can rename and write user-selected music files through Android's Storage Access Framework. A failed write should leave the original or a named `.lyrics-backup-*` file in the selected folder. If a write fails, stop further writes, inspect **Needs attention**, and retain all recovery files until the original is confirmed playable. Backups are retained until manually cleaned up according to the chosen retention window.

PaxSenix credentials are stored with Android Keystore-backed AES-GCM. Track metadata is sent only to enabled lyric sources after onboarding consent. Lyrics and metadata may still be visible to those third-party services and the user's network provider.

Release candidates need file-preservation verification on disposable fixtures and real SAF providers. The current overhaul has not met that gate.
