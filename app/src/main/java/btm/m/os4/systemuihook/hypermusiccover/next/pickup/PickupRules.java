// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import android.content.Context;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PickupRules {
    private static volatile PickupRules instance;

    private final List<PickupRule> rules;
    private final Set<String> packages;
    private final Map<String, String> cloudNamesByOrigin;
    private final Set<String> cloudDisabledOrigins;
    private final String version;
    private final PickupPolicy recognitionPolicy;

    private PickupRules(Context host) throws Exception {
        ParseResult parsed;
        try (InputStream input = PickupOem.asset(PickupConst.RULE_ASSET)) {
            parsed = parse(input);
        }
        rules = Collections.unmodifiableList(parsed.rules);
        recognitionPolicy = parsed.policy;
        HashSet<String> packageNames = new HashSet<>();
        for (PickupRule rule : rules) packageNames.add(rule.packageName);
        packages = Collections.unmodifiableSet(packageNames);
        CloudPolicy cloudPolicy = readCloudPolicy(host);
        cloudNamesByOrigin = cloudPolicy.namesByOrigin;
        cloudDisabledOrigins = cloudPolicy.disabledOrigins;
        version = parsed.version.isEmpty() ? PickupConst.BUILTIN_RULE_VERSION : parsed.version;
    }

    /**
     * The rules, built once and kept until [invalidate].
     *
     * This used to ask the reference implementation's ContentProvider for a revision on every
     * call, and for an overlay XML on every build. That provider lives in its own app, which this
     * module is not, so each call was a binder round trip to system_server that could only fail -
     * hundreds an hour, in bursts, from SystemUI - and had it ever answered, any app claiming the
     * authority could have handed SystemUI its rules. A change of rules comes from [PickupCloud],
     * which calls [invalidate].
     */
    static PickupRules get(Context context) {
        PickupRules current = instance;
        if (current != null) return current;
        synchronized (PickupRules.class) {
            current = instance;
            if (current == null) {
                try {
                    Context application = context.getApplicationContext();
                    current = new PickupRules(application == null ? context : application);
                } catch (Exception e) {
                    throw new IllegalStateException("ColorOS rules unavailable", e);
                }
                instance = current;
            }
        }
        return current;
    }

    static void invalidate() {
        instance = null;
    }

    String version() {
        return version;
    }

    int size() {
        return rules.size();
    }

    List<PickupRule> allRules() { return rules; }

    PickupPolicy recognitionPolicy() { return recognitionPolicy; }

    PickupRule recognitionRule(Context context, PickupEvent event) throws Exception {
        if (event == null) return null;
        PickupRule fallback = null;
        for (PickupRule rule : rules) {
            if (!rule.packageName.equals(event.sourcePackage) || rule.webView != event.webView
                    || !rule.originId.equals(event.ruleOriginId) || !rule.appName.equals(event.ruleAppName)
                    || cloudDisabledOrigins.contains(comparable(rule.originId))) continue;
            if (rule.webView ? rule.pathWhitelisted(event.recognitionPath())
                    : rule.activityWhitelisted(event.activity)) return rule;
            if (fallback == null) fallback = rule;
        }
        return fallback; // Preserve the matched rule even when its real-code page gate will reject.
    }

    boolean hasRules(String packageName) {
        return packages.contains(PickupEvent.clean(packageName));
    }

    PickupRule matchMini(String packageName, String appId, String route) {
        for (PickupRule rule : rules) {
            if (!rule.packageName.equals(packageName)) continue;
            if (cloudDisabledOrigins.contains(comparable(rule.originId))) continue;
            if (!rule.originId.isEmpty() && !rule.originMatches(appId)) continue;
            PickupRule.Target target = rule.pathTarget(route);
            if (target != null) return rule;
        }
        return null;
    }

    PickupRule.Target pathTarget(PickupRule rule, String route) {
        return rule == null ? null : rule.pathTarget(route);
    }

    PickupRule matchNative(String packageName, String activity) {
        for (PickupRule rule : rules) {
            if (!rule.packageName.equals(packageName)) continue;
            if (rule.activityTarget(activity) != null) return rule;
        }
        return null;
    }

    PickupRule.Target activityTarget(PickupRule rule, String activity) {
        return rule == null ? null : rule.activityTarget(activity);
    }

    boolean isKnownOrigin(String packageName, String appId) {
        for (PickupRule rule : rules) {
            if (rule.packageName.equals(packageName)
                    && !cloudDisabledOrigins.contains(comparable(rule.originId))
                    && rule.originMatches(appId)) return true;
        }
        return false;
    }

    boolean hasWebRules(String packageName) {
        for (PickupRule rule : rules) {
            if (rule.packageName.equals(packageName) && rule.webView) return true;
        }
        return false;
    }

    boolean hasNativeRules(String packageName) {
        for (PickupRule rule : rules) {
            if (rule.packageName.equals(packageName) && !rule.activities.isEmpty()) return true;
        }
        return false;
    }

    PickupRule matchPage(String packageName, String taskLabel, String content) {
        String cleanPackage = PickupEvent.clean(packageName);
        String label = comparable(taskLabel);
        String body = comparable(PickupEvent.truncate(content, PickupConst.MAX_CONTENT_LENGTH));
        // AssistStructure contains the whole task. Never select a mini-program rule from body
        // text alone: a chat, search result or another mini-program can mention the same brand.
        // ColorOS has an applet-id/path signal; on Xiaomi the task label is the equivalent
        // low-cost, non-invasive scope boundary available to this fallback path. That holds on
        // both platforms - 微信 gives each mini program a task of its own, and so does 支付宝
        // (`AppBrandUI02` and `XRiverActivity$App01`, each in a task named after the program) -
        // so the scope is asked of whoever the rules call a mini program host, not of 微信 alone.
        boolean requireLabelScope = recognitionPolicy.isMiniProgramHost(cleanPackage);
        PickupRule best = null;
        int bestScore = 0;
        for (PickupRule rule : rules) {
            if (!rule.webView || !rule.packageName.equals(cleanPackage)) continue;
            String origin = comparable(rule.originId);
            if (cloudDisabledOrigins.contains(origin)) continue;
            if (!rule.label.equals(taskLabel) && recognitionPolicy.blacklistedLabel(taskLabel)) continue;
            if (requireLabelScope && !labelMatches(rule, label,
                    cloudNamesByOrigin.getOrDefault(origin, ""))) continue;
            int score = score(rule, label, body);
            if (score > bestScore) {
                best = rule;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The rule for one mini program, by the appId the page's own launch intent carries.
     *
     * This is ColorOS's own index (`PickupCodeOderQuery.appId` theirs, `origin_id` here), and it is
     * the only one that needs no guess: 支付宝's entries in this config are keyed by it, and a page
     * whose appId is not in it is a page the config has nothing to say about.
     */
    PickupRule ruleForOrigin(String packageName, String appId) {
        for (PickupRule rule : rules) {
            if (!rule.packageName.equals(PickupEvent.clean(packageName))) continue;
            if (cloudDisabledOrigins.contains(comparable(rule.originId))) continue;
            if (rule.originMatches(appId)) return rule;
        }
        return null;
    }

    /**
     * The rule for a brand by name, on either platform.
     *
     * A brand is one company whether it is reached through 微信 or 支付宝 - 蜜雪冰城's own rule is
     * written for 微信, but it names the same logo, the same drink and the same red - so a page
     * whose appId the config does not list is still drawn with its brand's card rather than with
     * none. That matters because 支付宝's entries here are three brands where the 微信 ones are
     * 44, and a bare code is a worse answer than the brand's own card.
     *
     * Matched on the rule's own names: [label], [brandName], [appName]. An exact name wins outright.
     * A looser match - either name containing the other - is only taken from a name of three
     * characters or more, because the caller's name is a search query as often as it is a brand:
     * 「咖啡」, 「奶茶」, 「炸鸡」 are searches a user makes before opening a shop of their own
     * choosing, and any of them would otherwise borrow the first brand that happens to contain it.
     * Two characters still match exactly (「喜茶」 is a brand), which is the one case where a short
     * name is worth believing.
     */
    PickupRule matchBrand(String name) {
        String wanted = comparable(name);
        if (wanted.length() < 2) return null;
        PickupRule best = null;
        int bestScore = 0;
        for (PickupRule rule : rules) {
            if (cloudDisabledOrigins.contains(comparable(rule.originId))) continue;
            int score = brandScore(wanted, rule);
            if (score > bestScore) {
                best = rule;
                bestScore = score;
            }
        }
        return best;
    }

    private static int brandScore(String wanted, PickupRule rule) {
        int score = 0;
        for (String configured : new String[]{rule.label, rule.brandName, rule.appName}) {
            String candidate = comparable(configured);
            if (candidate.length() < 2) continue;
            if (wanted.equals(candidate)) score = Math.max(score, 100_000 + candidate.length());
            else if (wanted.length() >= 3
                    && (wanted.contains(candidate) || candidate.contains(wanted))) {
                score = Math.max(score, 10_000 + Math.min(candidate.length(), wanted.length()));
            }
        }
        return score;
    }

    boolean hasPageScope(String packageName, String taskLabel) {
        String label = comparable(taskLabel);
        for (PickupRule rule : rules) {
            String origin = comparable(rule.originId);
            if (rule.webView && rule.packageName.equals(packageName)
                    && (rule.label.equals(taskLabel) || !recognitionPolicy.blacklistedLabel(taskLabel))
                    && !cloudDisabledOrigins.contains(origin)
                    && labelMatches(rule, label, cloudNamesByOrigin.getOrDefault(origin, ""))) return true;
        }
        return false;
    }

    private static boolean labelMatches(PickupRule rule, String label, String cloudName) {
        if (label.isEmpty()) return false;
        return labelContains(label, comparable(rule.label))
                || labelContains(label, comparable(rule.appName))
                || labelContains(label, comparable(rule.brandName))
                || labelContains(label, comparable(cloudName));
    }

    private static boolean labelContains(String label, String candidate) {
        return candidate.length() >= 2 && label.contains(candidate);
    }

    private static int score(PickupRule rule, String label, String body) {
        int score = 0;
        String configuredLabel = comparable(rule.label);
        String appName = comparable(rule.appName);
        String brandName = comparable(rule.brandName);
        if (!configuredLabel.isEmpty()) {
            if (label.equals(configuredLabel)) score = Math.max(score, 100_000 + configuredLabel.length());
            else if (label.contains(configuredLabel) || configuredLabel.contains(label) && label.length() >= 2) {
                score = Math.max(score, 80_000 + configuredLabel.length());
            }
            if (body.contains(configuredLabel)) score = Math.max(score, 60_000 + configuredLabel.length());
        }
        score = Math.max(score, descriptiveScore(label, body, appName, 40_000));
        score = Math.max(score, descriptiveScore(label, body, brandName, 30_000));
        return score;
    }

    private static int descriptiveScore(String label, String body, String needle, int base) {
        if (needle.length() < 2) return 0;
        if (label.equals(needle)) return base + 20_000 + needle.length();
        if (label.contains(needle)) return base + 10_000 + needle.length();
        return body.contains(needle) ? base + needle.length() : 0;
    }

    private static String comparable(String value) {
        return PickupEvent.clean(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * The brand names and the switch-offs the cloud's applet list carries.
     *
     * This used to be a ContentProvider call - the reference implementation's own app - and that
     * provider does not exist here: the app this ships in is `com.github.zyl6932.HyperMusicCover`,
     * so the call could only ever throw and the list was always empty. [PickupCloud] is where it
     * comes from now, filled from ColorOS's cloud by the module itself, and its `disabled` flag is
     * read for the first time.
     */
    private static CloudPolicy readCloudPolicy(Context host) {
        try {
            return new CloudPolicy(PickupCloud.INSTANCE.names(), PickupCloud.INSTANCE.disabled());
        } catch (Exception ignored) {
            return CloudPolicy.EMPTY;
        }
    }

    private record CloudPolicy(Map<String, String> namesByOrigin,
                               Set<String> disabledOrigins) {
        private static final CloudPolicy EMPTY = new CloudPolicy(Map.of(), Set.of());
    }

    /**
     * What the parser makes of a config, without a Context to build a [PickupRules] around.
     *
     * The blocks this exists for - `wx_mini_activity` / `ali_mini_activity` - are plain tags with
     * no provider behind them, and one that parses to nothing reads exactly like a phone with no
     * mini programs open: silently. So this is the seam the on-device probe drives
     * (`scratch/pickup-probe/rules`, `app_process` over the debug apk's own dex - release strips
     * it, nothing in the module calls it), which is the only place the parser can be run against
     * the config that actually ships: `android.util.Xml` is a stub off-device, so a plain JVM
     * test of it cannot be written.
     */
    static PickupPolicy parsePolicy(String xml) throws Exception {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).policy;
    }

    static boolean looksLikeRuleXml(String xml) {
        if (xml == null || xml.length() < 100 || xml.length() > 500_000) return false;
        try {
            ParseResult result = parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return result.rules.size() >= 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    static long checkedCloudVersion(String xml) throws Exception {
        if (xml == null || xml.length() > 500_000 || xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
            throw new IllegalArgumentException("UnsafeXml");
        XmlPullParser check = Xml.newPullParser();
        check.setInput(new java.io.StringReader(xml));
        if (check.nextTag() != XmlPullParser.START_TAG || !"config".equals(check.getName()))
            throw new IllegalArgumentException("WrongXmlRoot");
        ParseResult parsed = parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        if (parsed.rules.isEmpty() || !parsed.version.matches("[0-9]{1,12}"))
            throw new IllegalArgumentException("InvalidXmlRules");
        return Long.parseLong(parsed.version);
    }

    private static ParseResult parse(InputStream input) throws Exception {
        byte[] bytes = readAll(input);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
        ParseResult result = new ParseResult();
        PickupPolicy.Builder policy = new PickupPolicy.Builder();
        PickupRule current = null;
        String section = "";
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("scan".equals(name)) {
                    current = new PickupRule();
                    current.packageName = attr(parser, "package_name");
                    current.category = attr(parser, "category");
                    current.label = attr(parser, "label");
                    current.webView = Boolean.parseBoolean(attr(parser, "isWebView"));
                } else if ("filter_paths".equals(name)) {
                    section = "paths";
                } else if ("monitor_activities".equals(name)) {
                    section = "activities";
                } else if ("item".equals(name) && current != null) {
                    PickupRule.Target target = new PickupRule.Target();
                    target.useCloud = Boolean.parseBoolean(attr(parser, "useCloud"));
                    target.ignoreVisibility = Boolean.parseBoolean(attr(parser, "ignoreVis"));
                    if ("paths".equals(section)) {
                        target.value = attr(parser, "path");
                        if (!target.value.isEmpty()) current.filterPaths.add(target);
                    } else if ("activities".equals(section)) {
                        target.value = attr(parser, "ac");
                        if (!target.value.isEmpty()) current.activities.add(target);
                    }
                } else if ("version".equals(name) && current == null) {
                    result.version = PickupEvent.clean(parser.nextText());
                } else if (current == null && "webviewname".equals(name)) {
                    var names = PickupWebViewNames.read(parser);
                    // m6.h.c replaces the prior entry for the exact package; it
                    // does not merge classes across repeated XML blocks.
                    if (names != null) policy.webViewNames.put(names.packageName(), names);
                } else if (current == null && isMiniActivityTag(name)) {
                    policy.readMiniActivities(name, parser.nextText());
                } else if (current == null && isPrepareTag(name)) {
                    PickupPolicy.Host host = switch (name) {
                        case "order_prepare_status_wx" -> PickupPolicy.Host.WECHAT;
                        case "order_prepare_status_ali" -> PickupPolicy.Host.ALIPAY;
                        default -> PickupPolicy.Host.NATIVE;
                    };
                    policy.preparing.add(new PickupPolicy.PrepareStatus(host,
                            attr(parser, "app_name"), attr(parser, "detail_activity"), attr(parser, "process_status")));
                } else if (current == null && isPolicyTag(name)) {
                    policy.read(name, parser.nextText().trim());
                } else if (current != null && isSimpleRuleTag(name)) {
                    String value = parser.nextText();
                    assign(current, name, "black_paths".equals(name) ? value.trim() : PickupEvent.clean(value));
                }
            } else if (type == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("filter_paths".equals(name) || "monitor_activities".equals(name)) {
                    section = "";
                } else if ("scan".equals(name) && current != null) {
                    if (!current.packageName.isEmpty()) result.rules.add(current);
                    current = null;
                }
            }
        }
        result.policy = policy.build();
        return result;
    }

    private static boolean isMiniActivityTag(String name) {
        return "wx_mini_activity".equals(name) || "ali_mini_activity".equals(name);
    }

    private static boolean isPrepareTag(String name) {
        return "order_prepare_status".equals(name) || "order_prepare_status_wx".equals(name)
                || "order_prepare_status_ali".equals(name);
    }

    private static boolean isPolicyTag(String name) {
        return switch (name) {
            case "order_status_finish_time_gap", "use_label_path_change", "send_card_when_fuzzy_match",
                    "use_remote_ai_plugin", "use_remote_img", "remote_img_send_card", "use_observer",
                    "black_labels" -> true;
            default -> false;
        };
    }

    private static boolean isSimpleRuleTag(String name) {
        return switch (name) {
            case "app_name", "brand_name", "origin_id", "tag_ai_app_name", "launch_path",
                    "app_package", "app_logo", "stickers", "base_bg_style", "aod_static_image",
                    "pickup_btn_color", "pickup_code_color", "card_bg_color", "card_bg_alpha",
                    "black_paths", "extract_wx_root_portal", "need_waiting_status",
                    "show_meal_name" -> true;
            default -> false;
        };
    }

    private static void assign(PickupRule rule, String name, String value) {
        switch (name) {
            case "app_name" -> rule.appName = value;
            case "brand_name" -> rule.brandName = value;
            case "origin_id" -> rule.originId = value;
            case "tag_ai_app_name" -> rule.tagAppName = value;
            case "launch_path" -> rule.launchPath = value;
            case "app_package" -> rule.appPackage = value;
            case "app_logo" -> rule.logo = value;
            case "stickers" -> rule.sticker = value;
            case "base_bg_style" -> rule.baseStyle = value;
            case "aod_static_image" -> rule.aodImage = value;
            case "pickup_btn_color" -> rule.pickupButtonColor =
                    PickupEvent.color(value, "#3482FF");
            case "pickup_code_color" -> rule.pickupColor = PickupEvent.color(value, "#FFFFFF");
            case "card_bg_color" -> rule.cardColor = PickupEvent.color(value, "#3A3A3A");
            case "card_bg_alpha" -> {
                try {
                    rule.cardAlpha = PickupEvent.alpha(Double.parseDouble(value), 0.12f);
                } catch (RuntimeException ignored) {
                    rule.cardAlpha = 0.12f;
                }
            }
            case "black_paths" -> rule.blackPaths = value;
            case "extract_wx_root_portal" -> rule.extractRootPortal = Boolean.parseBoolean(value);
            case "need_waiting_status" -> rule.needWaitingStatus = Boolean.parseBoolean(value);
            case "show_meal_name" -> rule.showMealName = Boolean.parseBoolean(value);
            default -> {
            }
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    static boolean containsSameRule(List<PickupRule> rules, PickupRule candidate) {
        for (PickupRule rule : rules) {
            if (rule.packageName.equals(candidate.packageName)
                    && rule.originId.equals(candidate.originId)
                    && rule.webView == candidate.webView
                    && rule.needWaitingStatus == candidate.needWaitingStatus
                    && rule.category.equals(candidate.category)
                    && rule.blackPaths.equals(candidate.blackPaths)
                    && sameTargets(rule.activities, candidate.activities)
                    && sameTargets(rule.filterPaths, candidate.filterPaths)) return true;
        }
        return false;
    }

    private static boolean sameTargets(List<PickupRule.Target> first, List<PickupRule.Target> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            PickupRule.Target a = first.get(index), b = second.get(index);
            if (!a.value.equals(b.value) || a.useCloud != b.useCloud
                    || a.ignoreVisibility != b.ignoreVisibility) return false;
        }
        return true;
    }

    private static final class ParseResult {
        final ArrayList<PickupRule> rules = new ArrayList<>();
        String version = "";
        PickupPolicy policy;
    }
}
