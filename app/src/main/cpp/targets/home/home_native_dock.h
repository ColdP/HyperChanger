// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include "home_effects.h"
#include <optional>
namespace home_native_dock {
inline constexpr uint32_t kNewLauncherVersionCode = 801027748;
// The 8.01.02.6245+ launcher moved the Dock background builder and changed
// the state field used by DeviceConfig. These RVAs are anchored by the unique
// support predicate below; they are never used with an unrecognised image.
inline constexpr uintptr_t kNewSupportRva = 0xf34648;
inline constexpr uintptr_t kNewBackgroundGateRva = 0x109eaf8;
// Dart true is null + 0x20; false is null + 0x30. TBNZ bit 4 therefore
// selects the labelled layout. NOP selects the no-label fallthrough locally;
// never change Utilities.isNoWordModel globally (that also hides icon labels).
// Background bounds, icon position and widget offset must agree on this mode.
inline constexpr uintptr_t kNewNoLabelLayoutGateRva = 0xe0e22c;
inline constexpr uintptr_t kNewNoLabelOffsetGateRva = 0x91f0d0;
inline constexpr uintptr_t kNewNoLabelPositionGateRva = 0xbf1f44;
// The later 8.01.02.7722 image keeps the same Rust code but shifts the
// generated functions and one DeviceConfig field by a release-specific amount.
inline constexpr uintptr_t kNewerSupportRva = 0xfa0440;
inline constexpr uintptr_t kNewerBackgroundGateRva = 0x11286fc;
inline constexpr uintptr_t kNewerNoLabelLayoutGateRva = 0xe9a7e4;
inline constexpr uintptr_t kNewerNoLabelOffsetGateRva = 0xa8a10c;
inline constexpr uintptr_t kNewerNoLabelPositionGateRva = 0xb77548;
// 8.01.02.7748 keeps the 7722 support predicate bytes but the AOT image was
// rebuilt, moving the Dock functions independently of the old image.
inline constexpr uintptr_t kNewestSupportRva = 0xfa1fd4;
inline constexpr uintptr_t kNewestBackgroundGateRva = 0x112a2f0;
inline constexpr uintptr_t kNewestNoLabelLayoutGateRva = 0xe9b2c0;
inline constexpr uintptr_t kNewestNoLabelOffsetGateRva = 0xa8b240;
inline constexpr uintptr_t kNewestNoLabelPositionGateRva = 0xb77c4c;
inline constexpr uint32_t kNoLabelFallthrough = 0xd503201f;
// Include the predicate call and the first four fallthrough instructions so a
// single coincidental branch word cannot select a target in an unknown image.
inline constexpr std::array<uint32_t, 6> kNoLabelLayoutShape{
    0x94000000, 0x37200220, 0xf85f03a1, 0x94000000, 0xf85f03a1, 0xfc1e03a0,
};
inline constexpr std::array<uint32_t, 6> kNoLabelOffsetShape{
    0x94000000, 0x37200160, 0xfc5f03a1, 0xfc5e83a0, 0x1e601003, 0x6e221c42,
};
inline constexpr std::array<uint32_t, 6> kNoLabelPositionShape{
    0x94000000, 0x372000a0, 0xfc5d83a0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
};
inline constexpr std::array<uint32_t, 5> kNewSupportShape{
    0xb84cb022, 0x8b1c8042, 0x36200062, 0x910082c0, 0x14000007,
};
inline constexpr std::array<uint32_t, 5> kNewerSupportShape{
    0xb84db022, 0x8b1c8042, 0x36200062, 0x910082c0, 0x14000007,
};
inline constexpr std::array<uint32_t, 14> kGateShape{
    0xb841b001, 0x8b1c8021, 0x372004c1, 0xf85e83a3, 0xb840f061, 0x8b1c8021,
    0x940024bf, 0xaa0003e2, 0xf85d83a0, 0xf81c83a2, 0xb840b001, 0xb840f003,
    0x8b1c8063, 0xb840b064,
};
// DeviceConfig.supportShowDockBackground. The third instruction is the
// capability-bit test; replacing it with NOP follows the true-object path.
inline constexpr std::array<uint32_t, 5> kSupportShape{
    0xb84db022, 0x8b1c8042, 0x36200062, 0x910082c0,
    0x14000007,
};
inline constexpr std::array<uint32_t, 109> kStrategyShape{
    0xa9bf79fd, 0xaa0f03fd, 0xd10081ef, 0xf81f83a1, 0xf81f03a2, 0xfc1e03a0,
    0xd2800061, 0x9428b95f, 0xaa0003e1, 0xf85f83a0, 0xf81e83a1, 0xb800f020,
    0xfc5e03a0, 0xa9460f42, 0x91004042, 0xeb02007f, 0x54000aa9, 0xf9003342,
    0xd1003c42, 0xd29c2b83, 0xf2a00063, 0xf81ff043, 0xfc007040, 0xb8013022,
    0xb840b002, 0x8b1c8042, 0x6b16005f, 0x54000a20, 0xb8417043, 0x8b1c8063,
    0x37200103, 0xb841f003, 0x8b1c8063, 0x372000a3, 0xf9712b60, 0xaa1d03ef,
    0xa8c179fd, 0xd65f03c0, 0xf85f03a0, 0xf8407003, 0xf100047f, 0x540003ac,
    0xf100007f, 0x540001ac, 0x9423e845, 0xf801f01f, 0xfc5e03a0, 0xfc00b000,
    0x91437b61, 0xf9404821, 0xb8013001, 0x1e6e1001, 0xfc017001, 0xaa1d03ef,
    0xa8c179fd, 0xd65f03c0, 0x91437b61, 0xf9404821, 0x1e6e1001, 0x9423e833,
    0xfc5e03a0, 0xfc00b000, 0x91437b64, 0xf9404884, 0xb8013004, 0x1e6e1001,
    0xfc017001, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0, 0x91437b64, 0xf9404884,
    0x1e6e1001, 0xf100087f, 0x5400020c, 0xb8413040, 0x8b1c8000, 0xb8017020,
    0x9423db71, 0xf85e83a2, 0x91437b61, 0xf9404c21, 0xf81f83a0, 0x9428ba04,
    0xaa0003e1, 0xf85f83a0, 0xb800b001, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0,
    0x9423e811, 0xfc5e03a0, 0xfc00b000, 0x91437b61, 0xf9404821, 0xb8013001,
    0x1e6e1000, 0xfc017000, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0, 0x3c9f0de0,
    0xa9bf05e0, 0x9428bcf9, 0xaa0003e2, 0xa8c105e0, 0x3cc105e0, 0x17ffffab,
    0x9428bf15,
};
inline constexpr std::array<uint32_t, 24> kGlassRadiusShape{
    0xa9bf79fd, 0xaa0f03fd, 0xd10101ef, 0xaa0103e0, 0xf81f83a1, 0xaa0203e1,
    0xf81f03a2, 0xf9403f40, 0xf9524800, 0xf9402370, 0x6b10001f, 0x54000061,
    0xf9403362, 0x941bc11d, 0xaa1603e1, 0xd2800082, 0xf81e83a0, 0x941bc8c7,
    0x91439370, 0xf9407210, 0xb800f010, 0xf85f83a1, 0xfc40b020, 0xfc1d03a0,
};
inline constexpr std::array<uint32_t, 31> kAdvancedRadiusShape{
    0xa9bf79fd, 0xaa0f03fd, 0xd100c1ef, 0xaa0103e0, 0xf81f83a1, 0xaa0203e1,
    0xf81f03a2, 0xd2800061, 0x941bc433, 0xaa0003e1, 0xf85f83a0, 0xf81e83a1,
    0xb800f020, 0xf9403f40, 0xf9524800, 0xf9402370, 0x6b10001f, 0x54000061,
    0xf9403362, 0x941bc09e, 0xaa1603e1, 0xd2800082, 0xf81e03a0, 0x941bc848,
    0x91439370, 0xf9406210, 0xb800f010, 0xf85f83a1, 0xfc40b020, 0xfc1d83a0,
    0xa9460b41,
};
inline constexpr std::array<uint32_t, 26> kPaddingShape{
    0xa9bf79fd, 0xaa0f03fd, 0x97f9fc1b, 0x37200080, 0x91402771, 0xfd477220,
    0x14000003, 0x91402771, 0xfd477620, 0xa9460740, 0x91004000, 0xeb00003f,
    0x54000149, 0xf9003340, 0xd1003c00, 0xd29c2b81, 0xf2a00061, 0xf81ff001,
    0xfc007000, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0, 0x3c9f0de0, 0x9443c17b,
    0x3cc105e0, 0x17fffff9,
};
template<size_t N> inline bool matches(std::span<const uint32_t> code, const std::array<uint32_t,N> &shape) {
    if(code.size()<N) return false;
    for(size_t i=0;i<N;++i) {
        const uint32_t expected=shape[i];
        const uint32_t mask=(expected&0xfc000000)==0x94000000 ? 0xfc000000 :
            ((expected&0xffc00000)==0xf9400000 && ((expected>>5)&31)==27) ? 0xffc003ff : 0xffffffff;
        if((code[i]&mask)!=(expected&mask)) return false;
    }
    return true;
}
inline uintptr_t call_target(uintptr_t pc, uint32_t word) {
    const int32_t signed_word=static_cast<int32_t>(word<<6);
    return static_cast<uintptr_t>(static_cast<intptr_t>(pc)+(signed_word>>6)*int64_t{4});
}
inline std::optional<uint64_t> class_header(std::span<const uint32_t> stub) {
    if(stub.size()<3 || (stub[0]&0xffe0001f)!=0xd2800002 || (stub[1]&0xffe0001f)!=0xf2a00002
        || (stub[2]&0xfc000000)!=0x14000000) return {};
    const uint64_t header=((stub[0]>>5)&0xffff) | (uint64_t((stub[1]>>5)&0xffff)<<16);
    // The allocation size tag occupies bits 11:8. Bits 31:12 are the class
    // ID, so testing bits 15:12 rejected the stock and advanced constructors.
    const uint64_t size_tag = (header >> 8) & 15;
    if((header&0xff)!=0x1c || size_tag<1 || size_tag>3 || (header>>12)==0) return {};
    return header;
}
// Replace just the first class-header instruction with one B. The enabled
// island supplies the complete validated header and skips the old MOVK.
// Disabled execution returns through the untouched stock MOVK.
inline std::vector<uint32_t> class_program(uintptr_t pc, uintptr_t data,
        uint32_t original, uintptr_t target) {
    std::vector<uint32_t> c{original,0xf81f0ff0};
    auto literal=[&](uintptr_t addr,uint32_t reg) {
        const intptr_t delta=static_cast<intptr_t>(addr)-static_cast<intptr_t>(pc+c.size()*4);
        c.push_back(0x58000000 | ((static_cast<uint32_t>(delta/4)&0x7ffff)<<5) | reg);
    };
    literal(data,16);
    const size_t guard=c.size(); c.push_back(0);
    literal(data+8,2); c.push_back(0xf84107f0);
    auto branch=[&](uintptr_t addr) {
        const intptr_t diff=static_cast<intptr_t>(addr)-static_cast<intptr_t>(pc+c.size()*4);
        c.push_back(0x14000000 | (static_cast<uint32_t>(diff/4)&0x03ffffff));
    };
    branch(target+8);
    c[guard]=0xb4000010 | (static_cast<uint32_t>(c.size()-guard)<<5);
    c.push_back(0xf84107f0); branch(target+4);
    return c;
}
} // namespace home_native_dock
