/* SPDX-License-Identifier: AGPL-3.0-or-later */
// Read-only Dart callback: identify the existing Dock's native layer by its
// object chain. Keep only the native layer's numeric original_id, never a
// Dart pointer. No calls, allocations, safepoints or FP changes are introduced.
.text
.p2align 4
.macro check_cid_slot slot, done=.Lcapture_done
    tbz x16, #0, \done
    cmp x16, x22
    b.eq \done
    ldur w10, [x16, #-1]
    ubfx w10, w10, #12, #20
    adrp x17, dock_reveal_cids
    add x17, x17, :lo12:dock_reveal_cids
    ldr w11, [x17, #(\slot * 4)]
    cmp w10, w11
    b.ne \done
.endm
.macro check_cid_vm value, done=.Lcapture_done
    tbz x16, #0, \done
    cmp x16, x22
    b.eq \done
    ldur w10, [x16, #-1]
    ubfx w10, w10, #12, #20
    mov w11, #\value
    cmp w10, w11
    b.ne \done
.endm
.macro follow offset, slot, done=.Lcapture_done
    ldur w16, [x16, #\offset]
    add x16, x16, x28, lsl #32
    check_cid_slot \slot, \done
.endm
.macro follow_vm offset, value, done=.Lcapture_done
    ldur w16, [x16, #\offset]
    add x16, x16, x28, lsl #32
    check_cid_vm \value, \done
.endm
.macro follow_box offset, slot, done=.Lcapture_done
    adrp x17, dock_reveal_box_shift
    ldr w17, [x17, :lo12:dock_reveal_box_shift]
    add x10, x16, x17
    ldur w16, [x10, #\offset]
    add x16, x16, x28, lsl #32
    check_cid_slot \slot, \done
.endm
.macro native_glass done
    ldur x16, [x16, #7]
    cbz x16, \done
    ldr x16, [x16]
    cbz x16, \done
    ldr x16, [x16, #0x30] // EngineLayer's strong shared_ptr<Layer>.
    cbz x16, \done
    ldr x10, [x16]
    adrp x17, dock_reveal_clip_vtable
    ldr x11, [x17, :lo12:dock_reveal_clip_vtable]
    cmp x10, x11
    b.ne \done
    // This clip must contain exactly the background filter. The icon display
    // list observed in 8593 is its sibling under the outer TransformLayer.
    ldp x10, x11, [x16, #0x30]
    sub x11, x11, x10
    cmp x11, #16
    b.ne \done
    ldr x10, [x10]
    cbz x10, \done
    ldr x10, [x10]
    adrp x17, dock_reveal_backdrop_vtable
    ldr x11, [x17, :lo12:dock_reveal_backdrop_vtable]
    cmp x10, x11
    b.ne \done
.endm
.global dock_reveal_capture_entry
.hidden dock_reveal_capture_entry
.type dock_reveal_capture_entry, %function
dock_reveal_capture_entry:
    stp x9, x10, [x15, #-16]!
    stp x11, x12, [x15, #-16]!
    mrs x9, nzcv
    str x9, [x15, #-16]!
    mov x16, x1
    check_cid_slot 0 // RenderBackdropFilter
    follow 0x13, 1   // RenderClipRRect
    mov x9, x16 // Keep the glass-only clip, before validating its ancestors.
    follow 0x13, 2   // RenderPhysicalShape
    mov x12, x16
    follow_box 0x63, 3 // The original Dock's private shape clipper.
    mov x16, x12
    follow 0x13, 4   // RenderStack
    mov x12, x16 // Same Stack owns the glass and original icon row.
    follow 0x13, 5   // _RenderLayoutBuilder
    follow 0x13, 6   // RenderPadding
    follow 0x13, 7   // RenderConstrainedBox
    follow 0x13, 8   // RenderPointerListener
    follow 0x13, 9   // RenderSemanticsGestureHandler
    follow 0x13, 10  // RenderExcludeSemantics
    follow 0x13, 11  // Identity check only; this wrapper also contains icons.
    // A retained icon need not run updateRenderObject again. Observe its
    // current pose from the attached sibling graph on this paint as well.
    mov x16, x12
    follow_box 0x67, 12, .Lbackground_bind // Stack.lastChild: Semantics wrapper.
    follow_box 0x5b, 13, .Lbackground_bind // child: original icon row.
    follow_box 0x63, 11, .Lbackground_bind // firstChild: RenderHyperTransform.
    mov x12, x16
    follow 7, 14, .Lbackground_bind        // MultiChildLayoutParentData.
    ldur w10, [x16, #0xf]
    cmp w10, w22
    b.ne .Lbackground_bind
    mov x16, x12
    follow_box 0x6f, 15, .Lbackground_bind // Offset.
    ldur x10, [x16, #0xf]
    adrp x17, dock_reveal_icon_y
    add x17, x17, :lo12:dock_reveal_icon_y
    str x10, [x17]
    adrp x17, dock_reveal_box_shift
    ldr w17, [x17, :lo12:dock_reveal_box_shift]
    add x10, x12, x17
    ldur x10, [x10, #0x5f]
    adrp x17, dock_reveal_icon_alpha
    add x17, x17, :lo12:dock_reveal_icon_alpha
    str x10, [x17]
    adrp x17, dock_reveal_icon_samples
    add x17, x17, :lo12:dock_reveal_icon_samples
    ldr x10, [x17]
    add x10, x10, #1
    stlr x10, [x17]
.Lbackground_bind:
    // The common RenderHyperTransform may be identity and have no engine
    // layer. Its existence is not a prerequisite for binding the glass clip.
    mov x16, x9 // Animate the glass clip, not the wrapper containing icons.
    follow 0x33, 16 // RenderClipRRect's own LayerHandle.
    follow 0xb, 17  // Existing ClipRRectLayer, excluding the icon sibling.
    follow 0x27, 18 // ClipRRectEngineLayer.
    follow 7, 19    // Native engine-layer wrapper.
    follow_vm 7, 140 // VM native-fields storage.
    native_glass .Lcapture_done
    ldr x10, [x16, #0x20] // Stable across retained-layer replacements.
    cbz x10, .Lcapture_done
    adrp x17, dock_reveal_native_id
    add x17, x17, :lo12:dock_reveal_native_id
    stlr x10, [x17]
.Lcapture_done:
    ldr x9, [x15], #16
    msr nzcv, x9
    ldp x11, x12, [x15], #16
    ldp x9, x10, [x15], #16
    adrp x16, dock_reveal_capture_original
    ldr x16, [x16, :lo12:dock_reveal_capture_original]
    br x16
.size dock_reveal_capture_entry, .-dock_reveal_capture_entry
.macro icon_follow offset, slot
    follow \offset, \slot, .Licon_done
.endm
.macro icon_follow_box offset, slot
    follow_box \offset, \slot, .Licon_done
.endm

// Last updateRenderObject setter: opacity, offset and scale are already updated.
// Read the first Dock icon (ContainerParentData.previousSibling == null).
// No managed pointers survive this callback; only numeric pose values do.
.p2align 4
.global dock_reveal_icon_entry
.hidden dock_reveal_icon_entry
.type dock_reveal_icon_entry, %function
dock_reveal_icon_entry:
    stp x9, x10, [x15, #-16]!
    stp x11, x12, [x15, #-16]!
    mrs x9, nzcv
    str x9, [x15, #-16]!
    mov x16, x1
    check_cid_slot 11, .Licon_done // RenderHyperTransform
    mov x9, x16
    icon_follow 7, 14
    ldur w10, [x16, #0xf]
    cmp w10, w22
    b.ne .Licon_done
    mov x16, x9
    icon_follow 0x13, 13
    icon_follow 0x13, 12
    icon_follow 0x13, 4
    mov x12, x16 // Common Stack, shared by the icon row and glass sibling.
    icon_follow 0x13, 5
    icon_follow 0x13, 6
    icon_follow 0x13, 7
    icon_follow 0x13, 8
    icon_follow 0x13, 9
    icon_follow 0x13, 10
    icon_follow 0x13, 11
    // Identify the actual glass sibling in this same managed graph. Identity
    // transforms deliberately drop LayerHandle.layer, so comparing their old
    // native IDs rejected every icon after a normal unlock. No Dart pointer
    // survives this callback; bind only the verified clip's numeric native ID.
    mov x16, x12
    icon_follow_box 0x63, 2 // Stack.firstChild is the Dock physical shape.
    mov x12, x16
    icon_follow_box 0x63, 3 // Private Dock clipper, not a workspace icon stack.
    mov x16, x12
    icon_follow_box 0x5b, 1 // PhysicalShape.child: existing glass-only clip.
    mov x12, x16
    icon_follow_box 0x5b, 0 // ClipRRect.child must be the background filter.
    // Pose belongs to this verified Dock graph even before its first paint
    // creates a native layer. Do not lose the early motion waiting for it.
    mov x16, x9
    icon_follow_box 0x6f, 15 // Original icon Offset (not Size or boxed doubles).
    ldur x10, [x16, #0xf]
    adrp x17, dock_reveal_icon_y
    add x17, x17, :lo12:dock_reveal_icon_y
    str x10, [x17]
    adrp x17, dock_reveal_box_shift
    ldr w17, [x17, :lo12:dock_reveal_box_shift]
    add x10, x9, x17
    ldur x10, [x10, #0x5f]
    adrp x17, dock_reveal_icon_alpha
    add x17, x17, :lo12:dock_reveal_icon_alpha
    str x10, [x17]
    adrp x17, dock_reveal_icon_samples
    add x17, x17, :lo12:dock_reveal_icon_samples
    ldr x10, [x17]
    add x10, x10, #1
    stlr x10, [x17]
    mov x16, x12
    icon_follow 0x33, 16
    icon_follow 0xb, 17
    icon_follow 0x27, 18
    icon_follow 7, 19
    follow_vm 7, 140, .Licon_done
    native_glass .Licon_done
    ldr x10, [x16, #0x20]
    cbz x10, .Licon_done
    adrp x17, dock_reveal_native_id
    add x17, x17, :lo12:dock_reveal_native_id
    stlr x10, [x17]
.Licon_done:
    ldr x9, [x15], #16
    msr nzcv, x9
    ldp x11, x12, [x15], #16
    ldp x9, x10, [x15], #16
    adrp x16, dock_reveal_icon_original
    ldr x16, [x16, :lo12:dock_reveal_icon_original]
    br x16
.size dock_reveal_icon_entry, .-dock_reveal_icon_entry

.section .note.GNU-stack,"",%progbits
