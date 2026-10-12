// SPDX-License-Identifier: Apache-2.0
#include "targets/home/home_effects.h"
#include "targets/home/folder_spacing.h"
#include <cassert>
#include <algorithm>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>

int main(int argc, char **argv) {
    assert(argc == 4);
    std::ifstream image(argv[1], std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(image)), {});
    std::vector<uint32_t> words(bytes.size() / 4);
    std::memcpy(words.data(), bytes.data(), words.size() * 4);
    size_t glass = 0, dock = 0;
    for (size_t i = 0; i < words.size(); ++i) {
        const auto code = std::span(words).subspan(i);
        if (home_effects::matches_glass(code)) { ++glass; assert(i * 4 == 0x17413e8); }
        if (home_effects::matches_dock(code)) { ++dock; assert(i * 4 == 0xa58398); }
    }
    assert(glass == 1 && dock == 1);
    auto shape = home_effects::kGlassShape;
    for (const auto &field : home_effects::kGlassFields) {
        assert(((shape[field.index] >> 12) & 0x1ff) == (field.reg == 21 ? 0xcb : field.reg == 2 ? 0x17 :
            field.reg == 9 ? 0x5f : field.reg == 13 ? 0x83 : field.reg == 14 ? 0x8b : 0x93));
        shape[field.index] ^= 1;
        assert(!home_effects::matches_glass(shape));
        shape[field.index] ^= 1;
    }
    auto bad_dock = home_effects::kDockShape;
    bad_dock[home_effects::kDockReturnIndex] ^= 1;
    assert(!home_effects::matches_dock(bad_dock));

    std::ifstream input(argv[2], std::ios::binary);
    size_t cases = 0;
    for (uint32_t percent = 0; percent <= 200; ++percent) for (int offset = -60; offset <= 30; ++offset)
        for (bool enabled : {false, true}) {
            uint32_t length = 0;
            input.read(reinterpret_cast<char *>(&length), 4);
            assert(length == 232);
            std::vector<std::byte> blob(length);
            input.read(reinterpret_cast<char *>(blob.data()), length);
            assert(input);
            const auto decoded = folder_spacing::read_material_settings(blob);
            assert(decoded.enabled == enabled && decoded.dock_enabled == enabled && decoded.dock_offset == offset);
            for (size_t i = 0; i < 5; ++i) assert(decoded.percent[i] == percent);
            assert(decoded.percent[5] == std::min(percent, 100U));
            assert(folder_spacing::read_settings(blob).enabled);
            assert(folder_spacing::read_settings(blob).value == 12);
            // No accidental magic scanning: damaged header, count, version,
            // oversized value, bad flags and truncation cannot enable a hook.
            for (size_t at : {size_t(0), size_t(4), size_t(12), size_t(172), size_t(176), size_t(180), size_t(204)}) {
                const auto previous = blob[at];
                blob[at] = std::byte{255};
                const auto invalid = folder_spacing::read_material_settings(blob);
                assert(!invalid.enabled && !invalid.dock_enabled);
                blob[at] = previous;
            }
            for (size_t n = 0; n < blob.size(); ++n) {
                const auto invalid = folder_spacing::read_material_settings(std::span(blob).first(n));
                assert(!invalid.enabled && !invalid.dock_enabled);
            }
            ++cases;
        }
    assert(input.peek() == std::char_traits<char>::eof());
    std::ofstream islands(argv[3], std::ios::binary);
    for (const auto &f : home_effects::kGlassFields) {
        const auto code = home_effects::island_program(0x100000, 0x104000, home_effects::kGlassShape[f.index], f.reg, f.refractive_index, false, 0x108000);
        uint32_t length = static_cast<uint32_t>(code.size() * 4);
        islands.write(reinterpret_cast<const char *>(&length), 4);
        islands.write(reinterpret_cast<const char *>(code.data()), length);
    }
    const auto code = home_effects::island_program(0x100000, 0x104000,
        home_effects::kDockShape[home_effects::kDockReturnIndex], 0, false, true, 0x108000);
    uint32_t length = static_cast<uint32_t>(code.size() * 4);
    islands.write(reinterpret_cast<const char *>(&length), 4);
    islands.write(reinterpret_cast<const char *>(code.data()), length);
    std::cout << "Unique glass/Dock bodies; " << cases << " Kotlin-to-native effect configurations and malformed input guards passed\n";
}
