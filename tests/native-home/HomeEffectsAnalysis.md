# Launcher glass parameters and Dock icon offset

The supplied launcher image has a unique V7 glass parameter body at RVA 0x17413e8 and a unique hotSeatsMarginBottom getter at RVA 0xa58398. Runtime scanning uses complete instruction shapes, including all 27 ordered glass scalar reads and the getter arithmetic/epilogue; runtime RVAs are not hardcoded.

Five UI controls map to glass fields +0xcb (blur), +0x17/+0x5f (thickness/IOR), +0x83 (soft light), +0x8b (background saturation) and +0x93 (background brightness). Values are stock-relative percentages; IOR scales around its neutral value of 1. Dock offset adds a signed dp value to the stock return, increasing the bottom margin for positive values.

Native config retains the V18 columns/recents payload and FGRS v2, followed by MTRL v3 (enabled plus five percentages) and DIOF v1 (enabled plus signed offset). Effects require an exact valid tail; older material tails cannot enable a new hook. These settings live outside HookSettings and use the existing atomic root transport with su -M.

A single atomic B replaces each verified scalar load/return-copy. Permanent code islands replay that instruction, preserve X16, Q30/Q31 and NZCV, and return to the next instruction. Immutable RX code and separate RW atomic data prevent executable-page writes during slider updates. Disabled islands replay stock exactly; disabling restores all original instructions. Mapping ownership and expected original/branch words are checked before writes. COW-discarded instructions are healed only for a previously verified backing location.

The V7 glass entry is shared by glass consumers. This adjusts existing glass; it does not force glass on unsupported/advanced materials. Other version branches and widget-owned custom effects are outside the matched body. Cached filters/layouts require rebuilding before new settings are visible.

Validation: 36,582 effect configurations, malformed/truncated input guards, 9,696 folder/recents regression configurations, unique image body matches, 254 executions of generated ARM64 code checking results, exact disabled replay, CPU registers, SP and NZCV. Tests output only under .reverse-tmp. Device visual validation remains manual.
