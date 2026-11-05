// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include "dock_reveal_follow.h"

namespace dock_reveal {
// Session deadlines restore visibility; they never determine the icon's curve.
// A final normal frame is necessary even if no unlock animation is emitted.
class FollowSession {
    uint64_t epoch_ = 0;
    bool motion_seen_ = false, complete_ = false;
public:
    static constexpr uint64_t kMotionLimitNs = 3000000000ULL;
    struct Result { FollowPose pose; bool complete; };

    Result sample(uint64_t epoch, uint64_t now, uint64_t begin,
                  uint64_t sample_floor, uint64_t samples, FollowPose source) {
        if (epoch_ != epoch) {
            epoch_ = epoch;
            motion_seen_ = complete_ = false;
        }
        if (complete_) return {{}, true};
        const auto elapsed = now >= begin ? now - begin : uint64_t{0};
        const bool fresh = samples > sample_floor;
        if (fresh && !identity(source)) motion_seen_ = true;
        if (elapsed >= kMotionLimitNs
            || (motion_seen_ && fresh && identity(source))) {
            complete_ = true;
            return {{}, true};
        }
        // A preloaded home frame can report the settled pose before the real
        // unlock animation starts (face authentication followed by a swipe).
        // Do not expose that stale/identity pose as a one-frame normal Dock.
        // Once motion starts, only this epoch's fresh icon samples are used.
        if (!motion_seen_) return {{0, 0}, false};
        return {fresh ? source : FollowPose{0, 0}, false};
    }
    bool needs_frame() const { return !complete_; }
};
} // namespace dock_reveal
