// SPDX-License-Identifier: AGPL-3.0-or-later
#include "targets/home/home_full_glass.h"
#include "targets/home/folder_spacing.h"
#include <cassert>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>

int main(int argc, char **argv) {
    assert(argc==4);
    std::ifstream image(argv[1],std::ios::binary);
    std::vector<char> bytes{std::istreambuf_iterator<char>(image),{}};
    assert(!bytes.empty());
    const auto read = [&]<class T>(size_t at) { T v; assert(at+sizeof(v)<=bytes.size()); std::memcpy(&v,bytes.data()+at,sizeof(v)); return v; };
    assert(read.operator()<uint32_t>(0)==0x464c457f && read.operator()<uint16_t>(18)==183);
    struct Segment { uint64_t offset,rva,size; };
    std::vector<Segment> segments;
    const auto ph=read.operator()<uint64_t>(32);
    for (unsigned i=0;i<read.operator()<uint16_t>(56);++i) {
        const auto at=ph+i*read.operator()<uint16_t>(54);
        if (read.operator()<uint32_t>(at)==1 && (read.operator()<uint32_t>(at+4)&1))
            segments.push_back({read.operator()<uint64_t>(at+8),read.operator()<uint64_t>(at+16),read.operator()<uint64_t>(at+32)});
    }
    const auto unique = [&](const auto &shape, uintptr_t expected, bool v7=false) {
        size_t hits=0;
        for (const auto &s:segments) for (size_t i=0;i+shape.size()*4<=s.size;i+=4) {
            if (read.operator()<uint32_t>(s.offset+i)!=shape[0]) continue;
            std::vector<uint32_t> code(shape.size()); std::memcpy(code.data(),bytes.data()+s.offset+i,code.size()*4);
            if (v7 ? home_effects::matches_glass(code) : home_full_glass::matches(code,shape)) {
                assert(s.rva+i==expected); ++hits;
            }
        }
        assert(hits==1);
    };
    using namespace home_full_glass;
    unique(home_effects::kGlassShape,kV7Rva,true);
    unique(kConstructorShape,kConstructorRva);
    unique(kFactoryTailShape,0x117a1cc);
    unique(kDarkCallShape,0x1179948); unique(kLightCallShape,0x117acd8);
    // Independent semantic order from HomeTweaks 40..66 and V7 scalar reads.
    constexpr std::array<uint32_t,15> offsets{0xcb,0xaf,0xbb,0xc3,0xdb,0x5f,0x17,0x8b,0x93,0xe3,0x7,0x67,0x2b,0x33,0x57};
    std::array<bool,15> seen{};
    std::ofstream islands(argv[2],std::ios::binary);
    for (const auto &f:kFields) {
        assert(f.offset==offsets[f.parameter] && !seen[f.parameter]); seen[f.parameter]=true;
        const uint32_t original=kConstructorShape[f.index];
        assert((original&31)==f.reg && ((original>>5)&31)==5 && ((original>>12)&511)==f.offset);
        const auto code=island_program(0x100000,0x104000,original,f.reg,0x108000);
        const std::array<uint32_t,4> head{static_cast<uint32_t>(code.size()*4),static_cast<uint32_t>(f.parameter),f.reg,f.offset};
        islands.write(reinterpret_cast<const char *>(head.data()),sizeof(head));
        islands.write(reinterpret_cast<const char *>(code.data()),head[0]);
        auto changed=kConstructorShape; changed[f.index]^=1; assert(!matches(changed,kConstructorShape));
    }
    std::ifstream fixtures(argv[3],std::ios::binary);
    assert(fixtures);
    size_t cases=0;
    while (fixtures.peek()!=std::char_traits<char>::eof()) {
        uint32_t length=0; fixtures.read(reinterpret_cast<char *>(&length),4);
        assert(length>=388 && length<512);
        std::vector<std::byte> wire(length); fixtures.read(reinterpret_cast<char *>(wire.data()),length); assert(fixtures);
        auto settings=folder_spacing::read_material_settings(wire);
        assert(settings.full_glass_present && !settings.enabled);
        const auto start=252+folder_spacing::word(wire,12)*4;
        for (size_t group=0;group<2;++group) {
            const auto &record=group==0?settings.desktop_glass:settings.dock_glass;
            assert(record.enabled==(folder_spacing::word(wire,start+8+group*64)!=0));
            for(size_t p=0;p<15;++p) assert(record.values[p]==static_cast<int32_t>(folder_spacing::word(wire,start+12+group*64+p*4)));
        }
        for (size_t n=start+1;n<wire.size();++n) {
            auto invalid=folder_spacing::read_material_settings(std::span(wire).first(n));
            assert(!invalid.enabled && !invalid.desktop_glass.enabled && !invalid.dock_glass.enabled);
        }
        for (size_t at: {start,start+4,start+8,start+12,start+72,start+76}) {
            const auto old=wire[at]; wire[at]=std::byte{255};
            auto invalid=folder_spacing::read_material_settings(wire);
            assert(!invalid.enabled && !invalid.desktop_glass.enabled && !invalid.dock_glass.enabled); wire[at]=old;
        }
        ++cases;
    }
    assert(cases>0);
    std::cout<<"8583 unique shapes, 15 field contracts, "<<cases<<" Kotlin wire cases and malformed-tail guards passed\n";
}
