# Saved originals and optimized copies

Aura keeps source quality separate from device playback needs. Saving a wallpaper or sound publishes the received bytes to MediaStore without re-encoding them. Video wallpaper imports and feed downloads use a named file in Aura's private original-media directory. Each history row records the source page, a SHA-256 digest, media type, dimensions, codec, duration, size, and HDR state when Android can identify it.

Applying compatible media reads the saved original directly. Aura creates an optimized copy only when a crop or visual treatment changes pixels, an image is too large for the target display, or a motion format needs a safer H.264 MP4. Sound edits and unsupported audio containers use a separate M4A or selected editor output. The copy name is derived from the original digest and apply settings, so repeating the same action reuses the valid file.

Video feed reapply verifies the saved original before any network request. A previously saved Reddit or YouTube video can therefore be reapplied offline. If its size or digest no longer matches, Aura fetches a fresh source instead of trusting the damaged file.

The Downloads screen labels both files. Deleting an optimized copy clears only that working file. The saved original and its provenance remain available, and Aura recreates the copy on the next apply if it is still needed. Deleting the complete download retains both parts during the existing Undo window.

## Failure and storage behavior

Optimized output is written to a pending file, checked for size and media metadata, then moved into place. Aura keeps at least 16 MiB free beyond the expected output. A storage error, interrupted encoder, invalid output, or database failure removes the pending work and leaves the original byte-identical. If a replacement or deletion cannot be recorded, Aura also retains the earlier working copy.

Original motion files and optimized copies are device-local media. Android cloud backup and device transfer exclude their directories, just as portable library export excludes device-specific file paths. User-visible MediaStore originals remain under Android's normal media ownership rules.

## HDR behavior

An unchanged Ultra HDR wallpaper is streamed from its encoded original, which avoids flattening its gainmap. Android 14 and newer are probed for a gainmap when metadata is recorded. A transform that cannot yet carry that gainmap writes a separate copy labeled `SDR working copy`; the HDR original is never overwritten. The gainmap preservation roadmap item can replace that derivative path without changing original ownership or history identity.
