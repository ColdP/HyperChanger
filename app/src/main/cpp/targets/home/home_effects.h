// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <array>
#include <span>
#include <cstdint>
#include <vector>
#include <algorithm>
namespace home_effects {
struct AddressRange { uintptr_t begin; uintptr_t end; };
// Use actual gaps, including anonymous mappings. Megabyte-spaced mmap hints
// miss the small holes left by the Rust runtime's densely packed AOT images.
inline std::vector<uintptr_t> island_candidates(std::vector<AddressRange> ranges,
        uintptr_t target, size_t page, size_t bytes) {
    constexpr uintptr_t reach = 128 * 1024 * 1024 - 256;
    if (page == 0 || bytes == 0 || bytes % page || target > UINTPTR_MAX - reach) return {};
    const uintptr_t low = target > reach ? target - reach : page;
    const uintptr_t high = target + reach;
    std::ranges::sort(ranges, {}, &AddressRange::begin);
    std::vector<uintptr_t> result;
    uintptr_t cursor = low;
    const auto gap = [&](uintptr_t begin, uintptr_t end) {
        const uintptr_t first = begin + (page - begin % page) % page;
        if (end <= first || end - first < bytes) return;
        const uintptr_t last = (end - bytes) / page * page;
        result.push_back(first);
        if (last != first) result.push_back(last);
    };
    for (const auto &range : ranges) {
        if (range.end <= cursor) continue;
        if (range.begin >= high) break;
        if (range.begin > cursor) gap(cursor, std::min(range.begin, high));
        cursor = std::max(cursor, range.end);
        if (cursor >= high) break;
    }
    if (cursor < high) gap(cursor, high);
    std::ranges::sort(result, [&](uintptr_t a, uintptr_t b) {
        return (a > target ? a - target : target - a) < (b > target ? b - target : target - b);
    });
    return result;
}
inline constexpr std::array<uint32_t, 122> kGlassShape{
    0xa9bf79fd, 0xaa0f03fd, 0xd10b21ef, 0xaa0203e0, 0xaa0103e3, 0xf81f83a1,
    0xf81f03a2, 0xb800b060, 0x385ff070, 0x385ff011, 0x8a500a30, 0xea5c821f,
    0x54000040, 0x94105567, 0xaa0303e1, 0x940aa7ea, 0xf9403f40, 0xf945d000,
    0xf9402370, 0x6b10001f, 0x54000061, 0xf9495f62, 0x941053e4, 0x93417c01,
    0x36000040, 0xf8407001, 0xf104a83f, 0x5400254b, 0xf85f83a2, 0xf85f03a0,
    0xfc407000, 0x928031f1, 0xfc316ba0, 0xfc40f001, 0x928030f1, 0xfc316ba1,
    0xfc417002, 0x92802ff1, 0xfc316ba2, 0xf841f003, 0xf81e83a3, 0xb8427001,
    0x8b1c8021, 0xf27c003f, 0x9a9f17e4, 0xd37ff884, 0xf81e03a4, 0xfc42b003,
    0x92802ef1, 0xfc316ba3, 0xfc433004, 0x92802df1, 0xfc316ba4, 0xfc43b005,
    0x92802cf1, 0xfc316ba5, 0xfc443006, 0x92802bf1, 0xfc316ba6, 0xb844b005,
    0x8b1c80a5, 0xf81483a5, 0xfc44f007, 0x92802af1, 0xfc316ba7, 0xfc457008,
    0x928029f1, 0xfc316ba8, 0xfc45f009, 0x928028f1, 0xfc316ba9, 0xfc46700a,
    0x928027f1, 0xfc316baa, 0xb846f006, 0x8b1c80c6, 0xf81503a6, 0xfc47300b,
    0x928026f1, 0xfc316bab, 0xfc47b00c, 0x928025f1, 0xfc316bac, 0xfc48300d,
    0x928024f1, 0xfc316bad, 0xfc48b00e, 0x928023f1, 0xfc316bae, 0xfc49300f,
    0x928022f1, 0xfc316baf, 0xfc49b010, 0x928021f1, 0xfc316bb0, 0xb84a3007,
    0x8b1c80e7, 0xf81583a7, 0xfc4a7011, 0x928020f1, 0xfc316bb1, 0xfc4af012,
    0xfc1003b2, 0xb84b7008, 0x8b1c8108, 0xf81603a8, 0xfc4bb013, 0xfc1083b3,
    0xfc4c3014, 0xfc1103b4, 0xfc4cb015, 0xfc1183b5, 0xfc4d3016, 0xfc1203b6,
    0xfc4db017, 0xfc1283b7, 0xfc4e3018, 0xfc1303b8, 0xfc4eb019, 0xfc1383b9,
    0xfc4f301a, 0xfc1403ba,
};
inline bool matches_glass(std::span<const uint32_t> code) {
    if (code.size() < kGlassShape.size()) return false;
    for (size_t i = 0; i < kGlassShape.size(); ++i) {
        const uint32_t expected = kGlassShape[i];
        // Only direct-call displacements and object-pool addresses may move.
        const uint32_t mask = (expected & 0xfc000000) == 0x94000000 ? 0xfc000000 :
            ((expected & 0xffc003ff) == 0xf9400360 || (expected & 0xffc003ff) == 0xf9400340
            || (expected & 0xffc003ff) == 0xf9400370 || (expected & 0xffc003ff) == 0xf9400362) ? 0xffc003ff : 0xffffffff;
        if ((code[i] & mask) != (expected & mask)) return false;
    }
    return true;
}
inline constexpr std::array<uint32_t, 27> kDockShape{
    0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xaa0103e0, 0xf81f83a1, 0x940307b3,
    0xb843b001, 0x8b1c8021, 0xfc41f020, 0x6e211c21, 0x1e612000, 0x54000061,
    0x6e201c00, 0x14000005, 0x1e602020, 0x5400006d, 0x1e614001, 0x4ea11c20,
    0xf85f83a1, 0xfc1f03a0, 0x97ff81b4, 0xfc5f03a1, 0x1e602822, 0x4ea21c40,
    0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
};
inline bool matches_dock(std::span<const uint32_t> code) {
    if (code.size() < kDockShape.size()) return false;
    for (size_t i = 0; i < kDockShape.size(); ++i) {
        const uint32_t expected = kDockShape[i];
        // Only direct-call displacements and object-pool addresses may move.
        const uint32_t mask = (expected & 0xfc000000) == 0x94000000 ? 0xfc000000 :
            ((expected & 0xffc003ff) == 0xf9400360 || (expected & 0xffc003ff) == 0xf9400340
            || (expected & 0xffc003ff) == 0xf9400370 || (expected & 0xffc003ff) == 0xf9400362) ? 0xffc003ff : 0xffffffff;
        if ((code[i] & mask) != (expected & mask)) return false;
    }
    return true;
}
struct GlassField { size_t index; size_t parameter; uint32_t reg; bool refractive_index; };
inline constexpr std::array<GlassField, 7> kGlassFields{{
    {110, 0, 21, false},
    {36, 1, 2, false},
    {68, 1, 9, true},
    {83, 2, 13, false},
    {86, 3, 14, false},
    {89, 4, 15, false},
    {114, 5, 23, false},
}};
inline constexpr size_t kDockReturnIndex = 23;
// Each island has immutable code and a separate writable data page. These
// straight-line instructions never call C/C++, allocate Dart objects or retain
// heap pointers. X16 and the full Q30/Q31 values are restored; NZCV is untouched.
inline std::vector<uint32_t> island_program(uintptr_t pc, uintptr_t data,
        uint32_t original, uint32_t reg, bool ior, bool dock, uintptr_t continuation, bool absolute = false) {
    std::vector<uint32_t> code{original, 0xf81f0ff0}; // str x16,[sp,#-16]!
    auto literal = [&](uintptr_t address, uint32_t base) {
        const intptr_t relative = static_cast<intptr_t>(address) - static_cast<intptr_t>(pc + code.size() * 4);
        code.push_back(base | ((static_cast<uint32_t>(relative / 4) & 0x7ffff) << 5));
    };
    literal(data, 0x58000010); // ldr x16, enabled
    const size_t guard = code.size();
    code.push_back(0); // cbz x16, finish
    code.push_back(ior ? 0xadbf7ffe : 0x3c9f0fff); // save Q30/Q31 or Q31
    literal(data + 8, 0x5c00001f); // ldr d31, coefficient
    if (ior) {
        code.push_back(0x1e6e101e); // 1.0
        code.push_back(0x1e7e3800 | (reg << 5) | reg);
    }
    // FMOV D<reg>, D31 for absolute values. The source register is encoded
    // in Rn (bits 9:5), while the destination is Rd (bits 4:0).
    code.push_back(absolute ? (0x1e604000 | (31u << 5) | reg)
                            : (dock ? 0x1e7f2800 : 0x1e7f0800) | (reg << 5) | reg);
    if (ior) code.push_back(0x1e7e2800 | (reg << 5) | reg);
    code.push_back(ior ? 0xacc17ffe : 0x3cc107ff);
    const size_t finish = code.size();
    code[guard] = 0xb4000010 | (static_cast<uint32_t>(finish - guard) << 5);
    code.push_back(0xf84107f0); // ldr x16,[sp],#16
    const intptr_t relative = static_cast<intptr_t>(continuation) - static_cast<intptr_t>(pc + code.size() * 4);
    code.push_back(0x14000000 | (static_cast<uint32_t>(relative / 4) & 0x03ffffff));
    return code;
}
} // namespace home_effects
