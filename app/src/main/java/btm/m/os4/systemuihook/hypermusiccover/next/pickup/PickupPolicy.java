// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Pure RUS/AIUnitHandler branches verified against ColorOS 16 smali.
 * No service constructors, network, notification cancellation, timers or OCR are invoked here.
 * This is the recognizer's decision layer, NOT the complete downstream card lifecycle.
 */
final class PickupPolicy {
    enum Host { NATIVE, WECHAT, ALIPAY }
    record PrepareStatus(Host host, String appName, String detailPath, List<String> statuses) {
        PrepareStatus(Host host, String appName, String detailPath, String statuses) {
            // m6.m.c uses String.split(","), with no trimming, substring or case folding.
            this(host, appName, detailPath, List.copyOf(Arrays.asList(statuses.split(","))));
        }
    }

    static final class Builder {
        long finishGapMinutes = 240; // m6.e.<init>, not a new heuristic expiry.
        boolean useLabelPathChange;
        boolean sendCardWhenFuzzyMatch;
        boolean useRemoteAi;
        boolean useRemoteImage;
        boolean remoteImageSendCard;
        boolean useObserver;
        final List<PrepareStatus> preparing = new ArrayList<>();
        final List<String> blackLabels = new ArrayList<>();
        final Map<String, PickupWebViewNames> webViewNames = new LinkedHashMap<>();
        final Map<String, List<String>> miniActivities = new LinkedHashMap<>();

        boolean read(String name, String value) {
            switch (name) {
                case "order_status_finish_time_gap" -> {
                    try { finishGapMinutes = Long.parseLong(value); }
                    catch (NumberFormatException ignored) { /* Original retains the prior value. */ }
                }
                case "use_label_path_change" -> useLabelPathChange = Boolean.parseBoolean(value);
                case "send_card_when_fuzzy_match" -> sendCardWhenFuzzyMatch = Boolean.parseBoolean(value);
                case "use_remote_ai_plugin" -> useRemoteAi = Boolean.parseBoolean(value);
                case "use_remote_img" -> useRemoteImage = Boolean.parseBoolean(value);
                case "remote_img_send_card" -> remoteImageSendCard = Boolean.parseBoolean(value);
                case "use_observer" -> useObserver = Boolean.parseBoolean(value);
                case "black_labels" -> {
                    blackLabels.clear();
                    for (String entry : value.split(";")) {
                        if (!entry.trim().isEmpty()) blackLabels.add(entry.trim());
                    }
                }
                default -> { return false; }
            }
            return true;
        }

        /**
         * The two blocks that say which activities a mini program is shown in - m6.e keeps them
         * per platform, and the tags name the platform rather than a package, so `wx_` is 微信's
         * list and `ali_` is 支付宝's. Each is a `;`-separated list that the XML spreads over
         * several lines, and 支付宝's runs to twenty names.
         */
        void readMiniActivities(String name, String value) {
            String owner = switch (name) {
                case "wx_mini_activity" -> PickupConst.PKG_WECHAT;
                case "ali_mini_activity" -> PickupConst.PKG_ALIPAY;
                default -> "";
            };
            if (owner.isEmpty()) return;
            ArrayList<String> activities = new ArrayList<>();
            for (String entry : value.split(";")) {
                String activity = entry.trim();
                if (!activity.isEmpty()) activities.add(activity);
            }
            if (!activities.isEmpty()) miniActivities.put(owner, List.copyOf(activities));
        }

        PickupPolicy build() { return new PickupPolicy(this); }
    }

    final long finishGapMinutes;
    final boolean useLabelPathChange, sendCardWhenFuzzyMatch;
    // These are policy data, never user consent or authorization to upload page content.
    final boolean useRemoteAi, useRemoteImage, remoteImageSendCard, useObserver;
    final List<PrepareStatus> preparing;
    final List<String> blackLabels;
    final Map<String, PickupWebViewNames> webViewNames;
    final Map<String, List<String>> miniActivities;

    private PickupPolicy(Builder builder) {
        finishGapMinutes = builder.finishGapMinutes;
        useLabelPathChange = builder.useLabelPathChange;
        sendCardWhenFuzzyMatch = builder.sendCardWhenFuzzyMatch;
        useRemoteAi = builder.useRemoteAi;
        useRemoteImage = builder.useRemoteImage;
        remoteImageSendCard = builder.remoteImageSendCard;
        useObserver = builder.useObserver;
        preparing = List.copyOf(builder.preparing);
        blackLabels = List.copyOf(builder.blackLabels);
        webViewNames = Map.copyOf(builder.webViewNames);
        miniActivities = Map.copyOf(builder.miniActivities);
    }

    /** The mini program containers the rules name for [packageName]; empty for anyone else's. */
    List<String> miniActivities(String packageName) {
        return packageName == null ? List.of() : miniActivities.getOrDefault(packageName, List.of());
    }

    /** Whether the rules know mini program containers for [packageName] at all. */
    boolean isMiniProgramHost(String packageName) {
        return !miniActivities(packageName).isEmpty();
    }

    /**
     * Whether [className] is one of those containers. A prefix, not equality: 微信 names one class
     * and shows every mini program in a copy of it (`AppBrandUI02` for the phone's second one), and
     * 支付宝 lists the subclasses it uses (`XRiverActivity$App01` and its siblings) beside the
     * plain one they are subclasses of.
     */
    boolean isMiniProgramActivity(String packageName, String className) {
        if (className == null) return false;
        for (String activity : miniActivities(packageName)) {
            if (className.startsWith(activity)) return true;
        }
        return false;
    }

    /** i6.l.k refuses unknown/non-positive installed versions before m6.e.m. */
    List<String> webViewClasses(String packageName, long installedVersion) {
        if (packageName == null || packageName.isEmpty() || installedVersion <= 0) return List.of();
        PickupWebViewNames config = webViewNames.get(packageName);
        return config == null ? List.of() : config.select(installedVersion);
    }

    /** i6.l.g/i/j request-selection branches, read from raw smali. Only type 4
     * consults the installed-version class rules; earlier page-id/node stages do
     * NOT inherit these classes. This is not the full g label/consent decision.
     */
    PickupWebViewSelector.Options webSelection(String packageName, long installedVersion,
                                                String eventSource, int resultType) {
        boolean appExit = "appexit".equals(eventSource);
        if (resultType == 4) {
            return new PickupWebViewSelector.Options(webViewClasses(packageName, installedVersion), "",
                    !"com.eg.android.AlipayGphone".equals(packageName),
                    appExit || "activityexit".equals(eventSource) || "com.ai.myapplication".equals(packageName), false);
        }
        if (resultType == 1 || resultType == 3)
            return new PickupWebViewSelector.Options(List.of(), "", true, appExit, false);
        throw new IllegalArgumentException("NotAnExSystemWebSelectionStage");
    }

    /** g6.f.p: a positive configured time gap REPLACES the textual status, even completed. */
    int initialState(String status, String orderTime, long now) {
        int state = "completed".equals(status) ? 2 : "uncompleted".equals(status) ? 1 : 0;
        return finishGapMinutes <= 0 ? state : stateFromTime(orderTime, finishGapMinutes, now);
    }

    /** g6.f.l. Preserve lenient SimpleDateFormat parsing, local timezone and minute truncation. */
    static int stateFromTime(String orderTime, long gapMinutes, long now) {
        Long age = ageMinutes(orderTime, now);
        return age != null && age >= gapMinutes ? 2 : 1;
    }

    /** g6.f.x: allowNonPositive=true deliberately accepts future times too. */
    static boolean withinTime(String orderTime, long minutes, boolean allowNonPositive, long now) {
        Long age = ageMinutes(orderTime, now);
        return age != null && (allowNonPositive || age > 0) && age <= minutes;
    }

    private static Long ageMinutes(String orderTime, long now) {
        if (orderTime == null || orderTime.isEmpty()) return null;
        try {
            // A fresh formatter avoids introducing a shared non-thread-safe singleton.
            return TimeUnit.MILLISECONDS.toMinutes(now
                    - new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").parse(orderTime).getTime());
        } catch (Exception ignored) { return null; }
    }

    /** g6.f.G + m6.h.n/o/p. Caller uses this only when the original order time is empty. */
    int preparingState(Host host, String appName, String actualPath, int initial, List<String> lines) {
        if (host == null || appName == null || appName.isEmpty() || lines == null || lines.isEmpty())
            return initial;
        for (PrepareStatus item : preparing) {
            if (item.host != host || !item.appName.equals(appName) || item.detailPath.isEmpty()
                    || !item.detailPath.equals(actualPath) || item.statuses.isEmpty()) continue;
            for (String status : item.statuses) if (lines.contains(status)) return 1;
            return 8; // First matching configured detail page wins, not best/fuzzy match.
        }
        return initial;
    }

    int orderState(PickupEvent event, PickupRule rule, PickupOem.Result result, long now) {
        int state = initialState(result.status, result.orderTime, now);
        if (result.orderTime.isEmpty() && event != null && rule != null) {
            Host host = !event.isMiniProgram() ? Host.NATIVE
                    : PickupConst.PKG_WECHAT.equals(event.sourcePackage) ? Host.WECHAT : Host.ALIPAY;
            state = preparingState(host, rule.appName, event.recognitionPath(), state,
                    PickupOem.contentLines(event.content));
        }
        // g6.f.C applies this override AFTER time/prepare status calculation.
        return result.waitingForCode(event) ? 1 : state;
    }

    /** g6.f.v real-code page gate only. Missing bridge evidence is checked separately. */
    boolean rejectRealCodePage(boolean miniProgram, PickupRule rule, String actualPath,
                               boolean fuzzyMatch, boolean orderPage) {
        if (!miniProgram || !useLabelPathChange) return false;
        if (rule == null || rule.isBlacklisted(actualPath)) return true;
        if (!fuzzyMatch && rule.pathWhitelisted(actualPath)) return false;
        return !(sendCardWhenFuzzyMatch && orderPage);
    }

    /** m6.e.M. Use on fuzzy label matching, not as an unconditional exact-label blacklist. */
    boolean blacklistedLabel(String actualLabel) {
        if (actualLabel == null || actualLabel.isEmpty()) return false;
        for (String token : blackLabels) if (actualLabel.contains(token)) return true;
        return false;
    }

    /** g6.f.C + k6.c.s: eligibility only; no network action or user switch is implied. */
    boolean remoteTextEligible(PickupRule rule, String actualPath, boolean hasCode, int state) {
        return hasCode && state != 2 && state != 8 && useRemoteAi
                && rule != null && rule.useCloud(actualPath);
    }

    /** n6.e.a, non-debug branch: neither finished nor not-preparing enters h's cache.
     * Finishing an existing card additionally requires o6.r.a0's exact identity/source checks;
     * this predicate alone NEVER authorizes cancelling any notification.
     */
    static boolean acceptNewLocalCandidate(int state) {
        return state != 2 && state != 8;
    }
}
