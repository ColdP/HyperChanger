# Dock adaptation: Launcher 8.01.02.7748

Static analysis target: `com.miui.home`, version code `801027748`, ARM64
Flutter/Rust AOT image `lib/arm64-v8a/libapp.so` from the supplied reverse
directory. The image SHA-256 is
`642f2f830da0ba0e2f1899edaf3e9d84f51f692e6891cb48ee9d65e4f0247f97`.

The Dock capability predicate is at RVA `0xfa1fd4`. The Dock background branch
is at `0x112a2f0`; the three no-label geometry branches are at `0xe9b2c0`,
`0xa8b240`, and `0xb77c4c`. The live words at those locations are respectively
`0xb84db022`, `0x37200101`, `0x37200220`, `0x37200160`, and `0x372000a0`.
The surrounding instruction chains match the resolver's guarded shapes.

The 8.01.02.7722 support predicate shape is byte-identical, so it cannot be
used as a version discriminator. `folder_spacing.cpp` anchors on the unique
support shape, derives the image base from the candidate support RVA, and then
validates all four 7748 Dock instructions before installing any gate. If no
candidate validates, the resolver leaves the image untouched. This is
`STATICALLY_VERIFIED`; no matching-device runtime reproduction is available.

Reproduce the check with:

```text
python tests/native-home/Dock7748StaticTest.py .reverse-tmp/launcher-8.01.02.7748/lib/arm64-v8a/libapp.so
```
