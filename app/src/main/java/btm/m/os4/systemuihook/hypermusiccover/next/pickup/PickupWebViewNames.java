// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import java.util.ArrayList;
import java.util.List;
import org.xmlpull.v1.XmlPullParser;

/** m6.e.I + m6.p/p$a, checked against ColorOS 16 raw smali.
 * Data only: no package queries, class loading, hooks or network. The host must
 * use the observed installed version, not a guessed latest version.
 */
public final class PickupWebViewNames {
    public record Item(long minimumVersion, List<String> classes) {
        public Item { classes = List.copyOf(classes); }
        public Item(long minimumVersion, String value) { this(minimumVersion, split(value)); }
        private static List<String> split(String value) {
            ArrayList<String> result = new ArrayList<>();
            if (value != null && !value.trim().isEmpty()) {
                for (String entry : value.split(";")) {
                    if (!entry.trim().isEmpty()) result.add(entry.trim());
                }
            }
            return result; // Duplicates and order are intentional (p$a.c).
        }
    }
    private final String packageName;
    private final List<Item> items;
    public PickupWebViewNames(String packageName, List<Item> entries) {
        this.packageName = packageName;
        ArrayList<Item> ordered = new ArrayList<>(entries);
        ordered.sort((first, second) -> Long.compare(second.minimumVersion, first.minimumVersion));
        items = List.copyOf(ordered); // Stable sort preserves equal-version XML order.
    }
    public String packageName() { return packageName; }

    /** p.b: first eligible version group only, never merge older groups. */
    public List<String> select(long version) {
        for (Item item : items) if (version >= item.minimumVersion) return item.classes;
        return List.of();
    }

    /** Consume exactly the original I parser's event sequence. A missing package
     * returns without advancing. The original checks the closing tag AFTER its
     * loop's next(), including its surprising empty-element cursor behavior.
     * Do not silently trim version/package attributes or change miniVersionCode.
     */
    public static PickupWebViewNames read(XmlPullParser parser) {
        try {
            if (parser.getEventType() != XmlPullParser.START_TAG || !"webviewname".equals(parser.getName())) return null;
            String name = parser.getAttributeValue(null, "package_name");
            if (name == null || name.isEmpty()) return null;
            ArrayList<Item> entries = new ArrayList<>();
            int event = parser.next();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                    String version = parser.getAttributeValue(null, "miniVersionCode");
                    String value = parser.nextText().trim();
                    if (version != null && !version.isEmpty() && !value.isEmpty()) {
                        try { entries.add(new Item(Long.parseLong(version), value)); }
                        catch (NumberFormatException ignored) { /* Original skips just this item. */ }
                    }
                }
                event = parser.next();
                if (event == XmlPullParser.END_TAG && "webviewname".equals(parser.getName())) break;
            }
            return new PickupWebViewNames(name, entries);
        } catch (Exception ignored) { return null; }
    }
}
