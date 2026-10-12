// SPDX-License-Identifier: Apache-2.0
#include "folder_spacing.h"
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
alignas(1) std::atomic<uint8_t> material_enabled{0};
alignas(1) std::atomic<uint8_t> material_style{0};
uintptr_t material_return_continue = 0;
void folder_spacing_return();
void material_return_entry();
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
struct MaterialSlot {
    uintptr_t address = 0;
    nhk::CodeSource source{};
    uint32_t branch = 0;
    uint32_t original = 0;
};
std::vector<Slot> slots;
std::optional<MaterialSlot> material_slot;
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

bool write_material_branch(const MaterialSlot &slot, bool enabled) {
    uint32_t current = 0;
    if (!nhk::safe_read(slot.address, std::as_writable_bytes(std::span(&current, 1)))) return false;
    const uint32_t desired = enabled ? slot.branch : slot.original;
    const uint32_t expected = enabled ? slot.original : slot.branch;
    if (current == desired) return true;
    if (current != expected) return false;
    const uintptr_t page = nhk::page_down(slot.address);
    const size_t page_size = nhk::host_page_size();
    if (mprotect(reinterpret_cast<void *>(page), page_size, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) return false;
    __atomic_store_n(reinterpret_cast<uint32_t *>(slot.address), desired, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char *>(slot.address), reinterpret_cast<char *>(slot.address + 4));
    return mprotect(reinterpret_cast<void *>(page), page_size, PROT_READ | PROT_EXEC) == 0;
}

void resolve_material(const std::vector<nhk::ExecutableMapping> &mappings, bool enabled) {
    if (!enabled && material_slot) {
        write_material_branch(*material_slot, false);
        material_slot.reset();
        material_return_continue = 0;
    }
    if (!enabled || material_slot) return;
    // Verified against launcher-8.01.02.7722: mov x0,x1 immediately before
    // the shared Dart epilogue of BlurUtilities.isGlassMaterialSupported.
    constexpr uintptr_t kResultRva = 0xb46658;
    constexpr uint32_t kOriginal = 0xaa0103e0;
    for (const auto &generation : nhk::runtime_generations(mappings)) {
        const auto key = nhk::generation_key(generation.front());
        if (!key) continue;
        const uintptr_t address = static_cast<uintptr_t>(key->load_bias) + kResultRva;
        uint32_t current = 0;
        if (!nhk::safe_read(address, std::as_writable_bytes(std::span(&current, 1))) || current != kOriginal) continue;
        const auto source = source_at(mappings, address);
        if (!source) continue;
        const auto branch = allocate_branch(address, reinterpret_cast<uintptr_t>(material_return_entry));
        if (!branch) continue;
        material_return_continue = address + 4;
        MaterialSlot candidate{address, *source, *branch, kOriginal};
        if (write_material_branch(candidate, true)) {
            material_slot = candidate;
            __android_log_print(ANDROID_LOG_INFO, kTag, "Desktop material selector installed at %p", reinterpret_cast<void *>(address));
        }
        return;
    }
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

void *worker(void *) {
    pthread_setname_np(pthread_self(), "hc-folder");
    int previous = -1000;
    uint32_t previous_material = UINT32_MAX;
    unsigned tick = 0;
    while (true) {
        const auto snapshot = read_config();
        const auto &settings = snapshot.spacing;
        const auto &material = snapshot.material;
        material_enabled.store(material.enabled ? 1 : 0, std::memory_order_release);
        material_style.store(static_cast<uint8_t>(material.style > 0 ? 1 : 0), std::memory_order_release);
        const int value = settings.enabled ? settings.value : folder_spacing::kDisabledValue;
        folder_spacing_value.store(value, std::memory_order_release);
        if (value != previous) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "Row spacing enabled=%d value=%d dp", settings.enabled, settings.value);
            previous = value;
            tick = 0;
        }
        const uint32_t material_state = (material.enabled ? 0x10000u : 0u) | material.style;
        if (material_state != previous_material) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "Desktop material enabled=%d style=%u glass=%08x/%08x advanced=%08x/%08x", material.enabled, material.style, material.glass_color, material.glass, material.advanced_color, material.advanced);
            previous_material = material_state;
        }
        if (settings.enabled || !slots.empty() || material.enabled || material_slot) {
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
            resolve_material(mappings, material.enabled);
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
