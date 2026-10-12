"""Disable launcher layout changes when the native library has no valid config.

The missing-config branch initializes an enabled fallback with features 9 and 16,
and five folder columns. Write zero to Config.masterEnabled instead, retaining
the loader's bookkeeping so a later valid config can still be applied.
"""

from pathlib import Path
import struct


LIBRARY = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a/libhyperpatch.so"
ADDRESS = 0x416BC
BEFORE = bytes.fromhex("350100b9")  # str w21, [x9]; w21 is 1
AFTER = bytes.fromhex("3f0100b9")   # str wzr, [x9]


def patch() -> None:
    blob = bytearray(LIBRARY.read_bytes())
    if blob[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", blob, 18)[0] != 183:
        raise ValueError("Expected a little-endian ARM64 ELF library")
    phoff = struct.unpack_from("<Q", blob, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", blob, 54)
    for index in range(phcount):
        kind, flags, offset, va, _, size, _, _ = struct.unpack_from(
            "<IIQQQQQQ", blob, phoff + index * phsize,
        )
        if kind == 1 and flags & 1 and va <= ADDRESS < va + size:
            target = offset + ADDRESS - va
            current = bytes(blob[target:target + 4])
            if current == AFTER:
                print("Missing-config fallback is already disabled")
                return
            if current != BEFORE:
                raise ValueError(f"Unexpected instruction at {ADDRESS:#x}: {current.hex()}")
            blob[target:target + 4] = AFTER
            LIBRARY.write_bytes(blob)
            print("Disabled missing-config fallback; explicit feature selection is preserved")
            return
    raise ValueError("Fallback address is not in an executable LOAD segment")


if __name__ == "__main__":
    patch()
