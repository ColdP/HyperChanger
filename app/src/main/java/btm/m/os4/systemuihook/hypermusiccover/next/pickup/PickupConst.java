// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;

public final class PickupConst {
    static final String PKG_WECHAT = "com.tencent.mm";
    static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    static final String PKG_TEST_FIXTURE = "io.github.mio.autopickupfixture";

    static final String EXTRA_EVENT_JSON = "event_json";
    static final String EXTRA_EVENT_ID = "event_id";
    static final String EXTRA_SOURCE_OPEN_INTENT = "source_open_intent";

    static final String RULE_ASSET = "coloros/sys_aifluid_config_list.xml";
    static final String BUILTIN_RULE_VERSION = "20260225";
    static final int MAX_CONTENT_LENGTH = 40_000;

    static boolean isSourcePackage(String packageName) {
        return switch (PickupEvent.clean(packageName)) {
            case PKG_WECHAT, PKG_ALIPAY, PKG_TEST_FIXTURE,
                    "com.lucky.luckyclient", "com.heyteago", "com.mxbc.mxsa",
                    "com.yek.android.kfc.activitys", "com.mcdonalds.gma.cn",
                    "com.starbucks.cn" -> true;
            default -> false;
        };
    }

    private PickupConst() {
    }
}
