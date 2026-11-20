// SPDX-License-Identifier: AGPL-3.0-or-later
// Provider capability approach and ABI contracts adapted from HyperModifier (GPL-3.0).
#include "widget_capabilities.h"
#include "schema_abi.h"
#include <android/log.h>
#include <atomic>
#include <dlfcn.h>
#include <link.h>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <unordered_set>
#include <string_view>

namespace {
constexpr const char *tag = "HyperChangerWidgets";
using Getter = void *(*)(void *);
using Drop = void (*)(void *);
using Boolean = uint64_t (*)(void *, const char *, size_t);
using Contains = bool (*)(void *, const char *, size_t);
void *original_boolean = nullptr, *original_contains = nullptr, *original_clone = nullptr;
void *original_drop = nullptr, *original_activity = nullptr, *original_item = nullptr;
void *original_activity_drop = nullptr, *original_item_drop = nullptr;
WidgetHook backend = nullptr;
std::atomic<void *> launcher_handle{nullptr};
std::atomic<uint32_t> enabled{0};
bool attempted = false, ready = false;
std::mutex registry_mutex;
std::unordered_map<void *, void *> owners;
std::unordered_map<void *, size_t> borrowed;
std::unordered_set<void *> clones;

void forget_owner(void *owner) {
    auto entry = owners.find(owner);
    if (entry == owners.end()) return;
    auto bundle = borrowed.find(entry->second);
    if (bundle != borrowed.end() && --bundle->second == 0) borrowed.erase(bundle);
    owners.erase(entry);
}
bool is_widget(void *bundle) {
    std::lock_guard lock(registry_mutex);
    return borrowed.contains(bundle) || clones.contains(bundle);
}
void *metadata(void *info, void *original) {
    void *bundle = reinterpret_cast<Getter>(original)(info);
    constexpr char key[] = "android.appwidget.provider";
    const bool provider = bundle && reinterpret_cast<Contains>(original_contains)(bundle, key, sizeof(key) - 1);
    std::lock_guard lock(registry_mutex);
    forget_owner(info);
    if (provider) { owners[info] = bundle; ++borrowed[bundle]; }
    return bundle;
}
void *activity_metadata(void *info) { return metadata(info, original_activity); }
void *item_metadata(void *info) { return metadata(info, original_item); }
void *clone_bundle(void *bundle) {
    void *copy = reinterpret_cast<Getter>(original_clone)(bundle);
    std::lock_guard lock(registry_mutex);
    if (copy && copy != bundle) {
        clones.erase(copy);
        if (borrowed.contains(bundle) || clones.contains(bundle)) clones.insert(copy);
    }
    return copy;
}
void drop_bundle(void *bundle) {
    { std::lock_guard lock(registry_mutex); clones.erase(bundle); }
    reinterpret_cast<Drop>(original_drop)(bundle);
}
void drop_info(void *info, void *getter, void *drop) {
    if (!info) { reinterpret_cast<Drop>(drop)(info); return; }
    void *bundle = reinterpret_cast<Getter>(getter)(info);
    {
        std::lock_guard lock(registry_mutex);
        if (borrowed.erase(bundle)) {
            for (auto it = owners.begin(); it != owners.end();) {
                if (it->second == bundle) it = owners.erase(it); else ++it;
            }
        }
        forget_owner(info);
    }
    reinterpret_cast<Drop>(drop)(info);
}
void drop_activity(void *info) { drop_info(info, original_activity, original_activity_drop); }
void drop_item(void *info) { drop_info(info, original_item, original_item_drop); }
bool key_is(const char *key, size_t size, const char *expected) {
    return key && size == std::strlen(expected) && std::memcmp(key, expected, size) == 0;
}
uint64_t get_boolean(void *bundle, const char *key, size_t size) {
    const uint32_t flags = enabled.load(std::memory_order_acquire);
    if (((flags & 6) && key_is(key, size, "miuiWidget"))
        || ((flags & 4) && key_is(key, size, "supportBackgroundBlur"))) {
        if (is_widget(bundle)) return 0x100; // Reviewed Rust Result<bool>: Ok(true).
    }
    return reinterpret_cast<Boolean>(original_boolean)(bundle, key, size);
}
bool contains(void *bundle, const char *key, size_t size) {
    if ((enabled.load(std::memory_order_acquire) & 4) && key_is(key, size, "supportBackgroundBlur") && is_widget(bundle)) return true;
    // Preserve original miuiWidget presence so the Android picker stays Android.
    return reinterpret_cast<Contains>(original_contains)(bundle, key, size);
}

struct Range { uintptr_t begin; size_t size; };
struct Source {
    uintptr_t base;
    std::vector<uint8_t> bytes;
    schema_abi::Image image;
    std::vector<Range> ranges;
    explicit Source(uintptr_t b) : base(b), image(bytes) {}
};
bool verified(void *handle, const std::vector<const char *> &symbols) {
    std::vector<std::unique_ptr<Source>> sources;
    size_t total = 0;
    for (const char *name : symbols) {
        void *symbol = dlsym(handle, name); Dl_info origin{};
        if (!symbol || !dladdr(symbol, &origin) || !origin.dli_fbase || !origin.dli_fname) return false;
        const uintptr_t base = reinterpret_cast<uintptr_t>(origin.dli_fbase);
        Source *source = nullptr;
        for (const auto &candidate : sources) if (candidate->base == base) source = candidate.get();
        if (!source) {
            auto added = std::make_unique<Source>(base);
            if (!schema_abi::load(origin.dli_fname, added->bytes) || !added->image.init()
                || added->bytes.size() > 8 * 1024 * 1024 - total) return false;
            total += added->bytes.size();
            dl_iterate_phdr([](dl_phdr_info *info, size_t, void *data) {
                auto &s = *static_cast<Source *>(data);
                if (info->dlpi_addr != s.base) return 0;
                for (size_t i = 0; i < info->dlpi_phnum; ++i) {
                    const auto &p = info->dlpi_phdr[i];
                    if (p.p_type == PT_LOAD && (p.p_flags & (PF_R | PF_X)) == (PF_R | PF_X) && !(p.p_flags & PF_W))
                        s.ranges.push_back({s.base + p.p_vaddr, p.p_memsz});
                }
                return 1;
            }, added.get());
            source = added.get(); sources.push_back(std::move(added));
        }
        const schema_abi::Contract *matched = nullptr;
        std::vector<uint64_t> locations;
        std::string reason;
        for (auto profile : {&schema_abi::contract})
            if (source->image.compatibleUsing(*profile, name, reason, &locations)) { matched = profile; break; }
        if (!matched) {
            __android_log_print(ANDROID_LOG_WARN, tag, "ABI rejected %s: %s", name, reason.c_str());
            return false;
        }
        uint64_t rva = 0, size = 0;
        if (!source->image.find(name, rva, size) || rva > UINTPTR_MAX - base
            || base + rva != reinterpret_cast<uintptr_t>(symbol)) return false;
        for (size_t i = 0; i < locations.size(); ++i) if (locations[i]) {
            const uint8_t *file = nullptr;
            const size_t bytes = matched->functions[i].size;
            if (locations[i] > UINTPTR_MAX - base || !source->image.code(locations[i], bytes, file)) return false;
            const uintptr_t address = base + locations[i];
            bool readable = false;
            for (const auto &r : source->ranges)
                if (address >= r.begin && address - r.begin <= r.size && bytes <= r.size - (address - r.begin)) readable = true;
            if (!readable || std::memcmp(reinterpret_cast<void *>(address), file, bytes)) return false;
        }
    }
    return true;
}
}

void initialize_widget_backend(WidgetHook hook) { backend = hook; }
void note_widget_library(const char *name, void *handle) {
    if (name && handle && std::string_view(name).ends_with("libapp_launcher.so"))
        launcher_handle.store(handle, std::memory_order_release);
}
bool update_widget_capabilities(uint32_t flags, bool picker_ready) {
    // This runs only in the existing serialized config observer/load callback.
    enabled.store(ready && picker_ready ? flags : 0, std::memory_order_release);
    if (!(flags & 6) || attempted || !backend) return ready;
    void *handle = launcher_handle.load(std::memory_order_acquire);
    const bool owned = !handle;
    if (!handle) handle = dlopen("libapp_launcher.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) return false; // Loader callback/next existing observer pass can retry.
    attempted = true;
    struct Entry { const char *name; void *hook; void **original; };
    const Entry entries[] = {
        {"Bundle_contains_key", reinterpret_cast<void *>(contains), &original_contains},
        {"Bundle_get_boolean", reinterpret_cast<void *>(get_boolean), &original_boolean},
        {"Bundle_clone", reinterpret_cast<void *>(clone_bundle), &original_clone},
        {"Bundle_drop", reinterpret_cast<void *>(drop_bundle), &original_drop},
        {"ActivityInfo_drop", reinterpret_cast<void *>(drop_activity), &original_activity_drop},
        {"PackageItemInfo_drop", reinterpret_cast<void *>(drop_item), &original_item_drop},
        {"ActivityInfo_get_meta_data", reinterpret_cast<void *>(activity_metadata), &original_activity},
        {"PackageItemInfo_get_meta_data", reinterpret_cast<void *>(item_metadata), &original_item},
    };
    std::vector<const char *> symbols;
    for (const auto &entry : entries) symbols.push_back(entry.name);
    if (!verified(handle, symbols)) {
        __android_log_write(ANDROID_LOG_WARN, tag, "Widget ABI unavailable; original capabilities retained");
        if (owned) dlclose(handle);
        return false;
    }
    // Publish originals before installing owner-drop hooks; they may execute immediately.
    original_activity = dlsym(handle, "ActivityInfo_get_meta_data");
    original_item = dlsym(handle, "PackageItemInfo_get_meta_data");
    for (const auto &entry : entries) {
        if (backend(dlsym(handle, entry.name), entry.hook, entry.original) != 0 || !*entry.original) {
            __android_log_print(ANDROID_LOG_WARN, tag, "Widget hook rejected: %s", entry.name);
            if (owned) dlclose(handle);
            return false;
        }
    }
    ready = true;
    enabled.store(picker_ready ? flags : 0, std::memory_order_release);
    __android_log_write(ANDROID_LOG_INFO, tag, "Provider capability hooks ready");
    // Keep the dlopen reference: installed original trampolines depend on this image.
    return true;
}
