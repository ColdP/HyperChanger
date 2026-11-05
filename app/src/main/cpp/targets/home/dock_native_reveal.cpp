// SPDX-License-Identifier: AGPL-3.0-or-later
#include "dock_reveal_follow.h"
#include "dock_reveal_profiles.h"
#include "dock_reveal_session.h"
#include <bit>
#include "nativehook/memory_io.h"
#include "nativehook/native_image.h"
#include "module_safety.h"
#include <array>
#include <atomic>
#include <cstring>
#include <elf.h>
#include <fstream>
#include <link.h>
#include <pthread.h>
#include <span>
#include <string>
#include <time.h>
#include <unistd.h>

extern "C" {
alignas(8) std::atomic<uint64_t> dock_reveal_native_id{0};
alignas(8) std::atomic<uint64_t> dock_reveal_icon_y{0}, dock_reveal_icon_alpha{0}, dock_reveal_icon_samples{0};
uintptr_t dock_reveal_clip_vtable = 0;
uintptr_t dock_reveal_backdrop_vtable = 0;
uint32_t dock_reveal_cid_delta = 0;
void *dock_reveal_capture_original = nullptr;
void dock_reveal_capture_entry();
void *dock_reveal_icon_original = nullptr;
void dock_reveal_icon_entry();
}
static_assert(sizeof(dock_reveal_native_id) == 8 && std::atomic<uint64_t>::is_always_lock_free);

namespace {
constexpr char kTag[] = "HyperChangerDockReveal";
using Hook = int (*)(void *, void *, void **);
Hook install_hook = nullptr;
std::atomic_bool started{false}, ready{false}, enabled{false}, keyguard_locked{false};
std::atomic<uint64_t> end_ns{0}, epoch{0}, paint_hits{0};
std::atomic<uint64_t> begin_ns{0}, sample_floor{0}, restore_epoch{0};
dock_reveal::FollowFrames submitted_frames;
thread_local dock_reveal::FollowPose frame_pose;
thread_local uint64_t frame_epoch = 0;
thread_local bool restored_background = false;
using Paint = void (*)(void *, void *);
using Raster = int (*)(void *, void *, bool, void *, int64_t);
using Render = void (*)(void *, int64_t, void *, float);
using Keyguard = void (*)(void *, bool);
using StackSave = void (*)(void *);
using StackSaveLayer = void (*)(void *, const void *);
using StackTranslate = void (*)(void *, float, float);
using StackOpacity = void (*)(void *, const void *, float);
using ScheduleFrame = void (*)(void *, bool);
Paint original_paint = nullptr;
Raster original_raster = nullptr;
Render original_render = nullptr;
Keyguard original_keyguard = nullptr;
StackSave save_state = nullptr;
StackSaveLayer save_layer = nullptr;
StackTranslate translate = nullptr;
StackOpacity opacity = nullptr;
ScheduleFrame schedule_frame = nullptr;

uint64_t now_ns() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return uint64_t(now.tv_sec) * 1000000000ULL + now.tv_nsec;
}
template<class T> T &at(void *base, size_t offset) {
    return *reinterpret_cast<T *>(static_cast<std::byte *>(base) + offset);
}
bool finishing(uint64_t now, uint64_t grace) {
    const auto end = end_ns.load(std::memory_order_acquire);
    return ready.load(std::memory_order_acquire) && end != 0 && now < end + grace;
}

// Match the engine's own MutatorContext destructor, including virtual restore
// and deletion of each state entry. The frame's existing prefix is untouched.
void restore_state(void *stack, size_t saved_bytes) {
    while (at<uintptr_t>(stack, 8) - at<uintptr_t>(stack, 0) > saved_bytes) {
        auto end = at<uintptr_t>(stack, 8);
        auto *entry = *reinterpret_cast<void **>(end - sizeof(void *));
        auto **vtable = *reinterpret_cast<void ***>(entry);
        reinterpret_cast<void (*)(void *, void *)>(vtable[4])(entry, stack);
        end = at<uintptr_t>(stack, 8) - sizeof(void *);
        at<uintptr_t>(stack, 8) = end;
        *reinterpret_cast<void **>(end) = nullptr;
        reinterpret_cast<void (*)(void *)>(vtable[1])(entry);
    }
}

void paint(void *layer, void *context) {
    const auto id = dock_reveal_native_id.load(std::memory_order_acquire);
    if (!ready.load(std::memory_order_acquire) || !enabled.load(std::memory_order_acquire)
        || id == 0 || at<uint64_t>(layer, 0x20) != id) {
        original_paint(layer, context);
        return;
    }
    // Also cover a keyguard transition arriving after Raster sampled its pose.
    // Only this glass layer is suppressed; the original icon state is untouched.
    if (keyguard_locked.load(std::memory_order_acquire)
        || (finishing(now_ns(), 0) && frame_epoch != epoch.load(std::memory_order_acquire))) return;
    if (dock_reveal::identity(frame_pose)) {
        original_paint(layer, context);
        restored_background = true;
        return;
    }
    paint_hits.fetch_add(1, std::memory_order_relaxed);
    if (frame_pose.alpha <= 0) return;
    auto *stack = at<void *>(context, 0);
    const auto saved = at<uintptr_t>(stack, 8) - at<uintptr_t>(stack, 0);
    // This is the same save/translate protocol used by the existing OpacityLayer.
    if (at<void *>(stack, 0x50)) save_layer(stack, static_cast<std::byte *>(stack) + 0x28);
    else save_state(stack);
    translate(stack, 0, frame_pose.y);
    // LTO promotes this engine method's `this` to LayerStateStack*, as confirmed
    // by OpacityLayer::Paint. Do not pass a synthetic C++ MutatorContext object.
    opacity(stack, static_cast<std::byte *>(layer) + 8, frame_pose.alpha);
    original_paint(layer, context);
    restore_state(stack, saved);
}

int raster(void *frame, void *tree, bool ignore_cache, void *damage, int64_t view) {
    const auto now = now_ns();
    frame_epoch = epoch.load(std::memory_order_acquire);
    const bool active = enabled.load(std::memory_order_acquire) && ready.load(std::memory_order_acquire);
    const bool locked = active && keyguard_locked.load(std::memory_order_acquire);
    const bool following = active && finishing(now, 0);
    const auto pending_restore = restore_epoch.load(std::memory_order_acquire);
    const bool restoring = active && pending_restore != 0 && pending_restore == frame_epoch;
    restored_background = false;
    frame_pose = locked ? dock_reveal::FollowPose{0, 0}
        : following ? submitted_frames.get(tree, frame_epoch, {0, 0}) : dock_reveal::FollowPose{};
    // Remove the old glass from the retained lock-screen frame as well. Merely
    // hiding the first post-unlock tree leaves the previous GPU frame visible.
    const bool repaint = locked || following || restoring || (active && finishing(now, 100000000ULL));
    const int result = original_raster(frame, tree, ignore_cache || repaint, repaint ? nullptr : damage, view);
    if (!locked && !following && restored_background) {
        auto expected = frame_epoch;
        restore_epoch.compare_exchange_strong(expected, 0, std::memory_order_acq_rel);
    }
    frame_pose = {1, 0};
    return result;
}

// Verified ABI: x2 points to the caller's unique_ptr<LayerTree>; the engine
// subsequently moves it. Snapshot before that move, on the original UI thread.
void render(void *engine, int64_t view, void *tree_owner, float density) {
    auto *tree = tree_owner ? at<void *>(tree_owner, 0) : nullptr;
    const auto generation = epoch.load(std::memory_order_acquire);
    dock_reveal::FollowPose pose;
    bool request_next = false;
    if (tree && enabled.load(std::memory_order_acquire)
        && !keyguard_locked.load(std::memory_order_acquire) && end_ns.load(std::memory_order_acquire) != 0) {
        thread_local dock_reveal::FollowSession session;
        const auto samples = dock_reveal_icon_samples.load(std::memory_order_acquire);
        const auto source = dock_reveal::source_pose(
            std::bit_cast<double>(dock_reveal_icon_alpha.load(std::memory_order_relaxed)),
            std::bit_cast<double>(dock_reveal_icon_y.load(std::memory_order_relaxed)));
        const auto result = session.sample(generation, now_ns(), begin_ns.load(std::memory_order_acquire),
            sample_floor.load(std::memory_order_acquire), samples, source);
        pose = result.pose;
        // Schedule one final regenerated normal tree too. Changing a deadline
        // alone cannot replace a glass-free frame already retained by the GPU.
        request_next = result.complete || session.needs_frame();
        if (result.complete && epoch.load(std::memory_order_acquire) == generation) {
            end_ns.store(0, std::memory_order_release);
            __android_log_print(ANDROID_LOG_INFO, kTag,
                "unlock session complete; restore Dock; fresh_samples=%llu",
                static_cast<unsigned long long>(samples - sample_floor.load(std::memory_order_acquire)));
        }
    }
    if (tree) submitted_frames.put(tree, generation, pose);
    original_render(engine, view, tree_owner, density);
    // Engine is borrowed only during this UI-thread callback. No worker timer,
    // retained Engine pointer or cross-thread ScheduleFrame call is involved.
    if (request_next && schedule_frame && epoch.load(std::memory_order_acquire) == generation
        && enabled.load(std::memory_order_acquire) && !keyguard_locked.load(std::memory_order_acquire))
        schedule_frame(engine, true);
}
void keyguard(void *watcher, bool locked) {
    const bool was_locked = keyguard_locked.exchange(locked, std::memory_order_acq_rel);
    if (locked != was_locked) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "keyguard showing=%d; Dock pre-entry gate", locked);
    }
    if (locked) {
        epoch.fetch_add(1, std::memory_order_acq_rel);
        end_ns.store(0, std::memory_order_release);
        restore_epoch.store(0, std::memory_order_release);
    } else if (was_locked && ready.load(std::memory_order_acquire) && enabled.load(std::memory_order_acquire)) {
        const auto start = now_ns();
        begin_ns.store(start, std::memory_order_release);
        sample_floor.store(dock_reveal_icon_samples.load(std::memory_order_acquire), std::memory_order_release);
        const auto generation = epoch.fetch_add(1, std::memory_order_acq_rel) + 1;
        restore_epoch.store(generation, std::memory_order_release);
        // Cancellation limit only; pose and rebound still come from the icon.
        end_ns.store(start + dock_reveal::FollowSession::kMotionLimitNs, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, kTag, "unlock follows stock Dock icon; glass_id=%llu source_samples=%llu",
            static_cast<unsigned long long>(dock_reveal_native_id.load()),
            static_cast<unsigned long long>(dock_reveal_icon_samples.load()));
    }
    original_keyguard(watcher, locked);
}

bool build_id(uintptr_t base, std::string_view expected) {
    Elf64_Ehdr header{};
    if (!nhk::safe_read(base, std::as_writable_bytes(std::span(&header, 1)))
        || std::memcmp(header.e_ident, ELFMAG, SELFMAG) || header.e_phnum > 64
        || header.e_phentsize != sizeof(Elf64_Phdr)) return false;
    for (unsigned i = 0; i < header.e_phnum; ++i) {
        Elf64_Phdr segment{};
        if (!nhk::safe_read(base + header.e_phoff + i * sizeof(segment),
            std::as_writable_bytes(std::span(&segment, 1)))) return false;
        if (segment.p_type != PT_NOTE || segment.p_memsz > 4096) continue;
        std::array<std::byte, 4096> notes{};
        if (!nhk::safe_read(base + segment.p_vaddr, std::span(notes).first(segment.p_memsz))) continue;
        for (size_t off = 0; off + sizeof(Elf64_Nhdr) <= segment.p_memsz;) {
            Elf64_Nhdr note{};
            std::memcpy(&note, notes.data() + off, sizeof(note));
            off += sizeof(note);
            const auto name_size = (size_t(note.n_namesz) + 3) & ~size_t(3);
            const auto data_size = (size_t(note.n_descsz) + 3) & ~size_t(3);
            if (name_size > segment.p_memsz - off || data_size > segment.p_memsz - off - name_size) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4
                && !std::memcmp(notes.data() + off, "GNU", 4) && expected.size() == note.n_descsz * 2) {
                constexpr char hex[] = "0123456789abcdef";
                std::string actual;
                for (unsigned j = 0; j < note.n_descsz; ++j) {
                    const auto value = std::to_integer<unsigned>(notes[off + name_size + j]);
                    actual += hex[value >> 4]; actual += hex[value & 15];
                }
                if (actual == expected) return true;
            }
            off += name_size + data_size;
        }
    }
    return false;
}
struct Images {
    uintptr_t engine = 0, bridge = 0, dart = 0;
    const dock_reveal::Profile *profile = nullptr;
};
Images images() {
    Images result;
    dl_iterate_phdr([](dl_phdr_info *info, size_t, void *opaque) {
        auto &found = *static_cast<Images *>(opaque);
        const std::string_view name(info->dlpi_name);
        if (name.ends_with("libhyper_os_flutter.so")) found.engine = info->dlpi_addr;
        if (name.ends_with("libapp_launcher.so")) found.bridge = info->dlpi_addr;
        return 0;
    }, &result);
    std::ifstream maps("/proc/self/maps");
    for (const auto &mapping : nhk::parse_file_mappings(maps)) {
        if (!mapping.writable && !mapping.executable && mapping.path.ends_with("/base.apk")) {
            for (const auto &profile : dock_reveal::kProfiles) {
                if (!build_id(mapping.begin, profile.build)) continue;
                result.dart = mapping.begin;
                result.profile = &profile;
                break;
            }
            if (result.profile) break;
        }
    }
    return result;
}

bool install(const Images &image) {
    if (!image.engine || !image.bridge || !image.dart || !image.profile) return false;
    const dock_reveal::EngineProfile *engine = nullptr;
    for (const auto &profile : dock_reveal::kEngineProfiles)
        if (build_id(image.engine, profile.build)) { engine = &profile; break; }
    if (!engine) {
        static bool reported = false;
        if (!reported) __android_log_print(ANDROID_LOG_WARN, kTag, "reveal rejected: unsupported Flutter engine build ID");
        reported = true;
        return false;
    }
    if (engine->build == "4ae44cae83c5140c46697ecdf193338e17aa8668") {
        // Engine::ScheduleFrame(bool) forwards X0=Engine*, W1=regenerate to
        // Animator::RequestFrame. Exact image + complete routine gate, not an
        // assumed adjacent RVA or a call from the polling/keyguard thread.
        constexpr std::array<uint32_t, 13> schedule_shape{
            0xf81e0ffe,0xa9014ff4,0xaa0003f4,0x90ff9b00,0x9106d400,
            0x2a0103f3,0x940c8bbc,0xf9434280,0x2a1303e1,0x97fff4ca,
            0xa9414ff4,0xf84207fe,0x140c8bbe};
        std::array<uint32_t, 13> live{};
        if (!nhk::safe_read(image.engine + 0xd8c9d8, std::as_writable_bytes(std::span(live)))
            || live != schedule_shape) return false;
        schedule_frame = reinterpret_cast<ScheduleFrame>(image.engine + 0xd8c9d8);
    }
    if (engine->build == "c014a14bb9ddab4431a887c98c6d56ce305b0573") {
        // Verified Engine::ScheduleFrame(bool), including Animator receiver +0x680.
        constexpr std::array<uint32_t, 13> expected{
            0xf81e0ffe,0xa9014ff4,0xaa0003f4,0xb0ff9660,0x912a4400,
            0x2a0103f3,0x940c97a9,0xf9434280,0x2a1303e1,0x97fff507,
            0xa9414ff4,0xf84207fe,0x140c97ab};
        std::array<uint32_t, 13> live{};
        if (!nhk::safe_read(image.engine + 0xe1fc14, std::as_writable_bytes(std::span(live)))
            || live != expected) return false;
        schedule_frame = reinterpret_cast<ScheduleFrame>(image.engine + 0xe1fc14);
    }
    const auto &profile = *image.profile;
    std::array<uint32_t, 24> keyguard_shape{
        0xd10243ff,0xa9047bfd,0xf9002bf9,0xa9065ff8,0xa90757f6,0xa9084ff4,
        0x910103fd,0x390063a1,0x9100c408,0x2a0103f3,0x08dffd08,0xaa0003f4,
        0x340000c8,0x9100c288,0x08dffd08,0x7100011f,0x1a9f07e8,0x14000008,
        0x941cb52b,0xf2607c1f,0xd3482008,0x1a9f07e9,0x7200001f,0x1a881128};
    keyguard_shape[18] = profile.keyguard_call;
    std::array<uint32_t, 24> code{};
    if (!nhk::safe_read(image.bridge + profile.keyguard, std::as_writable_bytes(std::span(code)))
        || code != keyguard_shape) return false;
    save_state = reinterpret_cast<StackSave>(image.engine + engine->save);
    save_layer = reinterpret_cast<StackSaveLayer>(image.engine + engine->save_layer);
    translate = reinterpret_cast<StackTranslate>(image.engine + engine->translate);
    opacity = reinterpret_cast<StackOpacity>(image.engine + engine->opacity);
    dock_reveal_clip_vtable = image.engine + engine->clip_vtable;
    dock_reveal_backdrop_vtable = image.engine + engine->backdrop_vtable;
    dock_reveal_cid_delta = profile.cid_delta;
    struct Target { uintptr_t address; void *replacement; void **continuation; };
    const std::array<Target, 6> targets{{
        {image.engine+engine->paint, reinterpret_cast<void *>(paint), reinterpret_cast<void **>(&original_paint)},
        {image.engine+engine->raster, reinterpret_cast<void *>(raster), reinterpret_cast<void **>(&original_raster)},
        {image.engine+engine->render, reinterpret_cast<void *>(render), reinterpret_cast<void **>(&original_render)},
        {image.bridge+profile.keyguard, reinterpret_cast<void *>(keyguard), reinterpret_cast<void **>(&original_keyguard)},
        {image.dart+profile.capture, reinterpret_cast<void *>(dock_reveal_capture_entry), &dock_reveal_capture_original},
        {image.dart+profile.icon, reinterpret_cast<void *>(dock_reveal_icon_entry), &dock_reveal_icon_original},
    }};
    constexpr std::array<std::array<uint32_t, 4>, 6> entries{{
        {0xd10103ff,0xa90257fe,0xa9034ff4,0xf9400029},
        {0xfc180fea,0x6d0123e9,0xa9027bfd,0xa9036ffc},
        {0xd10143ff,0xfd000be8,0xf9000ffe,0xa9025ff8},
        {0xd10243ff,0xa9047bfd,0xf9002bf9,0xa9065ff8},
        {0xa9bf79fd,0xaa0f03fd,0xd10121ef,0xaa0103e5},
        {0xa9bf79fd,0xaa0f03fd,0xaa0203e0,0xb849b022},
    }};
    for (size_t i = 0; i < targets.size(); ++i) {
        std::array<uint32_t, 4> live{};
        if (!nhk::safe_read(targets[i].address, std::as_writable_bytes(std::span(live)))
            || live != entries[i]) return false;
    }
    for (const auto &target : targets) {
        if (install_hook(reinterpret_cast<void *>(target.address), target.replacement, target.continuation) != 0
            || !*target.continuation) {
            // Keep any successful continuations alive and inactive. Unhooking
            // while a callback is in flight could free a live trampoline.
            __android_log_print(ANDROID_LOG_WARN, kTag, "reveal installation refused at %p; effect inactive",
                reinterpret_cast<void *>(target.address));
            return true; // No repeated installation of partially attached hooks.
        }
        (void)mlock(reinterpret_cast<void *>(target.address & ~uintptr_t(getpagesize()-1)), getpagesize());
    }
    ready.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s native reveal hooks ready; engine=%.*s; stock icon follower; glass clip only",
        profile.version, static_cast<int>(engine->build.size()), engine->build.data());
    return true;
}

void *worker(void *) {
    bool attempted = false;
    uint64_t previous_id = 0, previous_end = 0;
    for (;;) {
        const std::string path = "/data/user/" + std::to_string(getuid()/100000)
            + "/com.miui.home/files/hyperdock-reveal-duration";
        std::ifstream config(path);
        std::string mode;
        bool parsed = false;
        parsed = bool(config >> mode) && mode == "follow";
        std::string extra;
        const bool active = parsed && !(config >> extra) && hc_native_features_allowed();
        if (enabled.exchange(active, std::memory_order_acq_rel) && !active) {
            const auto generation = epoch.fetch_add(1, std::memory_order_acq_rel) + 1;
            end_ns.store(0, std::memory_order_release);
            restore_epoch.store(generation, std::memory_order_release);
        }
        if (active && !attempted) attempted = install(images());
        const auto id = dock_reveal_native_id.load(std::memory_order_acquire);
        if (id && id != previous_id) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "bound existing Dock glass clip original_id=%llu",
                static_cast<unsigned long long>(id)); previous_id = id;
        }
        const auto end = end_ns.load(std::memory_order_acquire);
        if (end && end != previous_end && now_ns() > end) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "reveal interval finished; affected paint calls=%llu icon_samples=%llu",
                static_cast<unsigned long long>(paint_hits.load()), static_cast<unsigned long long>(dock_reveal_icon_samples.load())); previous_end = end;
        }
        usleep(500000);
    }
    return nullptr;
}
} // namespace

void start_dock_native_reveal(int (*hook)(void *, void *, void **)) {
    if (!hook || started.exchange(true)) return;
    install_hook = hook;
    pthread_t thread{};
    if (pthread_create(&thread, nullptr, worker, nullptr) == 0) pthread_detach(thread);
    else started.store(false);
}
