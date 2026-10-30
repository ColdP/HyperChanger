// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import android.app.PendingIntent;
import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class PickupEvent {
    String sourcePackage = "";
    String activity = "";
    String appId = "";
    String actualAppletId = "";
    String eventSource = "pagechange";
    int userId;
    long sequence;
    String route = "";
    String query = "";
    String href = "";
    String content = "";
    String brand = "";
    String ruleAppName = "";
    String ruleOriginId = "";
    String actualTaskLabel = "";
    String tagAppName = "";
    String category = "";
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
    String ruleVersion = PickupConst.BUILTIN_RULE_VERSION;
    boolean useCloud;
    boolean webView;
    boolean pathObserved;
    boolean fuzzyMatch;
    boolean needWaitingStatus;
    boolean navigationOnly;
    long timestamp = System.currentTimeMillis();
    transient PendingIntent sourceOpenIntent;

    static PickupEvent navigation(String sourcePackage, String activity, String appId,
                                  String route, String query, String href) {
        PickupEvent event = new PickupEvent();
        event.sourcePackage = clean(sourcePackage);
        event.activity = clean(activity);
        event.appId = clean(appId);
        event.route = observedPath(route);
        event.pathObserved = !event.route.isEmpty();
        event.query = stripQuestion(clean(query));
        event.href = clean(href);
        event.navigationOnly = true;
        return event;
    }

    Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(PickupConst.EXTRA_EVENT_JSON, toJson().toString());
        bundle.putString(PickupConst.EXTRA_EVENT_ID, id());
        if (sourceOpenIntent != null) {
            bundle.putParcelable(PickupConst.EXTRA_SOURCE_OPEN_INTENT, sourceOpenIntent);
        }
        return bundle;
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("sourcePackage", sourcePackage);
            json.put("activity", activity);
            json.put("appId", appId);
            json.put("actualAppletId", actualAppletId);
            json.put("eventSource", eventSource);
            json.put("userId", userId);
            json.put("sequence", sequence);
            json.put("route", route);
            json.put("query", query);
            json.put("href", href);
            json.put("content", truncate(content, PickupConst.MAX_CONTENT_LENGTH));
            json.put("brand", brand);
            json.put("ruleAppName", ruleAppName);
            json.put("ruleOriginId", ruleOriginId);
            json.put("actualTaskLabel", actualTaskLabel);
            json.put("tagAppName", tagAppName);
            json.put("category", category);
            json.put("launchPath", launchPath);
            json.put("appPackage", appPackage);
            json.put("logo", logo);
            json.put("sticker", sticker);
            json.put("baseStyle", baseStyle);
            json.put("aodImage", aodImage);
            json.put("pickupColor", pickupColor);
            json.put("pickupButtonColor", pickupButtonColor);
            json.put("cardColor", cardColor);
            json.put("cardAlpha", cardAlpha);
            json.put("ruleVersion", ruleVersion);
            json.put("useCloud", useCloud);
            json.put("webView", webView);
            json.put("pathObserved", pathObserved);
            json.put("fuzzyMatch", fuzzyMatch);
            json.put("needWaitingStatus", needWaitingStatus);
            json.put("navigationOnly", navigationOnly);
            json.put("timestamp", timestamp);
        } catch (JSONException ignored) {
        }
        return json;
    }

    static PickupEvent fromBundle(Bundle bundle) {
        if (bundle == null) return null;
        PickupEvent event = fromJson(bundle.getString(PickupConst.EXTRA_EVENT_JSON, ""));
        if (event == null) return null;
        PendingIntent open = bundle.getParcelable(
                PickupConst.EXTRA_SOURCE_OPEN_INTENT, PendingIntent.class);
        if (open != null && event.sourcePackage.equals(open.getCreatorPackage())) {
            event.sourceOpenIntent = open;
        }
        return event;
    }

    static PickupEvent fromJson(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 100_000) return null;
        try {
            JSONObject json = new JSONObject(raw);
            PickupEvent event = new PickupEvent();
            event.sourcePackage = clean(json.optString("sourcePackage"));
            event.activity = clean(json.optString("activity"));
            event.appId = clean(json.optString("appId"));
            event.actualAppletId = clean(json.optString("actualAppletId"));
            event.eventSource = clean(json.optString("eventSource", "pagechange"));
            event.userId = json.optInt("userId", 0);
            event.sequence = json.optLong("sequence", 0);
            event.route = observedPath(json.optString("route"));
            event.query = stripQuestion(clean(json.optString("query")));
            event.href = truncate(clean(json.optString("href")), 4_096);
            event.content = truncate(clean(json.optString("content")), PickupConst.MAX_CONTENT_LENGTH);
            event.brand = clean(json.optString("brand"));
            event.ruleAppName = clean(json.optString("ruleAppName"));
            event.ruleOriginId = clean(json.optString("ruleOriginId"));
            event.actualTaskLabel = truncate(clean(json.optString("actualTaskLabel")), 200);
            event.tagAppName = clean(json.optString("tagAppName"));
            event.category = clean(json.optString("category"));
            event.launchPath = normalizeRoute(json.optString("launchPath"));
            event.appPackage = clean(json.optString("appPackage"));
            event.logo = clean(json.optString("logo"));
            event.sticker = clean(json.optString("sticker"));
            event.baseStyle = clean(json.optString("baseStyle"));
            event.aodImage = clean(json.optString("aodImage"));
            event.pickupColor = color(json.optString("pickupColor"), "#FFFFFF");
            event.pickupButtonColor = color(json.optString("pickupButtonColor"), "#3482FF");
            event.cardColor = color(json.optString("cardColor"), "#3A3A3A");
            event.cardAlpha = alpha(json.optDouble("cardAlpha", 0.12d), 0.12f);
            event.ruleVersion = clean(json.optString("ruleVersion", PickupConst.BUILTIN_RULE_VERSION));
            // Do not reinterpret the legacy local-model flag as permission to upload data.
            event.useCloud = json.optBoolean("useCloud");
            event.webView = json.optBoolean("webView");
            event.pathObserved = json.optBoolean("pathObserved") && !event.route.isEmpty();
            event.fuzzyMatch = json.optBoolean("fuzzyMatch");
            event.needWaitingStatus = json.optBoolean("needWaitingStatus");
            event.navigationOnly = json.optBoolean("navigationOnly");
            event.timestamp = json.optLong("timestamp", System.currentTimeMillis());
            return event.isStructurallyValid() ? event : null;
        } catch (JSONException ignored) {
            return null;
        }
    }

    boolean isStructurallyValid() {
        if (sourcePackage.isEmpty()) return false;
        if (PickupConst.PKG_WECHAT.equals(sourcePackage)
                || PickupConst.PKG_ALIPAY.equals(sourcePackage)) {
            // Missing route is a valid diagnostic event, not proof of an order page.
            return !appId.isEmpty() && (!activity.isEmpty() || !route.isEmpty() || navigationOnly);
        }
        return !activity.isEmpty() || !route.isEmpty();
    }

    boolean isFresh(long maxAgeMs) {
        long age = System.currentTimeMillis() - timestamp;
        return age >= -30_000L && age <= maxAgeMs;
    }

    String recognitionPath() {
        return webView ? (pathObserved ? route : "") : activity;
    }

    boolean isMiniProgram() {
        return PickupConst.PKG_WECHAT.equals(sourcePackage) || PickupConst.PKG_ALIPAY.equals(sourcePackage);
    }

    String id() {
        return digest(sourcePackage + '\n' + appId + '\n' + route + '\n' + query + '\n'
                + content + '\n' + timestamp / 1_000L).substring(0, 20);
    }

    String navigationKey() {
        return digest(sourcePackage + '\n' + appId + '\n' + route + '\n' + query);
    }

    String openPath() {
        String path = launchPath.isEmpty() ? route : launchPath;
        if (path.equals(route) && !query.isEmpty()) return path + "?" + query;
        return path;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode()) + "00000000000000000000";
        }
    }

    static String normalizeRoute(String value) {
        String route = clean(value);
        int scheme = route.indexOf("#/");
        if (scheme >= 0) route = route.substring(scheme + 2);
        while (route.startsWith("/")) route = route.substring(1);
        int q = route.indexOf('?');
        if (q >= 0) route = route.substring(0, q);
        return route.trim();
    }

    static String observedPath(String value) {
        // k6.c.i treats exactly "null" as absent. Do not trim/case-fold a captured
        // path into an entry that the original exact whitelist would not accept.
        return value == null || "null".equals(value) ? "" : value;
    }

    private static String stripQuestion(String value) {
        String result = value;
        while (result.startsWith("?")) result = result.substring(1);
        return result;
    }

    static String clean(String value) {
        return value == null || "null".equalsIgnoreCase(value.trim()) ? "" : value.trim();
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    static String color(String value, String fallback) {
        String c = clean(value);
        return c.matches("#[0-9a-fA-F]{6,8}") ? c : fallback;
    }

    static float alpha(double value, float fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return fallback;
        return (float) Math.max(0d, Math.min(1d, value));
    }
}
