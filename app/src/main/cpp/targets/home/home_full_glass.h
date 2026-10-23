// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include "home_effects.h"
#include <array>
#include <span>
#include <vector>
namespace home_full_glass {
inline constexpr uintptr_t kV7Rva = 0x17a052c;
inline constexpr uintptr_t kConstructorRva = 0x117a2fc;
inline constexpr uintptr_t kFactoryCallRva = 0x117a210;
inline constexpr uintptr_t kDarkReturnRva = 0x1179980;
inline constexpr uintptr_t kLightReturnRva = 0x117ad10;
inline constexpr std::array<uint32_t, 286> kConstructorShape{
    0xa9bf79fd, 0xaa0f03fd, 0xf9402368, 0xf9623764, 0xaa0503e0, 0xaa0103e5,
    0xaa0303f0, 0xaa0203e3, 0xaa1003e2, 0xaa0603e1, 0xd28027f1, 0xb83168a8,
    0xf94017a6, 0xfc4070c6, 0xfc0070a6, 0xf9401ba6, 0xfc4070c6, 0xfc00f0a6,
    0xfd4097a6, 0xfc0170a6, 0xf801f0a7, 0xf9403fa6, 0xb80270a6, 0xf94027a6,
    0xfc4070c6, 0xfc02b0a6, 0xf9402ba6, 0xfc4070c6, 0xfc0330a6, 0xf9401fa6,
    0xfc4070c6, 0xfc03b0a6, 0xf94023a6, 0xfc4070c6, 0xfc0430a6, 0xb804b0a0,
    0x385ff0b0, 0x385ff011, 0x8a500a30, 0xea5c821f, 0x54000040, 0x942988f4,
    0xfd4087a6, 0xfc04f0a6, 0xfd4083a6, 0xfc0570a6, 0xfd408fa6, 0xfc05f0a6,
    0xfd4093a6, 0xfc0670a6, 0xaa0203e0, 0xb806f0a0, 0x385ff0b0, 0x385ff011,
    0x8a500a30, 0xea5c821f, 0x54000040, 0x942988e4, 0xf94033a2, 0xfc407046,
    0xfc0730a6, 0xf9403ba2, 0xfc407046, 0xfc07b0a6, 0xf94037a2, 0xfc407046,
    0xfc0830a6, 0xfd408ba6, 0xfc08b0a6, 0xfd407ba6, 0xfc0930a6, 0xfc09b0a2,
    0xaa0103e0, 0xb80a30a0, 0x385ff0b0, 0x385ff011, 0x8a500a30, 0xea5c821f,
    0x54000040, 0x942988ce, 0xfc0a70a1, 0xfd4077a1, 0xfc0af0a1, 0xf94047a0,
    0xb80b70a0, 0x385ff0b0, 0x385ff011, 0x8a500a30, 0xea5c821f, 0x54000040,
    0x942988c3, 0xfd407fa1, 0xfc0bb0a1, 0xf9404ba1, 0xfc407021, 0xfc0c30a1,
    0xf9402fa1, 0xfc407021, 0xfc0cb0a1, 0xfc0d30a3, 0xfc0db0a5, 0xfc0e30a0,
    0xfc0eb0a4, 0xf94013a1, 0xfc407020, 0xfc0f30a0, 0xfd404fa0, 0xa9460740,
    0x91004000, 0xeb00003f, 0x54001609, 0xf9003340, 0xd1003c00, 0xd29c2b81,
    0xf2a00061, 0xf81ff001, 0xfc007000, 0xb80fb0a0, 0x385ff0b0, 0x385ff011,
    0x8a500a30, 0xea5c821f, 0x54000040, 0x942988a2, 0xf9400fa1, 0xb80ff0a1,
    0xf9400ba1, 0xd2802071, 0xb83168a1, 0xd28020f1, 0xb83168a4, 0xf94053a1,
    0xd37ff822, 0xd2802171, 0xb83168a2, 0xfd4073a0, 0xa9460740, 0x91004000,
    0xeb00003f, 0x54001369, 0xf9003340, 0xd1003c00, 0xd29c2b81, 0xf2a00061,
    0xf81ff001, 0xfc007000, 0xd28021f1, 0xb83168a0, 0x385ff0b0, 0x385ff011,
    0x8a500a30, 0xea5c821f, 0x54000040, 0x94298884, 0xfd406ba0, 0xa9460740,
    0x91004000, 0xeb00003f, 0x540011c9, 0xf9003340, 0xd1003c00, 0xd29c2b81,
    0xf2a00061, 0xf81ff001, 0xfc007000, 0xd2802271, 0xb83168a0, 0x385ff0b0,
    0x385ff011, 0x8a500a30, 0xea5c821f, 0x54000040, 0x94298871, 0xfd406fa0,
    0xa9460740, 0x91004000, 0xeb00003f, 0x54001029, 0xf9003340, 0xd1003c00,
    0xd29c2b81, 0xf2a00061, 0xf81ff001, 0xfc007000, 0xd28022f1, 0xb83168a0,
    0x385ff0b0, 0x385ff011, 0x8a500a30, 0xea5c821f, 0x54000040, 0x9429885e,
    0xfd405ba0, 0xa9460740, 0x91004000, 0xeb00003f, 0x54000e89, 0xf9003340,
    0xd1003c00, 0xd29c2b81, 0xf2a00061, 0xf81ff001, 0xfc007000, 0xd2802371,
    0xb83168a0, 0x385ff0b0, 0x385ff011, 0x8a500a30, 0xea5c821f, 0x54000040,
    0x9429884b, 0xfd4057a0, 0xa9460740, 0x91004000, 0xeb00003f, 0x54000ce9,
    0xf9003340, 0xd1003c00, 0xd29c2b81, 0xf2a00061, 0xf81ff001, 0xfc007000,
    0xd28023f1, 0xb83168a0, 0x385ff0b0, 0x385ff011, 0x8a500a30, 0xea5c821f,
    0x54000040, 0x94298838, 0xfd405fa0, 0xa9460740, 0x91004000, 0xeb00003f,
    0x54000b49, 0xf9003340, 0xd1003c00, 0xd29c2b81, 0xf2a00061, 0xf81ff001,
    0xfc007000, 0xd2802471, 0xb83168a0, 0x385ff0b0, 0x385ff011, 0x8a500a30,
    0xea5c821f, 0x54000040, 0x94298825, 0xfd4063a0, 0xa9460740, 0x91004000,
    0xeb00003f, 0x540009a9, 0xf9003340, 0xd1003c00, 0xd29c2b81, 0xf2a00061,
    0xf81ff001, 0xfc007000, 0xd28024f1, 0xb83168a0, 0x385ff0b0, 0x385ff011,
    0x8a500a30, 0xea5c821f, 0x54000040, 0x94298812, 0xf94043a1, 0xd2802571,
    0xb83168a1, 0xd37ff861, 0xd28025f1, 0xb83168a1, 0xd2802671, 0xb83168bf,
    0xf94067a1, 0xd37ff822, 0xd28026f1, 0xb83168a2, 0xd2802771, 0xb83168bf,
    0xaa1603e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
};
inline constexpr std::array<uint32_t, 22> kFactoryTailShape{
    0xaa0003e1, 0x928031f1, 0xfc716ba0, 0x928032f1, 0xfc716ba1, 0x928033f1,
    0xfc716ba2, 0xf85e03a2, 0x928034f1, 0xfc716ba3, 0xfc5203a4, 0x928035f1,
    0xfc716ba5, 0xf85f03a3, 0xf85803a5, 0xf85f83a6, 0xf85783a7, 0x9400003b,
    0xf85303a0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
};
inline constexpr std::array<uint32_t, 17> kDarkCallShape{
    0x4ea21c40, 0xf85e03a2, 0xaa0103e3, 0xf85d83a5, 0xf85e83a7, 0xaa1603e1,
    0x6e211c21, 0x9141f771, 0xfd401623, 0x6e251ca5, 0xd2800006, 0x9143b764,
    0xf945ec84, 0x94000005, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
};
inline constexpr std::array<uint32_t, 17> kLightCallShape{
    0x4ea21c40, 0xf85e03a2, 0xaa0103e3, 0xf85d83a5, 0xf85e83a7, 0xaa1603e1,
    0x6e211c21, 0x9140b371, 0xfd432a23, 0x6e251ca5, 0xd2800006, 0x9143b764,
    0xf945ec84, 0x97fffb21, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
};
// Only PC-relative BL displacements vary; pool offsets and every field write
// are exact for the supplied 8583 image. Other versions fail closed.
inline bool matches(std::span<const uint32_t> code, std::span<const uint32_t> shape) {
    if (code.size() < shape.size()) return false;
    for (size_t i=0; i<shape.size(); ++i) {
        const uint32_t mask = (shape[i] & 0xfc000000) == 0x94000000 ? 0xfc000000 : 0xffffffff;
        if ((code[i]&mask) != (shape[i]&mask)) return false;
    }
    return true;
}
struct Field { size_t index, parameter; uint32_t reg, offset; };
inline constexpr std::array<Field, 15> kFields{{
    {14, 10, 6, 7},
    {19, 6, 6, 23},
    {25, 12, 6, 43},
    {28, 13, 6, 51},
    {45, 14, 6, 87},
    {47, 5, 6, 95},
    {49, 11, 6, 103},
    {68, 7, 6, 139},
    {70, 8, 6, 147},
    {82, 1, 1, 175},
    {92, 2, 1, 187},
    {95, 3, 1, 195},
    {98, 0, 1, 203},
    {100, 4, 5, 219},
    {101, 9, 0, 227},
}};
inline constexpr std::array<double, 15> kMinimum{0, 0, 0, -1, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 0};
inline constexpr std::array<double, 15> kMaximum{500, 2, 3, 1, 2, 5, 120, 3, 1, 1, 100, 2, 1000, 2, 1};

// A store island borrows only the current constructor/factory stack. It never
// retains or modifies a previously cached Dart filter. Scratch GPRs and full
// vectors are restored, and no instruction changes NZCV.
inline std::vector<uint32_t> island_program(uintptr_t pc, uintptr_t data,
        uint32_t original, uint32_t reg, uintptr_t continuation) {
    std::vector<uint32_t> c{0xa9bf47f0}; // stp x16,x17,[sp,#-16]!
    const auto literal = [&](uintptr_t address, uint32_t base) {
        const intptr_t delta = static_cast<intptr_t>(address) - static_cast<intptr_t>(pc + c.size()*4);
        c.push_back(base | ((static_cast<uint32_t>(delta/4)&0x7ffff)<<5));
    };
    const auto adr = [&](uintptr_t address) {
        const auto d = static_cast<uint32_t>(address - (pc + c.size()*4));
        c.push_back(0x10000010 | ((d&3)<<29) | (((d>>2)&0x7ffff)<<5));
    };
    const auto branch = [&](size_t at, size_t to, uint32_t base) {
        c[at] = base | ((static_cast<uint32_t>(to-at)&0x7ffff)<<5);
    };
    c.push_back(0xf94007b0); // saved direct constructor caller
    literal(data+96, 0x58000011);
    c.push_back(0xca110210); // eor x16,x16,x17
    const size_t caller_guard=c.size(); c.push_back(0);
    c.push_back(0xf94003b0); // parent factory FP
    c.push_back(0xf9400610); // factory's caller LR
    literal(data+80, 0x58000011);
    c.push_back(0xca110211); // eor x17,x16,x17
    const size_t dark=c.size(); c.push_back(0);
    literal(data+88, 0x58000011);
    c.push_back(0xca110211);
    const size_t light=c.size(); c.push_back(0);
    adr(data);
    const size_t choose=c.size(); c.push_back(0);
    const size_t dock=c.size(); adr(data+40);
    const size_t selected=c.size();
    c[choose]=0x14000000 | static_cast<uint32_t>(selected-choose);
    branch(dark,dock,0xb4000011); branch(light,dock,0xb4000011);
    c.push_back(0xf9400211); // enabled
    const size_t enabled_guard=c.size(); c.push_back(0);
    c.push_back(0xadbf7ffe); // preserve Q30/Q31
    c.push_back(0x1e604000 | (reg<<5) | 31); // fmov d31, source
    c.push_back(0x9e6603f1); // fmov x17,d31
    c.push_back(0xd37ffa31); // lsl x17,x17,#1 (both signed zeros)
    const size_t zero=c.size(); c.push_back(0);
    c.push_back(0xfd40061e); // factor
    c.push_back(0x1e7e0bff); // fmul d31,d31,d30
    const size_t clamp_branch=c.size(); c.push_back(0);
    const size_t fallback=c.size(); c.push_back(0xfd400a1f);
    const size_t clamp=c.size();
    branch(zero,fallback,0xb4000011);
    c[clamp_branch]=0x14000000 | static_cast<uint32_t>(clamp-clamp_branch);
    c.push_back(0xfd400e1e); c.push_back(0x1e7e4bff); // max(minimum)
    c.push_back(0xfd40121e); c.push_back(0x1e7e5bff); // min(maximum)
    c.push_back((original & ~31u) | 31u); // one adjusted store
    c.push_back(0xacc17ffe);
    const size_t done_branch=c.size(); c.push_back(0);
    const size_t disabled=c.size(); c.push_back(original); // exact stock replay
    branch(caller_guard,disabled,0xb5000010);
    branch(enabled_guard,disabled,0xb4000011);
    const size_t done=c.size(); c.push_back(0xa8c147f0);
    c[done_branch]=0x14000000 | static_cast<uint32_t>(done-done_branch);
    const intptr_t delta=static_cast<intptr_t>(continuation)-static_cast<intptr_t>(pc+c.size()*4);
    c.push_back(0x14000000 | (static_cast<uint32_t>(delta/4)&0x3ffffff));
    return c;
}
} // namespace home_full_glass
