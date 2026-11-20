// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include <cstdint>
using WidgetHook = int (*)(void *, void *, void **);
void initialize_widget_backend(WidgetHook);
void note_widget_library(const char *, void *);
bool update_widget_capabilities(uint32_t flags, bool picker_ready);
