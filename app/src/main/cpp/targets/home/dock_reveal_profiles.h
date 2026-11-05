// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <string_view>

namespace dock_reveal {
struct Profile {
    const char *version;
    std::string_view build;
    uintptr_t capture, icon, keyguard;
    uint32_t keyguard_call, cid_delta;
};
// Application CIDs are verified from allocation stubs in each named image;
// the VM native-field storage CID 140 never uses this adjustment.
inline constexpr std::array kProfiles{
    Profile{"8583", "4f1bdaedd03a31f6de7d3ebc4379c75d",
        0x1025190, 0x1585d28, 0x6d387c, 0x941cb2ff, 1},
    Profile{"8593", "4f1bdaed7c97ae76de7d3ebc14f6a7b0",
        0x101e710, 0x15808a4, 0x6d3a3c, 0x941cb52b, 0},
};
struct EngineProfile {
    std::string_view build;
    uintptr_t paint, raster, render, save, save_layer, translate, opacity;
    uintptr_t root_vtable, clip_vtable, backdrop_vtable;
};
// Keep the contribution engine ABI alongside the independently checked system
// engine. Vtable addresses include the two-word Itanium ABI prefix.
inline constexpr std::array kEngineProfiles{
    // OS4.0.4.0.XPBCNXM: RVAs recovered from the engine's GNU mini symbols.
    // Runtime entry guards and unlock following verified on Xiaomi 17 Pro Max.
    EngineProfile{"c014a14bb9ddab4431a887c98c6d56ce305b0573",
        0xb1f5fc, 0xb18440, 0xe1fc5c, 0xb240f4, 0xb22ee0, 0xb241f4, 0xb2344c,
        0x1180208, 0x117f418, 0x117f168},
    EngineProfile{"fcc3c35629e617bafce14f6e96dbff79d3da1ab4",
        0xb2a270, 0xb230b4, 0xe2c964, 0xb2ed68, 0xb2db54, 0xb2ee68, 0xb2e0c0,
        0x118e118, 0x118d3c8, 0x118d118},
    EngineProfile{"4ae44cae83c5140c46697ecdf193338e17aa8668",
        0xaac3b8, 0xaa5278, 0xd8ca20, 0xab0ea8, 0xaafc94, 0xab0fa8, 0xab0200,
        0x10e9e28, 0x10e90d8, 0x10e8e28},
};
} // namespace dock_reveal
