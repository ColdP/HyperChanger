// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <string_view>

namespace dock_reveal {
inline constexpr size_t kClassIdCount = 20;
// Application CID table order consumed by dock_reveal_capture_arm64.S:
//  0: RenderBackdropFilter      1: RenderClipRRect         2: RenderPhysicalShape
//  3: Dock private clipper      4: RenderStack             5: _RenderLayoutBuilder
//  6: RenderPadding             7: RenderConstrainedBox    8: RenderPointerListener
//  9: RenderSemanticsGesture   10: RenderExcludeSemantics 11: RenderHyperTransform
// 12: RenderSemanticsAnnot     13: RenderCustomMultiChild 14: MultiChildLayoutParentData
// 15: Offset                   16: LayerHandle            17: ClipRRectLayer
// 18: _ClipRRectEngineLayer    19: _NativeEngineLayer
// VM native-field storage CID 140 is invariant and never indexed through this table.
struct Profile {
    const char *version;
    std::string_view build;
    uintptr_t capture, icon, keyguard;
    uint32_t keyguard_call, icon_entry_word3, box_shift;
    std::array<uint32_t, kClassIdCount> cids;
};
// Application CIDs are verified individually from allocation stubs in each named
// image; RenderBox subclass field offsets shifted by +0xc in 8.01.02.9239.
inline constexpr std::array kProfiles{
    Profile{"8583", "4f1bdaedd03a31f6de7d3ebc4379c75d",
        0x1025190, 0x1585d28, 0x6d387c, 0x941cb2ff, 0xb849b022, 0,
        {5496, 5494, 5489, 9067, 5374, 5415, 5539, 5506, 5514, 5512,
         5472, 5441, 5476, 5399, 5664, 9370, 5270, 5290, 9383, 10850}},
    Profile{"8593", "4f1bdaed7c97ae76de7d3ebc14f6a7b0",
        0x101e710, 0x15808a4, 0x6d3a3c, 0x941cb52b, 0xb849b022, 0,
        {5495, 5493, 5488, 9066, 5373, 5414, 5538, 5505, 5513, 5511,
         5471, 5440, 5475, 5398, 5663, 9369, 5269, 5289, 9382, 10849}},
    Profile{"9239", "4f1bdaed011bbe7156bde6449a5efb49",
        0xf6633c, 0x155399c, 0x6f396c, 0x941d2f7b, 0xb84a7022, 12,
        {5556, 5554, 5549, 9145, 5431, 5472, 5599, 5566, 5574, 5572,
         5532, 5499, 5536, 5456, 5724, 9450, 5327, 5347, 9463, 10938}},
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
