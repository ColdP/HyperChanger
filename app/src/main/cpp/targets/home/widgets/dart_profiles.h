// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
namespace home_widgets {
struct Profile { uint64_t support, support_size, support_hash, picker, picker_size, picker_hash; std::array<uint32_t,2> returns; uint32_t picker_gate; std::array<uint32_t, 8> anchor; };
inline constexpr Profile profiles[] = {
    // Launcher 8.01.02.7748; full-function FNV-1a preimages, no relocation masking.
    {0xb4a618, 0x20c, 0xf0c2d0431924cf5aULL, 0x1a8671c, 0x610, 0xfe74adda6e946a8eULL, {468, 512}, 636, {0xa9bf79fd, 0xaa0f03fd, 0xd100e1ef, 0xf9403f40, 0xf9591000, 0xf9402370, 0x6b10001f, 0x54000061}},
    // Launcher 8.01.02.8593; full-function FNV-1a preimages, no relocation masking.
    {0xb5062c, 0x228, 0xeec7e3504ddc6613ULL, 0x1b03ab4, 0x618, 0x800127d06908ad5fULL, {496, 540}, 644, {0xa9bf79fd, 0xaa0f03fd, 0xd10101ef, 0xf9403f40, 0xf9598800, 0xf9402370, 0x6b10001f, 0x54000061}},
    // Launcher 8.01.02.9239; full-function FNV-1a preimages, no relocation masking.
    {0xb7b5ec, 0x1e4, 0xc4a332e187e70130ULL, 0x1b4387c, 0x690, 0x6f0abedfd78815dcULL, {428, 472}, 604, {0xa9bf79fd, 0xaa0f03fd, 0xd100e1ef, 0xf9403f40, 0xf959d000, 0xf9402370, 0x6b10001f, 0x54000061}},
};
}
