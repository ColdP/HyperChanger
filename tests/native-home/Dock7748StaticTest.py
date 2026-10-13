"""Static guard for the 8.01.02.7748 native Dock anchors.

The test consumes the matching ARM64 libapp.so extracted from the launcher APK.
It checks image-relative RVAs and the surrounding instruction shapes used by
the runtime resolver.  It deliberately does not treat these RVAs as portable
to another Launcher build.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path


EXPECTED_SHA256 = "642f2f830da0ba0e2f1899edaf3e9d84f51f692e6891cb48ee9d65e4f0247f97"

# RVA -> words.  The first word is the instruction patched by the native hook;
# the remaining words provide the local shape used to reject coincidental hits.
TARGETS = {
    0xFA1FD4: (0xB84DB022, 0x8B1C8042, 0x36200062, 0x910082C0, 0x14000007),
    0x112A2F0: (0x37200101,),
    0xE9B2C0 - 4: (0x00000000, 0x37200220, 0xF85F03A1, 0x00000000, 0xF85F03A1, 0xFC1E03A0),
    0xA8B240 - 4: (0x00000000, 0x37200160, 0xFC5F03A1, 0xFC5E83A0, 0x1E601003, 0x6E221C42),
    0xB77C4C - 4: (0x00000000, 0x372000A0, 0xFC5D83A0, 0xAA1D03EF, 0xA8C179FD, 0xD65F03C0),
}

# These are the old 8.01.02.7722 RVAs.  At least one must differ in the new
# image, otherwise an accidental support-anchor match could select old code.
OLD_RVAS = (0xFA0440, 0x11286FC, 0xE9A7E4, 0xA8A10C, 0xB77548)
NEW_FIRST_WORDS = (0xB84DB022, 0x37200101, 0x37200220, 0x37200160, 0x372000A0)


def load_segments(blob: bytes) -> list[tuple[int, int, int]]:
    if blob[:4] != b"\x7fELF" or blob[4:6] != b"\x02\x01":
        raise AssertionError("expected a little-endian ELF64 image")
    if blob[18:20] != b"\xb7\x00":
        raise AssertionError("expected an AArch64 ELF image")
    phoff = struct.unpack_from("<Q", blob, 32)[0]
    phentsize = struct.unpack_from("<H", blob, 54)[0]
    phnum = struct.unpack_from("<H", blob, 56)[0]
    segments = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        kind, flags, file_offset, vaddr, _, filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", blob, offset
        )
        if kind == 1 and file_offset <= len(blob) and filesz <= len(blob) - file_offset:
            segments.append((file_offset, vaddr, filesz))
    return segments


def read_word(blob: bytes, segments: list[tuple[int, int, int]], rva: int) -> int:
    for file_offset, vaddr, filesz in segments:
        if vaddr <= rva < vaddr + filesz:
            return struct.unpack_from("<I", blob, file_offset + rva - vaddr)[0]
    raise AssertionError(f"RVA {rva:#x} is outside PT_LOAD segments")


def find_exact(blob: bytes, segments: list[tuple[int, int, int]], words: tuple[int, ...]) -> list[int]:
    hits = []
    for file_offset, vaddr, filesz in segments:
        if filesz < len(words) * 4:
            continue
        image = memoryview(blob)[file_offset : file_offset + filesz]
        for offset in range(0, filesz - len(words) * 4 + 1, 4):
            if all(struct.unpack_from("<I", image, offset + index * 4)[0] == word
                   for index, word in enumerate(words)):
                hits.append(vaddr + offset)
    return hits


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("libapp", type=Path)
    args = parser.parse_args()
    blob = args.libapp.read_bytes()
    digest = hashlib.sha256(blob).hexdigest()
    if digest != EXPECTED_SHA256:
        raise AssertionError(f"unexpected libapp.so SHA-256: {digest}")
    segments = load_segments(blob)
    support_hits = find_exact(blob, segments, TARGETS[0xFA1FD4])
    if support_hits != [0xFA1FD4]:
        raise AssertionError(f"support predicate is not unique: {support_hits!r}")

    for start, expected in TARGETS.items():
        actual = tuple(read_word(blob, segments, start + index * 4) for index in range(len(expected)))
        # Calls contain image-relative displacements and are intentionally
        # wildcarded in the runtime matcher.  The branch/gadget words remain
        # exact evidence for this particular image.
        for index, (word, wanted) in enumerate(zip(actual, expected)):
            if wanted and word != wanted:
                raise AssertionError(
                    f"RVA {start + index * 4:#x}: got {word:#010x}, expected {wanted:#010x}"
                )

    old_matches = sum(
        read_word(blob, segments, rva) == wanted
        for rva, wanted in zip(OLD_RVAS, NEW_FIRST_WORDS)
    )
    if old_matches == len(OLD_RVAS):
        raise AssertionError("all old 7722 anchors unexpectedly match the 7748 image")
    print(f"8.01.02.7748 Dock anchors verified ({digest})")


if __name__ == "__main__":
    main()
