// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

import android.content.res.Configuration;
import android.content.res.Resources;

/** Stock picker footprints, resolved on the detail worker and refreshed with configuration. */
final class LocalWidgetCatalogSizes {
    static final int[][] SPANS = {{1,1}, {1,2}, {2,1}, {2,2}, {4,1}, {2,3}, {4,2}, {4,4}, {8,4}};
    final Configuration configuration;
    private final int[] widths, heights;

    LocalWidgetCatalogSizes(Resources resources, String pkg) {
        configuration = new Configuration(resources.getConfiguration());
        widths = new int[SPANS.length]; heights = new int[SPANS.length];
        for (int i = 0; i < SPANS.length; i++) {
            String prefix = "pa_widget_size_type_" + SPANS[i][0] + SPANS[i][1];
            int width = resources.getIdentifier(prefix + "_width", "dimen", pkg);
            int height = resources.getIdentifier(prefix + "_height", "dimen", pkg);
            if (width != 0 && height != 0) {
                widths[i] = resources.getDimensionPixelSize(width);
                heights[i] = resources.getDimensionPixelSize(height);
            }
        }
    }

    LocalWidgetCatalogSizes(int[] widths, int[] heights) {
        configuration = null; this.widths = widths.clone(); this.heights = heights.clone();
    }

    int select(int targetX, int targetY, int minWidth, int minHeight) {
        // Explicit provider cell counts take precedence over legacy min-dimension hints.
        if (targetX > 0 && targetY > 0 && LocalWidgetCatalogPolicy.style(targetX, targetY) != 0)
            return LocalWidgetCatalogPolicy.style(targetX, targetY);
        if ((targetX <= 0 && minWidth <= 0) || (targetY <= 0 && minHeight <= 0)) return 0;
        int best = -1, area = Integer.MAX_VALUE;
        for (int i = 0; i < SPANS.length; i++) {
            int x = SPANS[i][0], y = SPANS[i][1];
            if (widths[i] <= 0 || heights[i] <= 0) continue;
            // 8x4 is a special scaled/tablet style, not a larger phone footprint.
            if (x == 8 && targetX <= 4) continue;
            boolean fitsX = targetX > 0 ? x >= targetX : widths[i] >= minWidth;
            boolean fitsY = targetY > 0 ? y >= targetY : heights[i] >= minHeight;
            if (fitsX && fitsY && x * y < area) { best = i; area = x * y; }
        }
        // Never turn an unrepresentable footprint into 2x2. Leave it out rather than
        // bind a permanently undersized MIUI widget that the user cannot resize.
        return best < 0 ? 0 : LocalWidgetCatalogPolicy.style(SPANS[best][0], SPANS[best][1]);
    }

    static int spanX(int style) {
        for (int[] span : SPANS) if (LocalWidgetCatalogPolicy.style(span[0], span[1]) == style) return span[0];
        throw new IllegalArgumentException("Unknown picker style");
    }
    static int spanY(int style) {
        for (int[] span : SPANS) if (LocalWidgetCatalogPolicy.style(span[0], span[1]) == style) return span[1];
        throw new IllegalArgumentException("Unknown picker style");
    }
}
