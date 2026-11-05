# Manual checks before release

Use disposable copies of music files. Keep an untouched SHA-256 digest of every original and compare the decoded audio payload after each write.

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
- Compare two unsigned release builds from the same source and toolchain for reproducibility, inspect all transitive dependencies for FOSS licensing, and install a signed minified release build to exercise Room, providers, player, and file recovery.
