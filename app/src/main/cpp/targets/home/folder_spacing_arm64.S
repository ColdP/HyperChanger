// SPDX-License-Identifier: AGPL-3.0-or-later
// Entry replaces the first instruction of calItemSpacing's two Dart epilogues.
// The stock calculation has finished; no C calls or runtime safepoints occur.
.text
.p2align 4
.global folder_spacing_return
.hidden folder_spacing_return
.type folder_spacing_return, %function
folder_spacing_return:
    adrp x17, folder_spacing_value
    add x17, x17, :lo12:folder_spacing_value
    ldar w17, [x17]
    // Disabled sentinel; SUB/CBZ preserve NZCV. An in-flight old branch after
    // unpatching must also return without touching the stock gap.
    sub w16, w17, #1000
    cbz w16, .Lstock_return
    ldur x16, [x29, #-8]
    str q0, [x15, #-16]!
    scvtf d0, w17
    stur d0, [x16, #0x3b]
    ldr q0, [x15], #16
.Lstock_return:
    // Exact displaced epilogue; only Dart scratch registers x16/x17 changed.
    mov x0, x22
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
.size folder_spacing_return, .-folder_spacing_return

// A Dart true object is the null singleton plus 0x20. This helper is only
// reached through a version-gated branch installed at the 8.01.02.7748
// isMultipleSplitSupport entry, so the original Dart frame remains untouched.
.global multi_split_support_return
.hidden multi_split_support_return
.type multi_split_support_return, %function
multi_split_support_return:
    add x0, x22, #0x20
    ret
.size multi_split_support_return, .-multi_split_support_return
.section .note.GNU-stack,"",%progbits
