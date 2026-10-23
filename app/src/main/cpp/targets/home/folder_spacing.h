// SPDX-License-Identifier: AGPL-3.0-or-later
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
    // FGRS may be followed by independent effect extensions. Spacing stays
    // compatible with spacing-only payloads and ignores subsequent tails.
    if (bytes.size() < extension + 16 || word(bytes, extension) != 0x53524746
        || word(bytes, extension + 4) != 2 || word(bytes, extension + 8) > 1) return {};
    for (size_t i = 0; i < count; ++i)
        if (word(bytes, 16 + i * 4) == 27) return {};
    if (word(bytes, 16 + count * 4 + 60) != 0) return {};
    const int32_t value = static_cast<int32_t>(word(bytes, extension + 12));
    if (value < -20 || value > 80) return {};
    return {word(bytes, extension + 8) != 0, value};
}

struct FullGlassRecord {
    bool enabled = false;
    std::array<int32_t, 15> values{100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -100};
};
struct MaterialSettings {
    bool enabled = false;
    std::array<uint32_t, 6> percent{100, 100, 100, 100, 100, 100};
    bool dock_enabled = false;
    int32_t dock_offset = 0;
    bool native_dock_enabled = false;
    uint32_t native_dock_style = 1;
    uint32_t native_dock_radius = 30;
    uint32_t native_dock_padding = 25;
    uint32_t native_dock_height = 150;
    uint32_t native_dock_bottom = 15;
    bool full_glass_present = false;
    FullGlassRecord desktop_glass, dock_glass;
};

struct MultiSplitSettings {
    bool enabled = false;
};

// MSPL is an independent tail extension. Search only the bounded wire payload
// so older configs remain valid and malformed tails fail closed.
inline MultiSplitSettings read_multi_split_settings(std::span<const std::byte> bytes) {
    if (bytes.size() < 16 || word(bytes, 0) != 0x46435048 || word(bytes, 4) != 18) return {};
    const uint32_t count = word(bytes, 12);
    if (count > 64) return {};
    const size_t start = 140 + size_t(count) * 4;
    for (size_t p = start; p + 12 <= bytes.size(); p += 4) {
        if (word(bytes, p) != 0x4c50534d) continue;
        if (word(bytes, p + 4) != 1 || word(bytes, p + 8) > 1) return {};
        return {word(bytes, p + 8) != 0};
    }
    return {};
}

inline MaterialSettings read_material_settings(std::span<const std::byte> bytes) {
    if (bytes.size() < 204 || word(bytes, 0) != 0x46435048 || word(bytes, 4) != 18) return {};
    const uint32_t count = word(bytes, 12);
    if (count > 64) return {};
    const size_t spacing = 140 + size_t(count) * 4;
    const size_t p = spacing + 16;
    if (p > bytes.size() || bytes.size() - p < 8) return {};
    const uint32_t version = word(bytes, p + 4);
    const bool legacy = version == 3;
    if (bytes.size() < p + (legacy ? 48 : 76) || word(bytes, spacing) != 0x53524746 || word(bytes, spacing + 4) != 2
        || word(bytes, p) != 0x4c52544d || (!legacy && version != 4) || word(bytes, p + 8) > 1) return {};
    const size_t dock = p + (legacy ? 32 : 36);
    if (word(bytes, dock) != 0x464f4944 || word(bytes, dock + 4) != 1 || word(bytes, dock + 8) > 1) return {};
    MaterialSettings settings;
    settings.enabled = word(bytes, p + 8) != 0;
    for (size_t i = 0; i < (legacy ? 5U : 6U); ++i) {
        settings.percent[i] = word(bytes, p + 12 + i * 4);
        if (settings.percent[i] > (i == 5 ? 100U : 200U)) return {};
    }
    settings.dock_enabled = word(bytes, dock + 8) != 0;
    settings.dock_offset = static_cast<int32_t>(word(bytes, dock + 12));
    if (settings.dock_offset < -60 || settings.dock_offset > 30) return {};
    if (!legacy) {
        const size_t native = dock + 16;
        const uint32_t native_version = word(bytes, native + 4);
        if (word(bytes, native) != 0x4b444e48 || (native_version != 1 && native_version != 2) || word(bytes, native + 8) > 1) return {};
        settings.native_dock_enabled = word(bytes, native + 8) != 0;
        settings.native_dock_style = word(bytes, native + 12);
        settings.native_dock_radius = word(bytes, native + 16);
        settings.native_dock_padding = word(bytes, native + 20);
        if (settings.native_dock_style < 1 || settings.native_dock_style > 2 || settings.native_dock_radius > 60
            || settings.native_dock_padding > 150) return {};
        if (native_version >= 2) {
            if (bytes.size() < native + 32) return {};
            settings.native_dock_height = word(bytes, native + 24);
            settings.native_dock_bottom = word(bytes, native + 28);
            if (settings.native_dock_height < 40 || settings.native_dock_height > 300 || settings.native_dock_bottom > 150) return {};
            const size_t full = native + 44; // HNDK v2 (32) then MSPL v1 (12).
            if (bytes.size() > full) {
                // No magic-word search through user values. Refuse malformed tails.
                settings.enabled = false;
                settings.full_glass_present = true;
                if (bytes.size() != full + 136 || word(bytes, native + 32) != 0x4c50534d
                    || word(bytes, native + 36) != 1 || word(bytes, native + 40) > 1
                    || word(bytes, full) != 0x534c4748 || word(bytes, full + 4) != 1) return settings;
                std::array<FullGlassRecord, 2> records{};
                for (size_t r = 0; r < 2; ++r) {
                    const size_t at = full + 8 + r * 64;
                    if (word(bytes, at) > 1) return settings;
                    records[r].enabled = word(bytes, at) != 0;
                    for (size_t i = 0; i < 15; ++i) {
                        const int32_t value = static_cast<int32_t>(word(bytes, at + 4 + i * 4));
                        const int low = i == 0 ? 0 : i == 11 ? -200 : -100;
                        const int high = i == 11 ? 100 : 200;
                        if (value < low || value > high) return settings;
                        records[r].values[i] = value;
                    }
                }
                settings.desktop_glass = records[0]; settings.dock_glass = records[1];
            }
        }
    }
    return settings;
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
