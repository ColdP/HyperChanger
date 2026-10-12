// SPDX-License-Identifier: Apache-2.0
#include "targets/home/folder_spacing.h"
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>

int main(int argc, char **argv) {
    assert(argc == 3);
    std::ifstream image(argv[1], std::ios::binary);
    const std::vector<char> bytes((std::istreambuf_iterator<char>(image)), {});
    assert(!bytes.empty() && bytes.size() % 4 == 0);
    std::vector<uint32_t> words(bytes.size() / 4);
    std::memcpy(words.data(), bytes.data(), bytes.size());
    size_t matches = 0;
    for (size_t i = 0; i + folder_spacing::kFunctionWords <= words.size(); ++i) {
        if (!folder_spacing::matches(std::span(words).subspan(i))) continue;
        ++matches;
        assert(i * 4 == 0x8ef908); // independent supplied-image expectation
        auto fixture = std::vector<uint32_t>(words.begin() + i,
            words.begin() + i + folder_spacing::kFunctionWords);
        for (const size_t bad : {size_t(3), size_t(24), size_t(25), size_t(54), size_t(74)}) {
            fixture[bad] ^= 1;
            assert(!folder_spacing::matches(fixture));
            fixture[bad] ^= 1;
        }
        // Image-specific call and constant-pool offsets may move.
        fixture[59] ^= 0x100;
        fixture[67] ^= 0x1000;
        fixture[68] ^= 0x1000;
        assert(folder_spacing::matches(fixture));
    }
    assert(matches == 1);

    std::ifstream configs(argv[2], std::ios::binary);
    size_t combinations = 0;
    for (int clear = 0; clear <= 2; ++clear)
        for (int columns = 0; columns <= 15; ++columns)
            for (int value = -20; value <= 80; ++value) for (bool enabled : {false, true}) {
                uint32_t length = 0;
                configs.read(reinterpret_cast<char *>(&length), sizeof(length));
                assert(configs && length >= 156 && length <= 164);
                std::vector<std::byte> blob(length);
                configs.read(reinterpret_cast<char *>(blob.data()), length);
                const auto decoded = folder_spacing::read_settings(blob);
                assert(configs && decoded.value == value && decoded.enabled == enabled);
                // Old relative extension and invalid enable flags must fail closed.
                const size_t extension = length - 16;
                blob[extension + 4] = std::byte{1};
                assert(!folder_spacing::read_settings(blob).enabled);
                blob[extension + 4] = std::byte{2};
                blob[extension + 8] = std::byte{2};
                assert(!folder_spacing::read_settings(blob).enabled);
                blob.resize(length - 1);
                assert(!folder_spacing::read_settings(blob).enabled);
                ++combinations;
            }
    assert(configs.peek() == std::char_traits<char>::eof());
    assert(!folder_spacing::read_settings({}).enabled);
    std::cout << "Unique launcher match; guarded epilogues; " << combinations
              << " signed Kotlin-to-native config combinations passed\n";
}
