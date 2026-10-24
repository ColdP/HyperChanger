// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <android/log.h>
#include <unistd.h>
#include <cstdio>
#include <cstdarg>

// Launcher-owned file, published atomically with UID/SELinux label by the existing transport.
// No symbol/RVA or target ABI changes. Missing flag preserves existing installations.
inline unsigned hc_native_flags() {
    char path[128];
    std::snprintf(path, sizeof(path), "/data/user/%u/com.miui.home/files/hyperchanger-safety",
                  static_cast<unsigned>(getuid() / 100000));
    FILE *file = std::fopen(path, "r");
    if (file == nullptr) return 0;
    const int value = std::fgetc(file);
    std::fclose(file);
    return value >= '0' && value <= '7' ? static_cast<unsigned>(value - '0') : 0;
}
inline bool hc_native_features_allowed() { return (hc_native_flags() & 1U) == 0; }
inline int hc_native_log(int priority, const char *tag, const char *format, ...) {
    const auto flags = hc_native_flags();
    if ((flags & 2U) != 0 || (priority < ANDROID_LOG_INFO && (flags & 4U) == 0)) return 0;
    va_list args;
    va_start(args, format);
    const int result = __android_log_vprint(priority, tag, format, args);
    va_end(args);
    return result;
}
#define __android_log_print hc_native_log
