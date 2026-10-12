// SPDX-License-Identifier: Apache-2.0
// Independent folder feature entry; the paused Dock observer is unchanged.
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
void start_folder_spacing();
void refresh_home_effects_on_load();

namespace {
void (*original_name)(const char *) = nullptr;
int (*original_property)(const char *, char *) = nullptr;
pid_t entry_pid = 0;
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
    // No thread or allocator activity in the process that will fork launchers.
    // A forked launcher still executes hyos_spawner. Its PID, unlike its exe,
    // differs from the pre-fork entry process. Also exclude the root spawner
    // that may temporarily use the launcher's process name.
    if (launcher() && (!spawner() || getpid() != entry_pid || getuid() >= 10000)) {
        start_folder_spacing();
    }
}
void process_name(const char *name) {
    if (original_name != nullptr) original_name(name);
    if (name != nullptr && std::string_view(name) == "com.miui.home") start_in_child();
}
int property_get(const char *name, char *value) {
    start_in_child();
    return original_property != nullptr ? original_property(name, value) : 0;
}
void loaded(const char *name, void *) {
    start_in_child();
    if (name != nullptr && std::string_view(name).ends_with("libapp.so") && launcher()
        && getuid() >= 10000) refresh_home_effects_on_load();
}
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
Loaded native_init(const NativeApiEntries *api) {
    if (api == nullptr || api->hook_func == nullptr) return nullptr;
    entry_pid = getpid();
    __android_log_print(ANDROID_LOG_INFO, "HyperChangerFolder",
        "Native entry pid=%d uid=%d spawner=%d launcher=%d", entry_pid, getuid(), spawner(), launcher());
    if (spawner() || launcher()) {
        void *target = dlsym(RTLD_DEFAULT, "__system_property_get");
        if (target != nullptr) api->hook_func(target, reinterpret_cast<void *>(property_get),
                                             reinterpret_cast<void **>(&original_property));
        target = dlsym(RTLD_DEFAULT, "setprogname");
        if (target != nullptr) api->hook_func(target, reinterpret_cast<void *>(process_name),
                                             reinterpret_cast<void **>(&original_name));
    }
    start_in_child();
    return loaded;
}
