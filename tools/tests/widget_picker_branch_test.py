"""Verify Android-picker branch direction against reviewed Launcher AOT images.

Run with the local 7748 and/or 8593 libapp.so paths; no proprietary inputs are
stored in the repository. Verifies complete reviewed function preimages, both
Dart bool successors and the installed single-word replacement.
"""
from pathlib import Path
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT / "app/src/main/cpp/targets/home/widgets/dart_profiles.h"
SOURCE = ROOT / "app/src/main/cpp/targets/home/folder_spacing.cpp"


def fnv(data):
    value = 14695981039346656037
    for byte in data:
        value = ((value ^ byte) * 1099511628211) & 0xffffffffffffffff
    return value


def signed(value, bits):
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def target(word, pc, value):
    if word & 0x7e000000 == 0x36000000:  # TBZ/TBNZ
        bit = ((word >> 19) & 31) | ((word >> 26) & 32)
        taken = bool(value & (1 << bit)) == bool(word & 0x01000000)
        return pc + signed((word >> 5) & 0x3fff, 14) * 4 if taken else pc + 4
    if word & 0x7c000000 == 0x14000000:
        return pc + signed(word & 0x3ffffff, 26) * 4
    if word == 0xd503201f:
        return pc + 4
    raise AssertionError(f"Unexpected branch {word:08x}")


source = SOURCE.read_text(encoding="utf-8")
patches = re.findall(r'add\(base \+ profile->picker \+ profile->picker_gate,\s*(0x[0-9a-f]+),\s*(0x[0-9a-f]+), 6\)', source)
assert len(patches) == 1, "Missing unique production picker patch"
original, replacement = map(lambda x: int(x, 16), patches[0])
profiles = re.findall(r'\{(0x[0-9a-f]+), (0x[0-9a-f]+), (0x[0-9a-f]+)ULL, (0x[0-9a-f]+), (0x[0-9a-f]+), (0x[0-9a-f]+)ULL, \{(\d+), (\d+)\}, (\d+),', PROFILE.read_text())
assert sys.argv[1:], "Provide local Launcher libapp.so paths"
for path in map(Path, sys.argv[1:]):
    blob = path.read_bytes()
    matched = 0
    for record in profiles:
        support, size, support_hash, picker, picker_size, picker_hash = map(lambda x: int(x, 16), record[:6])
        returns = tuple(map(int, record[6:8]))
        gate = int(record[8])
        if fnv(blob[support:support + size]) != support_hash:
            continue
        assert fnv(blob[picker:picker + picker_size]) == picker_hash
        pc = picker + gate
        assert struct.unpack_from('<I', blob, pc)[0] == original
        assert struct.unpack_from('<II', blob, pc - 8) == (0xb84fb020, 0x8b1c8000)
        # False's bit 4 is set; true's bit 4 is clear in the reviewed Dart ABI.
        for null_base in (0x10001, 0x40001, 0x700000001):
            android = target(original, pc, null_base + 0x30)
            miui = target(original, pc, null_base + 0x20)
            assert android != miui and miui == pc + 4
            for value in (null_base + 0x20, null_base + 0x30):
                assert target(replacement, pc, value) == android, "Patch hides Android widgets"
                assert target(0xd503201f, pc, value) == miui, "Regression witness must reproduce old failure"
        for offset in returns:
            assert struct.unpack_from('<III', blob, support + offset) == (0xaa1d03ef, 0xa8c179fd, 0xd65f03c0)
        matched += 1
    assert matched == 1, f"Unknown/ambiguous image: {path}"
    print(f"PASS: {path.name} ({path.parent.name}), picker inclusion and both support epilogues")
