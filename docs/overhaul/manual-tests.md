# Manual checks before release

## Workspace redesign (open)

- Start a blank Maker draft without a folder. Paste multiline text, add and reorder individual lines, undo and redo, force-stop and reopen the app, then confirm the draft and edits survive.
- Save a provider result as a draft and compare its words with the Lookup preview. Search the same title and artist offline: cached results should appear without a request. Confirm explicit Refresh fetches again online, and that found, missing, and error entries expire after 30 days, six hours, and 15 minutes respectively.
- Import a portable `.lrc` and an enhanced `.lrc`. Attach one disposable audio file, stamp lines and words, seek, change speed, correct one line, shift all lines, and confirm the live line preview follows playback. Export `.lrc`, `.txt`, and enhanced `.lrc`; inspect title and artist headers and confirm portable output has no angle-bracket word stamps.
- Confirm draft edits and exports leave the audio file byte-identical. For embedding, choose a parent folder that contains the attached file, use a disposable copy, and confirm the standard lyrics field contains portable line LRC. Exercise wrong-folder rejection, rename failure, backup restoration, and interrupted SAF writes.
- Scan a 10k-track folder and confirm discovered rows appear before inspection finishes, counts advance, navigation stays responsive, stable rows do not jump, and write actions remain disabled for uninspected or failed files. Confirm missing tracks are removed only after a complete scan.
- Check bottom navigation on a phone and rail on a wide device. Check large font scale, light/dark/dynamic color, TalkBack focus and labels, and reduced-motion settings across Library, Lookup, Maker, and track detail.

These checks were not run during implementation because no Android device or emulator was attached. The permitted verification was debug Kotlin compilation; no test command was run and no new tests were added.

Use disposable copies of music files. Keep an untouched SHA-256 digest of every original and compare the decoded audio payload after each write.

## Device-wide library and details editor (open, 2026-11-05)

Status: `:app:assembleDebug` succeeded with Android SDK 36. `/home/zoom/android-sdk/platform-tools/adb devices -l` showed no attached device. No on-device checks below were run, no test command was run, and no test was added. Do not merge or push to `master` until the checks are recorded on disposable audio.

- Android 8–9: deny and grant `READ_EXTERNAL_STORAGE`, index shared music, then deny and grant `WRITE_EXTERNAL_STORAGE` at a confirmed Save. Android 10: exercise `RecoverableSecurityException` and its per-file dialog. Android 11+: exercise `MediaStore.createWriteRequest` for one volume-specific URI. Confirm denying any dialog leaves the original byte-identical.
- Populate indexed music without choosing a folder. Add an unindexed SAF file, then grant a folder containing one indexed file and confirm a single visible row for it. Reopen after changing folder access and confirm old drafts and retained SAF rows survive until a completed scan. Check removable volumes and stable track identity after a metadata edit.
- For each editable format (MP3, FLAC, M4A, audio MP4), edit every common text field, year, track and disc numbers, lyrics, and JPEG and PNG cover art. Confirm the displayed original and edited values, the filename, portable lyrics in an independent tagger, unrelated tags, artwork, and decoded audio payload after Save. Confirm AAC, Ogg/Opus, WebM, DSF, and DFF fields are read-only in this editor.
- Copy cover art from another picked audio file. Check JPEG and PNG embedded images, a source with no artwork, a malformed source, and a large source that needs the tag-reader fallback. Confirm the source file SHA-256 stays unchanged, the target stays unchanged until Save, the preview matches the written cover, and cancellation or failure retains the previous cover selection.
- Use large, malformed, oversized-tag, and insufficient-space files. Interrupt a MediaStore write immediately after access is granted and again during byte transfer. On next launch, confirm Needs attention identifies the original copy and can restore it with fresh file approval; compare its SHA-256 digest with the untouched source. Attempt restoration when the MediaStore row is missing or permission is denied and confirm the private original remains.
- Check SAF metadata edits when sibling creation, transfer, either rename, or post-rename verification fails. Confirm the original or named backup remains restorable, and cleanup retention applies to both SAF and app-private media backups.
- Index 10,000 tracks and check scan time, scrolling, search, duplicate suppression, and memory use. Use TalkBack and large font settings on the permission button, original/edited field pairs, artwork previews, confirmation dialog, and recovery action. Confirm metadata Save never starts provider requests or an automatic MediaStore write.

## File preservation

- On each supported format (MP3, FLAC, M4A, audio MP4, WebM), write line-timed LRC and plain lyrics. Open the result in at least two unrelated players or taggers and confirm the standard field is present. Confirm unrelated tags and audio still work.
- Repeat each write on a file with existing standard lyrics, and on one with only `BITCHORD_LYRICS`. Confirm one standard field remains and the private field never substitutes for it.
- Try files larger than 64 MiB, files with artwork, malformed/truncated metadata, oversized atom lengths, and a full storage volume. A failed write must leave the original intact.
- Use a DocumentsProvider that fails candidate creation, output stream writes, rename of original, rename of candidate, and rename back. Interrupt the app after each swap step. Locate and play the original or its named backup.
- Confirm scans never rename or delete `.lyrics-backup-*` or `.tagged-*` files, and never list them as music.
- Write, then use **Restore original**. Confirm the original is playable and the updated version appears as a recovery file. Try restore with a missing or changed current file.
- In **Needs attention**, inspect backup and candidate listings. Confirm deletion requires confirmation and refuses to remove the last audio-looking copy, including a stranded tagged candidate. Try manual cleanup with each retention setting.

## Automatic writing

- On a fresh install, confirm automatic writing is off and the explanation appears once.
- Try one provider, conflicting providers, matching providers, poor timing, missing duration, an existing embedded lyric, and a result marked Wrong. Only a high-confidence result with no embedded lyric may write automatically.
- Verify confidence details on the track screen and that lower-scoring results stay in **To review**.
- Edit proposed lyrics, shift line and word timestamps by positive and negative offsets, and verify no audio file changes until the explicit write action. Check search, sort, bulk review, and bulk high-confidence writes on disposable copies.

## Lookup and secrets

- Migrate an installation with a plaintext PaxSenix key. Confirm the old preference is gone, the encrypted key still works, and key deletion removes ciphertext and IV.
- Try a missing-artist file named `Artist - Title.mp3`, a useful artist folder, and manual artist entry. Confirm audio tags are not modified by lookup inference.
- Upgrade from a real schema-v3 install and confirm tracks, reviews, and provider results survive. Check a 10k-track folder for scan progress and responsive navigation.
- Check light, dark, system, and dynamic color, TalkBack labels and focus order, and the first-run privacy explanation. Confirm only enabled providers receive network requests.
- Compare two debug builds from the same source and toolchain for reproducibility, inspect all transitive dependencies for FOSS licensing, and install the debug prerelease APK to exercise Room, providers, player, and file recovery. Check whether two CI debug APKs can update each other; if their keys differ, document the app data lost when the earlier build must be uninstalled.
