// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

import java.util.ArrayList;
import java.util.List;

/** Keeps stock paging counts separate from the records passed to its row converter. */
final class LocalWidgetCatalogPage extends ArrayList<Object> {
    final boolean first;
    final int epoch;
    List<Object> expanded;
    LocalWidgetCatalogPage(List<?> remote, boolean first, int epoch) {
        super(remote); this.first = first; this.epoch = epoch;
    }
    void expand(List<Object> rows) {
        expanded = rows;
        // A local-only first page needs the stock header/success path. One genuine local row
        // makes it nonempty while preserving the cloud's end-of-data decision (< six).
        if (first && isEmpty() && !rows.isEmpty()) add(rows.get(0));
    }
}
