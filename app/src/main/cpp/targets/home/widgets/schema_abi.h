// Adapted from HyperModifier (GPL-3.0), ABI verification only.
#pragma once
#include <array>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "schema_abi_profiles.h"

// A conservative structural ABI contract, not a general ARM64 disassembler.
// Public wrappers and their private clone/drop helpers must retain reviewed
// instructions and call targets. Only relocation addresses and panic-source ADRs
// may move. Unknown code changes fail closed, independently of the ROM build.
namespace schema_abi {
class Image {
    const std::vector<uint8_t> &bytes;
    struct Section { uint32_t name, type; uint64_t flags, address, offset, size; uint32_t link, info; uint64_t align, entry; };
    struct Symbol { uint32_t name; uint8_t info, other; uint16_t section; uint64_t address, size; };
    static_assert(sizeof(Section) == 64 && sizeof(Symbol) == 24);
    std::vector<Section> sections;
    Section symbols{}, strings{}, relocations{}, text{};
    template<class T> bool read(size_t offset, T &value) const {
        if (offset > bytes.size() || sizeof(T) > bytes.size() - offset) return false;
        std::memcpy(&value, bytes.data() + offset, sizeof(T)); return true;
    }
    bool valid(const Section &s) const { return s.offset <= bytes.size() && s.size <= bytes.size() - s.offset; }
    const char *name(const Section &s, uint32_t offset) const {
        if (!valid(s) || offset >= s.size) return nullptr;
        const char *p = reinterpret_cast<const char *>(bytes.data() + s.offset + offset);
        return std::memchr(p, 0, s.size - offset) ? p : nullptr;
    }
    bool symbol(size_t index, Symbol &out) const {
        return index < symbols.size / sizeof(Symbol) && read(symbols.offset + index * sizeof(Symbol), out);
    }
    static int64_t sign(uint64_t value, unsigned bits) {
        return value & (uint64_t{1} << (bits - 1)) ? static_cast<int64_t>(value) - (int64_t{1} << bits) : value;
    }
public:
    explicit Image(const std::vector<uint8_t> &data) : bytes(data) {}
    bool init() {
        if (bytes.size() < 64 || std::memcmp(bytes.data(), "\177ELF\2\1\1", 7)) return false;
        uint16_t machine = 0, type = 0, size = 0, count = 0, stringIndex = 0; uint64_t offset = 0;
        if (!read(16, type) || type != 3 || !read(18, machine) || machine != 183
                || !read(40, offset) || !read(58, size) || size != sizeof(Section)
                || !read(60, count) || count == 0 || count > 1024 || !read(62, stringIndex)
                || stringIndex >= count || offset > bytes.size() || count > (bytes.size() - offset) / sizeof(Section)) return false;
        sections.resize(count);
        for (size_t i = 0; i < count; ++i) if (!read(offset + i * sizeof(Section), sections[i])) return false;
        const auto &names = sections[stringIndex];
        for (const auto &s : sections) {
            const char *n = name(names, s.name); if (!n) return false;
            if (!std::strcmp(n, ".dynsym")) symbols = s;
            else if (!std::strcmp(n, ".dynstr")) strings = s;
            else if (!std::strcmp(n, ".rela.plt")) relocations = s;
            else if (!std::strcmp(n, ".text")) text = s;
        }
        return symbols.size && symbols.entry == 24 && symbols.size % 24 == 0
            && strings.size && relocations.size % 24 == 0 && text.size && (text.flags & 4)
            && valid(symbols) && valid(strings) && valid(relocations) && valid(text);
    }
    bool code(uint64_t address, size_t size, const uint8_t *&out) const {
        if ((address & 3) || address < text.address || address - text.address > text.size
                || size > text.size - (address - text.address)) return false;
        out = bytes.data() + text.offset + address - text.address; return true;
    }
    bool data(uint64_t address, size_t size, const uint8_t *&out) const {
        for (const auto &s : sections) if ((s.flags & 2) && !(s.flags & 4) && s.type != 8 && valid(s)
                && address >= s.address && address - s.address <= s.size && size <= s.size - (address - s.address)) {
            out = bytes.data() + s.offset + address - s.address; return true;
        }
        return false;
    }
    bool find(const char *expected, uint64_t &address, uint64_t &size) const {
        for (size_t i = 0; i < symbols.size / 24; ++i) {
            Symbol s{}; if (!symbol(i, s)) return false;
            const char *n = name(strings, s.name);
            if (n && !std::strcmp(n, expected) && s.section && (s.info & 15) == 2) {
                address = s.address; size = s.size; return true;
            }
        }
        return false;
    }
    bool imported(uint64_t address, const char *expected) const {
        // Android ARM64 PLT: ADRP x16; LDR x17,[x16,#offset]; ADD; BR x17.
        const uint8_t *p = nullptr;
        for (const auto &s : sections) if ((s.flags & 4) && valid(s) && address >= s.address
                && address - s.address <= s.size && 16 <= s.size - (address - s.address))
            p = bytes.data() + s.offset + address - s.address;
        if (!p) return false;
        uint32_t w[4]; std::memcpy(w, p, sizeof(w));
        if ((w[0] & 0x9f00001f) != 0x90000010 || (w[1] & 0xffc003ff) != 0xf9400211
                || (w[2] & 0xffc003ff) != 0x91000210 || w[3] != 0xd61f0220
                || ((w[2] >> 10) & 4095) != ((w[1] >> 10) & 4095) * 8) return false;
        int64_t page = sign((((w[0] >> 5) & 0x7ffff) << 2) | ((w[0] >> 29) & 3), 21) * 4096;
        uint64_t got = (address & ~uint64_t{4095}) + page + ((w[1] >> 10) & 4095) * 8;
        for (size_t i = 0; i < relocations.size; i += 24) {
            uint64_t target = 0, info = 0;
            if (!read(relocations.offset + i, target) || !read(relocations.offset + i + 8, info)) return false;
            if (target != got || (info & 0xffffffff) != 1026) continue; // R_AARCH64_JUMP_SLOT
            Symbol s{}; if (!symbol(info >> 32, s)) return false;
            const char *n = name(strings, s.name); return n && !std::strcmp(n, expected);
        }
        return false;
    }
    bool compatibleUsing(const Contract &profile, const char *selected, std::string &reason,
                         std::vector<uint64_t> *locations = nullptr) const {
        if (!profile.functionCount || profile.functionCount > 256 || profile.rootCount > profile.functionCount) return false;
        std::vector<uint64_t> addresses(profile.functionCount);
        std::vector<size_t> work;
        std::vector<bool> queued(profile.functionCount, false);
        for (size_t i = 0; i < profile.rootCount; ++i) {
            const auto &function = profile.functions[i];
            if (selected && std::strcmp(function.name, selected)) continue;
            uint64_t size = 0;
            if (!find(function.name, addresses[i], size) || size != function.size) {
                reason = std::string("export:") + function.name; return false;
            }
            work.push_back(i); queued[i] = true;
        }
        if (work.empty()) { reason = "unreviewed-export"; return false; }
        // Traverse the selected root's complete helper graph. A legitimate export
        // in a second DSO need not also define all unrelated schema interfaces.
        for (size_t cursor = 0; cursor < work.size(); ++cursor) {
            size_t i = work[cursor]; const auto &function = profile.functions[i];
            const uint8_t *p = nullptr;
            if (!addresses[i] || !code(addresses[i], function.size, p)) { reason = "code-range"; return false; }
            uint64_t hash = 0xcbf29ce484222325ULL;
            for (size_t offset = 0; offset < function.size; offset += 4) {
                uint32_t word; std::memcpy(&word, p + offset, 4);
                if ((word & 0x7c000000) == 0x14000000) {
                    uint64_t target = addresses[i] + offset + sign(word & 0x3ffffff, 26) * 4;
                    if (target < addresses[i] || target >= addresses[i] + function.size) {
                        const Edge *edge = nullptr;
                        for (size_t j = 0; j < profile.edgeCount; ++j) {
                            const auto &e = profile.edges[j];
                            if (e.function == i && e.word == offset / 4) { edge = &e; break; }
                        }
                        if (!edge) { reason = "unexpected-call"; return false; }
                        if (edge->symbol) {
                            if (!imported(target, edge->symbol)) { reason = "call-target"; return false; }
                        } else {
                            if (edge->target < 0 || static_cast<size_t>(edge->target) >= addresses.size()) return false;
                            size_t next = static_cast<size_t>(edge->target);
                            if (addresses[next] && addresses[next] != target) { reason = "helper-target"; return false; }
                            const auto &helper = profile.functions[next];
                            if (helper.name) {
                                uint64_t exportAddress = 0, size = 0;
                                if (!find(helper.name, exportAddress, size) || exportAddress != target || size != helper.size) {
                                    reason = "helper-export"; return false;
                                }
                            }
                            addresses[next] = target;
                            if (!queued[next]) { work.push_back(next); queued[next] = true; }
                        }
                        word &= 0xfc000000;
                    }
                } else if ((word & 0x9f000000) == 0x10000000) {
                    uint64_t target = addresses[i] + offset + sign((((word >> 5) & 0x7ffff) << 2) | ((word >> 29) & 3), 21);
                    const uint8_t *location = nullptr;
                    if (i < profile.rootCount || !data(target, 24, location)) { reason = "panic-location"; return false; }
                    word &= 0x9f00001f;
                }
                for (unsigned byte = 0; byte < 4; ++byte) hash = (hash ^ ((word >> (byte * 8)) & 255)) * 0x100000001b3ULL;
            }
            if (hash != function.hash) { reason = function.name ? function.name : "clone/drop-helper"; return false; }
        }
        if (locations) *locations = std::move(addresses);
        reason = "schema-abi-v1"; return true;
    }
    bool compatible(std::string &reason, std::array<uint64_t, std::size(functions)> *locations = nullptr) const {
        std::vector<uint64_t> addresses;
        if (!compatibleUsing(contract, nullptr, reason, locations ? &addresses : nullptr)) return false;
        if (locations) std::copy(addresses.begin(), addresses.end(), locations->begin());
        return true;
    }
};
inline bool load(const char *path, std::vector<uint8_t> &bytes) {
    FILE *file = path ? std::fopen(path, "rb") : nullptr;
    if (!file) return false;
    bool ok = !std::fseek(file, 0, SEEK_END);
    long size = ok ? std::ftell(file) : -1;
    if (size < 64 || size > 4 * 1024 * 1024 || std::fseek(file, 0, SEEK_SET)) ok = false;
    if (ok) { bytes.resize(size); ok = std::fread(bytes.data(), 1, bytes.size(), file) == bytes.size(); }
    std::fclose(file); return ok;
}
}
