// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace folder_spacing {
inline constexpr uint32_t kReturnStart = 0xaa1603e0; // mov x0, x22
inline constexpr size_t kFunctionWords = 75;
inline constexpr std::array<size_t, 2> kReturnIndices{25, 71};

inline uint32_t word(std::span<const std::byte> bytes, size_t offset) {
    uint32_t value = 0;
    for (size_t i = 0; i < 4; ++i)
        value |= uint32_t(std::to_integer<uint8_t>(bytes[offset + i])) << (i * 8);
    return value;
}

struct Settings {
    bool enabled = false;
    int value = 0;
};
inline constexpr int kDisabledValue = 1000;

// V18 plus our v2 extension. V1 relative settings must not enable absolute mode.
inline Settings read_settings(std::span<const std::byte> bytes) {
    if (bytes.size() < 156 || word(bytes, 0) != 0x46435048 || word(bytes, 4) != 18)
        return {};
    const uint32_t count = word(bytes, 12);
    if (count > 64) return {};
    const size_t extension = 140 + size_t(count) * 4;
    if ((bytes.size() != extension + 16 && bytes.size() != extension + 52) || word(bytes, extension) != 0x53524746
        || word(bytes, extension + 4) != 2 || word(bytes, extension + 8) > 1) return {};
    for (size_t i = 0; i < count; ++i)
        if (word(bytes, 16 + i * 4) == 27) return {};
    if (word(bytes, 16 + count * 4 + 60) != 0) return {};
    const int32_t value = static_cast<int32_t>(word(bytes, extension + 12));
    if (value < -20 || value > 80) return {};
    return {word(bytes, extension + 8) != 0, value};
}

struct MaterialSettings {
    bool enabled = false;
    uint32_t style = 0;
    uint32_t glass = 0;
    uint32_t advanced = 0;
    bool glass_highlight = true;
    bool advanced_highlight = true;
};

inline MaterialSettings read_material_settings(std::span<const std::byte> bytes) {
    if (bytes.size() < 192 || word(bytes, bytes.size() - 36) != 0x4c52544d || word(bytes, bytes.size() - 32) != 1)
        return {};
    const size_t p = bytes.size() - 36;
    return {word(bytes, p + 8) != 0, word(bytes, p + 12), word(bytes, p + 16), word(bytes, p + 20), word(bytes, p + 24) != 0, word(bytes, p + 28) != 0};
}

// Guarded instruction layout from calItemSpacing, rather than a fixed address.
// The receiver is spilled at FP-8, and both return paths must restore the Dart
// stack/frame identically. All stores to the vertical gap use tagged offset 0x3b.
// Unsupported launcher layouts are refused, leaving the stock code intact.
inline bool matches(std::span<const uint32_t> code) {
    if (code.size() < kFunctionWords) return false;
    constexpr std::array<uint32_t, 4> prologue{
        0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xf81f83a1};
    for (size_t i = 0; i < prologue.size(); ++i)
        if (code[i] != prologue[i]) return false;
    if (code[22] != 0xf85f83a0 || code[23] != 0xf804301f
        || code[24] != 0xf803b01f) return false;
    constexpr std::array<uint32_t, 24> tail{
        0xf85f83a0, 0x1e641001, 0x1e651000, 0xfc043001,
        0xfc03b000, 0x1400000f, 0xf85f83a0, 0x1e641001,
        0x94000000, 0x372000c0, 0xf85f83a1, 0x1e641000,
        0xf804303f, 0xfc03b020, 0x14000006, 0xf85f83a1,
        0x91402771, 0xfd477220, 0xfc043020, 0xfc03b020,
        kReturnStart, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0};
    for (size_t i = 0; i < tail.size(); ++i) {
        // Call displacement and elderly-mode pool address are image-specific.
        const uint32_t mask = i == 8 ? 0xfc000000 :
            i == 16 ? 0xffc003ff : i == 17 ? 0xffc003ff : 0xffffffff;
        if ((code[51 + i] & mask) != (tail[i] & mask)) return false;
    }
    for (const size_t at : kReturnIndices) {
        if (code[at] != kReturnStart || code[at + 1] != 0xaa1d03ef
            || code[at + 2] != 0xa8c179fd || code[at + 3] != 0xd65f03c0)
            return false;
    }
    return true;
}
} // namespace folder_spacing
