// SPDX-License-Identifier: AGPL-3.0-or-later
.text
.p2align 2
.global widget_support_return
.hidden widget_support_return
.type widget_support_return, %function
// Enter only at the reviewed Dart epilogue after all original initialization.
// Preserve its shadow-stack frame, LR, and callee-saved registers. Dart true is
// NULL_REG + 0x20 in these full-function profiles; no C/Rust ABI call occurs.
widget_support_return:
    add x0, x22, #0x20
    mov x15, x29
    ldp x29, x30, [x15], #0x10
    ret
.size widget_support_return, .-widget_support_return
.section .note.GNU-stack,"",%progbits
