// SPDX-License-Identifier: AGPL-3.0-or-later
// HyperOS native loader ABI and specialization strategy adapted from HyperCeiler.
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string_view>

using Hook = int (*)(void *, void *, void **);
using Unhook = int (*)(void *);
using Loaded = void (*)(const char *, void *);
struct NativeApiEntries { uint32_t version; Hook hook_func; Unhook unhook_func; };
void start_dock_native_motion(Hook, Unhook);

namespace {
Hook hook = nullptr;
Unhook unhook = nullptr;
void (*original_setprogname)(const char *) = nullptr;
int (*original_property_get)(const char *, char *) = nullptr;
std::atomic<int> process_kind{0};

bool launcher() {
    char name[128]{};
    FILE *file = std::fopen("/proc/self/cmdline", "r");
    if (file == nullptr) return false;
    const size_t size = std::fread(name, 1, sizeof(name) - 1, file);
    std::fclose(file);
    return size > 0 && std::string_view(name) == "com.miui.home";
}
bool spawner() {
    char path[128]{};
    const auto size = readlink("/proc/self/exe", path, sizeof(path) - 1);
    return size > 0 && std::string_view(path, size) == "/system_ext/bin/hyos_spawner";
}
void start_in_child() {
    if (spawner()) return; // Never create threads in the pre-fork runtime.
    if (process_kind.load(std::memory_order_acquire) == 2) return;
    if (launcher()) {
        process_kind.store(1, std::memory_order_release);
        start_dock_native_motion(hook, unhook);
    }
}
void process_name(const char *name) {
    if (original_setprogname != nullptr) original_setprogname(name);
    if (name != nullptr && std::string_view(name) == "com.miui.home") start_in_child();
    else if (name != nullptr && std::string_view(name) != "usap64"
             && std::string_view(name) != "hyos_spawner") process_kind.store(2);
}
int property_get(const char *name, char *value) {
    start_in_child();
    return original_property_get != nullptr ? original_property_get(name, value) : 0;
}
void loaded(const char *, void *) { start_in_child(); }
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
Loaded native_init(const NativeApiEntries *api) {
    if (api == nullptr || api->hook_func == nullptr) return nullptr;
    hook = api->hook_func;
    unhook = api->unhook_func;
    if (spawner() || launcher()) {
        void *target = dlsym(RTLD_DEFAULT, "__system_property_get");
        if (target != nullptr) hook(target, reinterpret_cast<void *>(property_get),
                                    reinterpret_cast<void **>(&original_property_get));
        target = dlsym(RTLD_DEFAULT, "setprogname");
        if (target != nullptr) hook(target, reinterpret_cast<void *>(process_name),
                                    reinterpret_cast<void **>(&original_setprogname));
        __android_log_print(ANDROID_LOG_INFO, "HyperChangerDockNative", "Runtime animation observer registered");
    }
    start_in_child();
    return loaded;
}
