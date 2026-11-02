// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <mutex>
namespace dock_reveal {
struct FollowPose { float alpha = 1, y = 0; };
inline FollowPose source_pose(double alpha, double y) {
    if (!std::isfinite(alpha) || !std::isfinite(y) || std::abs(y) > 1000000) return {};
    return {float(std::clamp(alpha, 0.0, 1.0)), float(y)};
}
inline bool identity(FollowPose pose) { return pose.alpha >= 1 && std::abs(pose.y) < .0001f; }
// An identity sample from the previous home frame is not the start of an
// unlock. Keep glass hidden until the stock icon actually enters its motion.
class FollowStart {
    uint64_t epoch_ = 0;
    bool motion_seen_ = false;
public:
    FollowPose accept(uint64_t epoch, bool available, FollowPose source) {
        if (epoch_ != epoch) { epoch_ = epoch; motion_seen_ = false; }
        if (!available) return {0, 0};
        if (!identity(source)) motion_seen_ = true;
        return motion_seen_ ? source : FollowPose{0, 0};
    }
    bool settled(FollowPose pose) const { return motion_seen_ && identity(pose); }
};
// UI and raster threads can be one or more frames apart. Retain each submitted
// LayerTree's own sample instead of applying the newest UI pose to an older tree.
class FollowFrames {
    struct Frame { const void *tree = nullptr; uint64_t epoch = 0; FollowPose pose; };
    std::array<Frame, 64> frames_{};
    size_t cursor_ = 0;
    std::mutex mutex_;
public:
    void put(const void *tree, uint64_t epoch, FollowPose pose) {
        std::lock_guard lock(mutex_);
        for (auto &frame : frames_) if (frame.tree == tree) { frame = {tree, epoch, pose}; return; }
        frames_[cursor_++ % frames_.size()] = {tree, epoch, pose};
    }
    FollowPose get(const void *tree, uint64_t epoch, FollowPose missing = {}) {
        std::lock_guard lock(mutex_);
        for (const auto &frame : frames_) if (frame.tree == tree && frame.epoch == epoch) return frame.pose;
        return missing;
    }
};
} // namespace dock_reveal
