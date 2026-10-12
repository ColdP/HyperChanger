/* SPDX-License-Identifier: Apache-2.0 */
// Dart compressed-pointer ABI. The hook replaces the result move in
// BlurUtilities.isGlassMaterialSupported and returns through its original
// epilogue. Disabled mode reproduces the original mov x0, x1 exactly.
.text
.p2align 4
.global material_return_entry
.hidden material_return_entry
.type material_return_entry, %function
material_return_entry:
    adrp x16, material_enabled
    ldrb w16, [x16, :lo12:material_enabled]
    cbz w16, .Lmaterial_original
    adrp x16, material_style
    ldrb w16, [x16, :lo12:material_style]
    cbnz w16, .Lmaterial_false
    add x0, x22, #0x20
    b .Lmaterial_continue
.Lmaterial_false:
    add x0, x22, #0x30
    b .Lmaterial_continue
.Lmaterial_original:
    mov x0, x1
.Lmaterial_continue:
    adrp x16, material_return_continue
    ldr x16, [x16, :lo12:material_return_continue]
    br x16
.size material_return_entry, .-material_return_entry
