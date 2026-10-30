// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import java.util.ArrayList;
import java.util.List;

final class PickupRule {
    static final class Target {
        String value = "";
        // m6.n.b / m6.l.c: permission for the remote algorithm, NOT local ONNX.
        boolean useCloud;
        boolean ignoreVisibility;
    }

    String packageName = "";
    String category = "";
    String label = "";
    String appName = "";
    String brandName = "";
    String originId = "";
    String tagAppName = "";
    String launchPath = "";
    String appPackage = "";
    String logo = "";
    String sticker = "";
    String baseStyle = "";
    String aodImage = "";
    String pickupColor = "#FFFFFF";
    String pickupButtonColor = "#3482FF";
    String cardColor = "#3A3A3A";
    float cardAlpha = 0.12f;
    String blackPaths = "";
    boolean webView;
    boolean extractRootPortal;
    boolean needWaitingStatus;
    /** Whether the meal's name is shown on the card; `<show_meal_name>`, on unless said otherwise. */
    boolean showMealName = true;
    final List<Target> filterPaths = new ArrayList<>();
    final List<Target> activities = new ArrayList<>();

    boolean originMatches(String candidate) {
        return !originId.isEmpty() && originId.equalsIgnoreCase(PickupEvent.clean(candidate));
    }

    Target pathTarget(String route) {
        return isBlacklisted(route) ? null : exactTarget(filterPaths, route);
    }

    Target activityTarget(String activity) {
        return isBlacklisted(activity) ? null : exactTarget(activities, activity);
    }

    boolean pathWhitelisted(String route) {
        return exactTarget(filterPaths, route) != null; // m6.k.b, exact String.equals.
    }

    boolean activityWhitelisted(String activity) {
        return exactTarget(activities, activity) != null; // m6.k.a.
    }

    boolean useCloud(String actualPathOrActivity) {
        Target target = exactTarget(webView ? filterPaths : activities, actualPathOrActivity);
        return target != null && target.useCloud; // m6.k.w does not consult blacklist.
    }

    /**
     * The brand as ColorOS's card names it: the rule's brand name, else its app name, else the label.
     * The label is the mini program's own name (「益禾堂手机点单」), which is what the config matches
     * pages by, not what it shows.
     */
    String displayName() {
        return !brandName.isEmpty() ? brandName : (!appName.isEmpty() ? appName : label);
    }

    PickupEvent event(String activity, String appId, String route, String query,
                      String href, String content, Target target, String ruleVersion) {
        PickupEvent event = new PickupEvent();
        event.sourcePackage = packageName;
        event.activity = PickupEvent.clean(activity);
        event.appId = PickupEvent.clean(appId);
        event.route = PickupEvent.observedPath(route); // Actual __route__, not a URL to canonicalize.
        event.query = PickupEvent.clean(query).replaceFirst("^\\?+", "");
        event.href = PickupEvent.clean(href);
        event.content = PickupEvent.truncate(PickupEvent.clean(content), PickupConst.MAX_CONTENT_LENGTH);
        event.brand = displayName();
        event.ruleAppName = appName;
        event.ruleOriginId = originId;
        event.webView = webView;
        event.pathObserved = !event.route.isEmpty();
        event.tagAppName = tagAppName;
        event.category = category;
        event.launchPath = PickupEvent.clean(launchPath);
        event.appPackage = appPackage;
        event.logo = logo;
        event.sticker = sticker;
        event.baseStyle = baseStyle;
        event.aodImage = aodImage;
        event.pickupColor = pickupColor;
        event.pickupButtonColor = pickupButtonColor;
        event.cardColor = cardColor;
        event.cardAlpha = cardAlpha;
        event.useCloud = target != null && target.useCloud;
        event.needWaitingStatus = needWaitingStatus;
        event.ruleVersion = ruleVersion;
        event.navigationOnly = false;
        return event;
    }

    boolean isBlacklisted(String candidate) {
        // m6.k.z / k6.c.i: a missing path is blacklisted even with an empty list.
        if (candidate == null || candidate.isEmpty() || "null".equals(candidate)) return true;
        List<String> configured = new ArrayList<>();
        for (String raw : blackPaths.split(";")) {
            String black = raw.trim();
            if (!black.isEmpty()) configured.add(black);
        }
        // The token is special only when it is the sole nonempty blacklist entry.
        if (configured.size() == 1 && "black_all".equals(configured.get(0))) {
            return !(webView ? pathWhitelisted(candidate) : activityWhitelisted(candidate));
        }
        return configured.contains(candidate);
    }

    private static Target exactTarget(List<Target> targets, String value) {
        if (value == null || value.isEmpty()) return null;
        for (Target target : targets) {
            if (value.equals(target.value)) return target;
        }
        return null;
    }
}
