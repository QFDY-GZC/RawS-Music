# DSD-Nexus reader and DST decoder

Upstream: https://github.com/wichers/dsd-nexus

Imported from the upstream `main` archive on 2026-10-12. The main revision observed
at import was `b3f443d4c42ccf52806eb57def3b3a34805714bd`. Archive SHA-256:
`A0526F5E778322FE48DCB26B35FF3F56C5245FDEF5FFAD4392873BD432FA9E89`.
The archive, rather than a Git checkout, is the source of this snapshot.

The `libsacd`, `libdst`, and `libsautil` sources retain their upstream copyright
and license notices. See `COPYING.LGPL` (LGPL 2.1 or later) and the notices in each
source file. The application also bundles the license under
`assets/licenses/dsd-nexus-lgpl-2.1.txt`.

RawSMusic builds the read-only SACD reader and single-frame DST decoder as the
separate `librawsmusic_sacd.so` shared library. Network access, PS3 drive access,
writers and threaded batch decoders are excluded. The complete imported sources,
local changes and CMake build instructions are present in this directory.

Local changes:

- `CMakeLists.txt`: minimal Android shared-library target and source selection.
- `android_charset.c`: Android charset conversion callback supplied by JNI,
  avoiding an external iconv dependency; ASCII fallback for native test tools.
- `libsautil/time.h`: include the platform time header before utility declarations.
- `libsautil/log.c`: enable the existing C11 threads shim for Windows host builds.
- `libsacd/src/sacd_area_toc.c`: bound TOC allocation, zero-pad the buffer, validate
  text offsets/counts/terminators and account for headers and alignment when
  advancing text entries. Permit the reader's initial empty TOC object.
- `libsacd/src/sacd_area_toc.h` and `.c`: keep text item counts per language channel
  so parsing, lookup and destruction use the same bounds.
- `libsacd/src/sacd_dst_reader.c`: validate packet lengths, total payload size and
  frame-start counts before copying sector payloads.
- `libdst/src/decoder.c`: initialize uncoded-frame output and return decoded sample
  bytes, excluding the DST frame header.

The application-specific adapter lives outside this directory in `raw_sacd_*`.
