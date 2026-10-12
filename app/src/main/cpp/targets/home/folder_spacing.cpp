// SPDX-License-Identifier: Apache-2.0
#include "folder_spacing.h"
#include "home_effects.h"
#include "nativehook/memory_io.h"
#include "nativehook/native_image.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <optional>
#include <pthread.h>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

extern "C" {
alignas(4) std::atomic<int32_t> folder_spacing_value{folder_spacing::kDisabledValue};
void folder_spacing_return();
}
static_assert(sizeof(folder_spacing_value) == sizeof(int32_t));
static_assert(std::atomic<int32_t>::is_always_lock_free);

namespace {
constexpr char kTag[] = "HyperChangerFolder";
constexpr size_t kMaxSlots = 32;
std::atomic_bool started{false};
struct Slot {
    uintptr_t address;
    nhk::CodeSource source;
    uint32_t branch;
};
std::vector<Slot> slots;
struct EffectSlot {
    uintptr_t address;
    nhk::CodeSource source;
    uint32_t original;
    uint32_t branch;
    uint64_t *data;
    size_t parameter;
    bool dock;
};
std::vector<EffectSlot> effect_slots;
std::map<std::string, std::optional<std::pair<uint64_t, uint64_t>>> zip_cache;

std::optional<std::span<const std::byte>> read_blob(std::array<std::byte, 512> &buffer, std::streamsize &size) {
    // Launcher UID can read its own atomically published file. Resolve the active
    // Android user from UID; never depend on root access inside the launcher.
    const std::string path = "/data/user/" + std::to_string(getuid() / 100000)
        + "/com.miui.home/files/hyperpatch.bin";
    std::ifstream file(path, std::ios::binary);
    if (!file) return {};
    file.read(reinterpret_cast<char *>(buffer.data()), buffer.size());
    size = file.gcount();
    if (size <= 0 || size == static_cast<std::streamsize>(buffer.size())) return {};
    return std::span(buffer).first(static_cast<size_t>(size));
}

struct ConfigSnapshot {
    folder_spacing::Settings spacing;
    folder_spacing::MaterialSettings material;
};

ConfigSnapshot read_config() {
    std::array<std::byte, 512> buffer{};
    std::streamsize size = 0;
    const auto blob = read_blob(buffer, size);
    if (!blob) return {};
    return {folder_spacing::read_settings(*blob), folder_spacing::read_material_settings(*blob)};
}

std::vector<nhk::ExecutableMapping> inventory() {
    std::ifstream maps("/proc/self/maps");
    const auto entries = nhk::parse_file_mappings(maps);
    std::vector<nhk::ImageContainer> containers;
    for (const auto &entry : entries) {
        const std::string path(nhk::strip_deleted(entry.path));
        if (path.ends_with("/libapp.so")) {
            containers.push_back({path, 0, UINT64_MAX});
        } else if (path.ends_with(".apk")) {
            auto cached = zip_cache.find(path);
            if (cached == zip_cache.end())
                cached = zip_cache.emplace(path, nhk::zip_stored_entry(path, "libapp.so")).first;
            if (cached->second) {
                const auto [start, size] = *cached->second;
                containers.push_back({path, nhk::page_down(start), nhk::page_up(start + size)});
            }
        }
    }
    std::sort(containers.begin(), containers.end(), [](const auto &a, const auto &b) {
        return a.path < b.path;
    });
    containers.erase(std::unique(containers.begin(), containers.end(), [](const auto &a, const auto &b) {
        return a.path == b.path && a.view_begin == b.view_begin && a.view_end == b.view_end;
    }), containers.end());
    return nhk::owned_image_mappings(entries, containers);
}

std::optional<nhk::CodeSource> source_at(const std::vector<nhk::ExecutableMapping> &maps,
                                      uintptr_t address) {
    for (const auto &mapping : maps)
        if (auto source = mapping.source_at(address, sizeof(uint32_t))) return source;
    return {};
}

// One atomic B instruction replaces MOV X0,X22. A permanent, nearby island uses
// an absolute jump to our complete epilogue. No four-word torn patch, relocated
// Dart instructions, saved original trampoline, or retained heap pointers.
std::optional<uint32_t> allocate_branch(uintptr_t target, uintptr_t destination) {
    const size_t page_size = nhk::host_page_size();
    constexpr intptr_t kReach = 128 * 1024 * 1024;
    for (intptr_t distance = 1024 * 1024; distance < kReach; distance += 1024 * 1024) {
        for (const intptr_t direction : {-1, 1}) {
            const intptr_t hint = static_cast<intptr_t>(nhk::page_down(target)) + direction * distance;
            if (hint <= 0) continue;
            void *page = mmap(reinterpret_cast<void *>(hint), page_size,
                              PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (page == MAP_FAILED) continue;
            const uintptr_t address = reinterpret_cast<uintptr_t>(page);
            const intptr_t relative = static_cast<intptr_t>(address) - static_cast<intptr_t>(target);
            if (relative < -kReach || relative >= kReach || relative % 4 != 0) {
                munmap(page, page_size);
                continue;
            }
            const std::array<uint32_t, 4> island{
                0x58000050, 0xd61f0200, // ldr x16, [pc,#8]; br x16
                static_cast<uint32_t>(destination), static_cast<uint32_t>(destination >> 32)};
            std::memcpy(page, island.data(), sizeof(island));
            __builtin___clear_cache(static_cast<char *>(page), static_cast<char *>(page) + sizeof(island));
            if (mprotect(page, page_size, PROT_READ | PROT_EXEC) != 0) {
                munmap(page, page_size);
                continue;
            }
            // The page remains mapped for process life, including stale image aliases.
            return 0x14000000 | (static_cast<uint32_t>(relative / 4) & 0x03ffffff);
        }
    }
    return {};
}

std::optional<uint32_t> allocate_branch(uintptr_t target) {
    return allocate_branch(target, reinterpret_cast<uintptr_t>(folder_spacing_return));
}

bool write_branch(const Slot &slot, bool enabled = true) {
    uint32_t current = 0;
    if (!nhk::safe_read(slot.address, std::as_writable_bytes(std::span(&current, 1)))) return false;
    const uint32_t desired = enabled ? slot.branch : folder_spacing::kReturnStart;
    const uint32_t expected = enabled ? folder_spacing::kReturnStart : slot.branch;
    if (current == desired) return true;
    if (current != expected) return false;
    const uintptr_t page = nhk::page_down(slot.address);
    const size_t page_size = nhk::host_page_size();
    void *base = reinterpret_cast<void *>(page);
    if (mprotect(base, page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    __atomic_store_n(reinterpret_cast<uint32_t *>(slot.address), desired, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char *>(slot.address), reinterpret_cast<char *>(slot.address + 4));
    const bool protected_again = mprotect(base, page_size, PROT_READ | PROT_EXEC) == 0;
    if (!protected_again)
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Cannot restore code page permissions");
    return protected_again;
}

void resolve(const std::vector<nhk::ExecutableMapping> &mappings) {
    for (const auto &generation : nhk::runtime_generations(mappings)) {
        const size_t known = std::ranges::count_if(slots, [&](const Slot &slot) {
            return source_at(generation, slot.address) == slot.source;
        });
        if (known == 2) continue;
        std::vector<uintptr_t> matches;
        size_t total = 0;
        for (const auto &mapping : generation) total += mapping.end - mapping.begin;
        if (total > nhk::kMaxExecutableCodeBytes) continue;
        for (const auto &mapping : generation) {
            const size_t bytes = mapping.end - mapping.begin;
            std::vector<uint32_t> code(bytes / sizeof(uint32_t));
            if (!nhk::safe_read(mapping.begin, std::as_writable_bytes(std::span(code)))) continue;
            for (const auto &slot : slots) {
                if (mapping.source_at(slot.address, 4) != slot.source) continue;
                const size_t at = (slot.address - mapping.begin) / 4;
                if (code[at] == slot.branch) code[at] = folder_spacing::kReturnStart;
            }
            for (size_t i = 0; i + folder_spacing::kFunctionWords <= code.size(); ++i) {
                if (code[i] == 0xa9bf79fd && folder_spacing::matches(std::span(code).subspan(i)))
                    matches.push_back(mapping.begin + i * sizeof(uint32_t));
            }
        }
        // One calItemSpacing per generation. Ambiguity never patches any candidate.
        if (matches.size() != 1 || slots.size() + 2 > kMaxSlots) continue;
        const auto fresh = inventory();
        std::array<uint32_t, folder_spacing::kFunctionWords> live{};
        if (!nhk::safe_read(matches[0], std::as_writable_bytes(std::span(live)))) continue;
        for (const auto &slot : slots) {
            if (slot.address < matches[0] || slot.address >= matches[0] + sizeof(live)
                || source_at(fresh, slot.address) != slot.source) continue;
            const size_t at = (slot.address - matches[0]) / 4;
            if (live[at] == slot.branch) live[at] = folder_spacing::kReturnStart;
        }
        if (!folder_spacing::matches(live)) continue;
        for (const size_t index : folder_spacing::kReturnIndices) {
            const uintptr_t address = matches[0] + index * 4;
            const auto source = source_at(generation, address);
            if (!source || source_at(fresh, address) != source) continue;
            if (std::ranges::any_of(slots, [&](const Slot &slot) {
                return slot.address == address && slot.source == *source;
            })) continue;
            const auto branch = allocate_branch(address);
            if (!branch) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "No nearby branch island available");
                continue;
            }
            Slot slot{address, *source, *branch};
            // Revalidate mapping ownership immediately before writing the instruction.
            if (source_at(inventory(), address) != source) continue;
            slots.push_back(slot); // retain branch for healing even if protection fails
            if (write_branch(slot))
                __android_log_print(ANDROID_LOG_INFO, kTag, "Absolute row spacing installed at %p", reinterpret_cast<void *>(address));
        }
    }
}

std::optional<EffectSlot> make_effect(uintptr_t target, const nhk::CodeSource &source,
        uint32_t original, size_t parameter, uint32_t reg, bool ior, bool dock) {
    const size_t page_size = nhk::host_page_size();
    constexpr intptr_t kReach = 128 * 1024 * 1024;
    for (intptr_t distance = 1024 * 1024; distance < kReach; distance += 1024 * 1024) {
        for (const intptr_t direction : {-1, 1}) {
            const intptr_t hint = static_cast<intptr_t>(nhk::page_down(target)) + direction * distance;
            if (hint <= 0) continue;
            void *page = mmap(reinterpret_cast<void *>(hint), page_size * 2,
                PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (page == MAP_FAILED) continue;
            const uintptr_t address = reinterpret_cast<uintptr_t>(page);
            const intptr_t relative = static_cast<intptr_t>(address) - static_cast<intptr_t>(target);
            if (relative <= -kReach + 128 || relative >= kReach - 128 || relative % 4 != 0) {
                munmap(page, page_size * 2);
                continue;
            }
            const auto code = home_effects::island_program(address, address + page_size,
                original, reg, ior, dock, target + 4);
            std::memcpy(page, code.data(), code.size() * 4);
            __builtin___clear_cache(static_cast<char *>(page), static_cast<char *>(page) + code.size() * 4);
            if (mprotect(page, page_size, PROT_READ | PROT_EXEC) != 0) {
                munmap(page, page_size * 2);
                continue;
            }
            // Never free an installed island: another Dart thread may still be
            // executing a pre-disable branch. Its data and continuation survive.
            return EffectSlot{target, source, original,
                0x14000000 | (static_cast<uint32_t>(relative / 4) & 0x03ffffff),
                reinterpret_cast<uint64_t *>(address + page_size), parameter, dock};
        }
    }
    return {};
}

bool write_effect(const EffectSlot &slot, bool enabled) {
    uint32_t current = 0;
    if (!nhk::safe_read(slot.address, std::as_writable_bytes(std::span(&current, 1)))) return false;
    const uint32_t desired = enabled ? slot.branch : slot.original;
    if (current == desired) return true;
    if (current != (enabled ? slot.original : slot.branch)) return false;
    const uintptr_t page = nhk::page_down(slot.address);
    const size_t size = nhk::host_page_size();
    if (mprotect(reinterpret_cast<void *>(page), size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    __atomic_store_n(reinterpret_cast<uint32_t *>(slot.address), desired, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char *>(slot.address), reinterpret_cast<char *>(slot.address + 4));
    return mprotect(reinterpret_cast<void *>(page), size, PROT_READ | PROT_EXEC) == 0;
}

void publish_effect(const EffectSlot &slot, const folder_spacing::MaterialSettings &settings) {
    const bool enabled = slot.dock ? settings.dock_enabled : settings.enabled;
    if (!enabled) __atomic_store_n(slot.data, uint64_t{0}, __ATOMIC_RELEASE);
    const double value = slot.dock ? settings.dock_offset : settings.percent[slot.parameter] / 100.0;
    __atomic_store_n(slot.data + 1, std::bit_cast<uint64_t>(value), __ATOMIC_RELEASE);
    if (enabled) __atomic_store_n(slot.data, uint64_t{1}, __ATOMIC_RELEASE);
}

void update_effects(const std::vector<nhk::ExecutableMapping> &mappings,
        const folder_spacing::MaterialSettings &settings, unsigned tick) {
    for (const auto &slot : effect_slots) {
        publish_effect(slot, settings); // disable gate before restoring code
        if (source_at(mappings, slot.address) == slot.source)
            write_effect(slot, slot.dock ? settings.dock_enabled : settings.enabled);
    }
    if ((!settings.enabled && !settings.dock_enabled) || tick % 10 != 0) return;
    for (const auto &generation : nhk::runtime_generations(mappings)) {
        size_t total = 0;
        for (const auto &mapping : generation) total += mapping.end - mapping.begin;
        if (total > nhk::kMaxExecutableCodeBytes) continue;
        const auto known = [&](bool dock) {
            return std::ranges::count_if(effect_slots, [&](const EffectSlot &slot) {
                return slot.dock == dock && source_at(generation, slot.address) == slot.source;
            });
        };
        const bool need_glass = settings.enabled && known(false) != 6;
        const bool need_dock = settings.dock_enabled && known(true) != 1;
        if (!need_glass && !need_dock) continue;
        std::vector<uintptr_t> glass, dock;
        for (const auto &mapping : generation) {
            std::vector<uint32_t> code((mapping.end - mapping.begin) / 4);
            if (!nhk::safe_read(mapping.begin, std::as_writable_bytes(std::span(code)))) continue;
            for (const auto &slot : effect_slots) {
                if (mapping.source_at(slot.address, 4) != slot.source) continue;
                const size_t at = (slot.address - mapping.begin) / 4;
                if (code[at] == slot.branch) code[at] = slot.original;
            }
            for (size_t i = 0; i < code.size(); ++i) {
                if (code[i] != 0xa9bf79fd) continue;
                const auto candidate = std::span(code).subspan(i);
                if (need_glass && home_effects::matches_glass(candidate)) glass.push_back(mapping.begin + i * 4);
                if (need_dock && home_effects::matches_dock(candidate)) dock.push_back(mapping.begin + i * 4);
            }
        }
        // Refuse unsupported/ambiguous bodies; never derive an address from
        // load_bias, which includes the ZIP container offset for APK images.
        const auto install = [&](uintptr_t base, auto shape, size_t index,
                size_t parameter, uint32_t reg, bool ior, bool is_dock) {
            const uintptr_t address = base + index * 4;
            const auto source = source_at(generation, address);
            if (!source || effect_slots.size() >= 64) return;
            if (std::ranges::any_of(effect_slots, [&](const EffectSlot &s) {
                return s.address == address && s.source == *source;
            })) return;
            const auto fresh = inventory();
            if (source_at(fresh, address) != source) return;
            std::vector<uint32_t> live(shape.size());
            if (!nhk::safe_read(base, std::as_writable_bytes(std::span(live)))) return;
            for (const auto &s : effect_slots) {
                if (s.address >= base && s.address < base + live.size() * 4 && source_at(fresh, s.address) == s.source) {
                    const size_t at = (s.address - base) / 4;
                    if (live[at] == s.branch) live[at] = s.original;
                }
            }
            if (!(is_dock ? home_effects::matches_dock(live) : home_effects::matches_glass(live))) return;
            auto slot = make_effect(address, *source, live[index], parameter, reg, ior, is_dock);
            if (!slot) return;
            publish_effect(*slot, settings);
            if (source_at(inventory(), address) != source) return;
            effect_slots.push_back(*slot);
            if (write_effect(*slot, true)) __android_log_print(ANDROID_LOG_INFO, kTag,
                "%s parameter %zu installed at %p", is_dock ? "Dock icon offset" : "Soft glass", parameter, reinterpret_cast<void *>(address));
        };
        if (glass.size() == 1) for (const auto &field : home_effects::kGlassFields)
            install(glass[0], std::span(home_effects::kGlassShape), field.index, field.parameter, field.reg, field.refractive_index, false);
        if (dock.size() == 1) install(dock[0], std::span(home_effects::kDockShape), home_effects::kDockReturnIndex, 0, 0, false, true);
    }
}

void *worker(void *) {
    pthread_setname_np(pthread_self(), "hc-folder");
    int previous = -1000;
    bool previous_glass = false;
    bool previous_dock = false;
    unsigned tick = 0;
    while (true) {
        const auto snapshot = read_config();
        const auto &settings = snapshot.spacing;
        const auto &material = snapshot.material;
        if (material.enabled != previous_glass || material.dock_enabled != previous_dock) {
            tick = 0;
            __android_log_print(ANDROID_LOG_INFO, kTag, "Soft glass enabled=%d; Dock icon offset enabled=%d",
                material.enabled, material.dock_enabled);
            previous_glass = material.enabled;
            previous_dock = material.dock_enabled;
        }
        const int value = settings.enabled ? settings.value : folder_spacing::kDisabledValue;
        folder_spacing_value.store(value, std::memory_order_release);
        if (value != previous) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "Row spacing enabled=%d value=%d dp", settings.enabled, settings.value);
            previous = value;
            tick = 0;
        }
        if (settings.enabled || !slots.empty() || material.enabled || material.dock_enabled || !effect_slots.empty()) {
            const auto mappings = inventory();
            for (const auto &slot : slots) {
                // Restore discarded COW code only when the original instruction and
                // backing file identity still match. Unknown code is never overwritten.
                if (source_at(mappings, slot.address) == slot.source) write_branch(slot, settings.enabled);
            }
            // The controller may calculate its gap during initial layout. A
            // five-second first retry misses that initialization completely.
            if (settings.enabled && (slots.empty() || tick % 10 == 0)) resolve(mappings);
            if (settings.enabled && slots.empty() && tick % 120 == 0)
                __android_log_print(ANDROID_LOG_WARN, kTag,
                    "Folder resolver pending: mappings=%zu value=%d", mappings.size(), settings.value);
            update_effects(mappings, material, tick);
        }
        ++tick;
        usleep(settings.enabled && slots.empty() ? 50000 : 500000);
    }
    return nullptr;
}
} // namespace

void start_folder_spacing() {
    if (started.exchange(true)) return;
    pthread_t thread{};
    if (pthread_create(&thread, nullptr, worker, nullptr) != 0) {
        started.store(false);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Cannot start folder observer");
    } else {
        pthread_detach(thread);
        __android_log_print(ANDROID_LOG_INFO, kTag, "Folder spacing observer registered");
    }
}
