# Phase 5e region handle report

## What changed

Phase 5e adds a true bounded embedded-artwork region path for formats where the image payload is safe to decode directly from the original audio file.

Implemented first:

- FLAC `METADATA_BLOCK_PICTURE` parser.
- `EmbeddedArtworkRegion.Handle(audioPath, offset, length, mime, format)`.
- `Handle.openStream()` returns a bounded stream over the embedded picture bytes only.
- `BitmapProvider` tries region decode before source-art cache extraction.
- `PlayerService` notification/MediaSession artwork tries region decode before TagLib source-art copy.
- `CoverUriResolver` async cover extraction copies a bounded region stream before TagLib extraction.

## Why FLAC first

FLAC stores album art in metadata block type 6. The block contains an explicit image-data length and the image bytes are contiguous. That makes it safe to expose as `(file, offset, length, mime)`.

MP3, MP4/M4A and DSF/DFF are deliberately not enabled for region decode in this phase:

- MP3 APIC can require ID3v2 unsynchronisation handling.
- MP4/M4A requires verified atom/extended-size handling for `covr`.
- DSF/DFF require verified ID3 offset behavior across files written by different taggers.

They remain on the existing `EmbeddedArtworkSourceCache -> TagLibBridge.extractEmbeddedArtworkToFile()` fallback, so compatibility is preserved.

## Effective path after Phase 5e

```text
Source decode admitted by surface / viewport / token
    ↓
BitmapProvider.decodeEmbeddedWithRegionHandle()
    ├─ FLAC region handle found → decode bounded original-file stream
    └─ no safe region → EmbeddedArtworkSourceCache + native TagLib extraction fallback
            ↓
        FFmpeg / MediaMetadataRetriever final fallback
```

## Old-logic pollution check

- List/Mini/Prefetch lightweight misses still cannot call region decode because `decodeBitmap()` returns `LightweightMiss` before any source decode when `surface.allowsSourceDecode == false`.
- Stable viewport gating from Phase 5b still controls whether List/Prefetch misses may schedule an Indexer request.
- Region decode is only reached by Playback/Fullscreen/Widget/Indexer surfaces that are already allowed to source decode.
- `MediaMetadataRetriever.embeddedPicture` remains only as final fallback after region, TagLib source-art cache and/or FFmpeg paths.
- `TagLibBridge.extractEmbeddedArtworkToFile()` remains as compatibility fallback and as source-art owner for non-region formats.

## Expected telemetry

A FLAC with embedded cover should show:

```text
REGION_TAG_ART ... format=flac-front mime=image/jpeg|image/png offset=... bytes=...
```

and should not need:

```text
NATIVE_TAG_ART ... reused=false
MMR_EMBED ...
FFMPEG_EXTRACT ...
```

unless region parsing or BitmapFactory decoding fails.
