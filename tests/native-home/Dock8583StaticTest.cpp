// SPDX-License-Identifier: AGPL-3.0-or-later
// Runs the production matchers against the supplied 8583 ELF, including the
// phone allocation chain that gate-only checks previously missed.
#include "targets/home/home_native_dock.h"
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>

using namespace home_native_dock;
struct Segment { uint64_t offset, rva, size; };
struct Image {
    std::vector<char> bytes;
    std::vector<Segment> segments;
    template<class T> T read(size_t offset) const {
        assert(offset <= bytes.size() && sizeof(T) <= bytes.size() - offset);
        T v; std::memcpy(&v, bytes.data() + offset, sizeof(v)); return v;
    }
    uint32_t word(uintptr_t rva) const {
        for (const auto &s : segments)
            if (rva >= s.rva && rva + 4 <= s.rva + s.size)
                return read<uint32_t>(s.offset + rva - s.rva);
        assert(false && "RVA outside ELF executable segments"); return 0;
    }
    template<size_t N> std::array<uint32_t, N> code(uintptr_t rva) const {
        std::array<uint32_t, N> result{};
        for (size_t i = 0; i < N; ++i) result[i] = word(rva + i * 4);
        return result;
    }
    template<size_t N> std::vector<uintptr_t> find(const std::array<uint32_t, N> &shape) const {
        std::vector<uintptr_t> hits;
        for (const auto &s : segments) {
            for (size_t i = 0; i + N * 4 <= s.size; i += 4) {
                if (read<uint32_t>(s.offset + i) != shape[0]) continue;
                auto candidate = code<N>(s.rva + i);
                if (matches(std::span(candidate), shape)) hits.push_back(s.rva + i);
            }
        }
        return hits;
    }
};

int main(int argc, char **argv) {
    assert(argc == 2);
    Image image;
    std::ifstream file(argv[1], std::ios::binary);
    assert(file);
    image.bytes.assign(std::istreambuf_iterator<char>(file), {});
    assert(image.read<uint32_t>(0) == 0x464c457f);
    assert(image.read<uint16_t>(4) == 0x0102); // ELF64 LE
    assert(image.read<uint16_t>(18) == 183); // AArch64
    const auto phoff = image.read<uint64_t>(32);
    const auto phsize = image.read<uint16_t>(54), phnum = image.read<uint16_t>(56);
    for (size_t i = 0; i < phnum; ++i) {
        const auto p = phoff + phsize * i;
        if (image.read<uint32_t>(p) == 1 && (image.read<uint32_t>(p + 4) & 1))
            image.segments.push_back({image.read<uint64_t>(p + 8), image.read<uint64_t>(p + 16), image.read<uint64_t>(p + 32)});
    }
    assert(!image.segments.empty());
    const auto unique = [&](const auto &shape, uintptr_t expected) {
        const auto hits = image.find(shape);
        assert(hits.size() == 1 && hits[0] == expected);
        auto bad = image.code<std::tuple_size_v<std::decay_t<decltype(shape)>>>(expected);
        bad[0] ^= 1;
        assert(!matches(std::span(bad), shape));
        return hits[0];
    };
    unique(kRelease8583SupportShape, kRelease8583SupportRva);
    const auto gate = unique(kGateShape, 0x11810d0);
    const auto strategy = unique(kRelease8583StrategyShape, 0x1178c34);
    unique(kRelease8583GlassRadiusShape, 0x14c477c);
    unique(kRelease8583AdvancedRadiusShape, 0x14c4960);
    unique(kRelease8583PaddingShape, 0x8f6c14);
    // The previous all-or-nothing installer could never reach the class patch.
    assert(image.find(kStrategyShape).empty());
    assert(image.find(kGlassRadiusShape).empty());
    assert(image.find(kAdvancedRadiusShape).empty());
    assert(image.find(kPaddingShape).empty());
    assert(image.word(kRelease8583BackgroundGateRva) == 0x37200101);
    assert(call_target(kRelease8583BackgroundGateRva, 0x14000008) == 0x11786c4);
    assert(call_target(kRelease8583SupportRva + 8, 0x14000001) == kRelease8583SupportRva + 12);
    const auto check_layout = [&](uintptr_t rva, const auto &shape) {
        auto code = image.code<6>(rva - 4);
        assert(matches(std::span(code), shape));
        // Verify all three calls still use the unchanged global no-label getter.
        assert(call_target(rva - 4, code[0]) == 0x8daf0c);
        const auto original = code[1];
        code[1] = kNoLabelFallthrough;
        assert(!matches(std::span(code), shape));
        code[1] = original;
        assert(matches(std::span(code), shape));
    };
    check_layout(kRelease8583NoLabelLayoutGateRva, kNoLabelLayoutShape);
    check_layout(kRelease8583NoLabelOffsetGateRva, kNoLabelOffsetShape);
    check_layout(kRelease8583NoLabelPositionGateRva, kNoLabelPositionShape);

    const auto glass = call_target(strategy + 44 * 4, image.word(strategy + 44 * 4));
    const auto advanced = call_target(strategy + 59 * 4, image.word(strategy + 59 * 4));
    const auto wrapper = call_target(gate + 6 * 4, image.word(gate + 6 * 4));
    assert(wrapper == 0x118a444);
    const auto thunk = image.code<6>(wrapper);
    assert(thunk[0] == 0xa9bf79fd && thunk[1] == 0xaa0f03fd);
    assert((thunk[2] & 0xfc000000) == 0x94000000);
    assert(thunk[3] == 0xaa1d03ef && thunk[4] == 0xa8c179fd && thunk[5] == 0xd65f03c0);
    const auto phone = call_target(wrapper + 8, thunk[2]);
    assert(phone == 0x1aaf170);
    auto a = image.code<3>(glass), b = image.code<3>(advanced), old = image.code<3>(phone);
    const auto ah = class_header(a), bh = class_header(b), oh = class_header(old);
    assert(ah && bh && oh);
    assert(((*ah >> 8) & 15) == 3 && ((*bh >> 8) & 15) == 2);
    assert((*ah >> 12) == (*bh >> 12) + 1);
    assert(call_target(glass + 8, a[2]) == call_target(advanced + 8, b[2]));
    assert(call_target(glass + 8, a[2]) == call_target(phone + 8, old[2]));
    auto invalid = a; invalid[1] ^= 0x200000; assert(!class_header(invalid));
    invalid = a; invalid[0] ^= 1; assert(!class_header(invalid));

    // Disabled class island executes the untouched MOVK; enabled execution
    // supplies the whole header and resumes after MOVK. Both retain allocator B.
    constexpr uintptr_t island = 0x1ab0000;
    const auto program = class_program(island, island + 0x4000, old[0], phone);
    assert(program[0] == old[0]);
    assert(call_target(island + 6 * 4, program[6]) == phone + 8);
    assert(call_target(island + 8 * 4, program[8]) == phone + 4);
    assert(class_header(old) == oh);
    std::cout << "8583: all material/gate shapes unique; allocation chain, ABI, local layout and restore paths passed\n";
}
