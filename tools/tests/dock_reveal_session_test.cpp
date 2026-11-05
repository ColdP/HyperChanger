// SPDX-License-Identifier: AGPL-3.0-or-later
#include "../../app/src/main/cpp/targets/home/dock_reveal_session.h"
#include <cassert>
#include <cstdio>
int main() {
    using namespace dock_reveal;
    constexpr uint64_t start = 1000000000;
    FollowSession session;
    auto r=session.sample(1,start,start,10,10,{});
    assert(r.pose.alpha==0 && !r.complete && session.needs_frame());
    r=session.sample(1,start+10000000,start,10,11,{});
    assert(r.pose.alpha==0 && !r.complete); // fresh settled preload must stay hidden
    r=session.sample(1,start+20000000,start,10,12,{0.4f,80});
    assert(r.pose.alpha==0.4f && r.pose.y==80 && !r.complete);
    r=session.sample(1,start+30000000,start,10,13,{});
    assert(identity(r.pose) && r.complete && !session.needs_frame());
    r=session.sample(2,start,start,13,13,{});
    assert(r.pose.alpha==0 && !r.complete); // no completed-state leakage across unlocks
    r=session.sample(2,start+FollowSession::kMotionLimitNs,start,13,13,{});
    assert(identity(r.pose) && r.complete); // no-animation recovery cannot remain invisible
    FollowSession fingerprint;
    r=fingerprint.sample(1,start,start,0,1,{0,100});
    assert(r.pose.alpha==0 && r.pose.y==100);
    r=fingerprint.sample(1,start+10000000,start,0,2,{1,0});
    assert(identity(r.pose) && r.complete);
    std::puts("PASS: preload/stale gate, motion following, completion, generation reset, timeout and immediate fingerprint entry");
}
