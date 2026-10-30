package btm.m.os4.systemuihook.newnotificationcenter;

import android.view.View;
import android.view.ViewGroup;
import com.android.systemui.shade.NotificationPanelView;
import com.android.systemui.statusbar.notification.row.NotificationBackgroundView;
import com.android.systemui.statusbar.notification.row.NotificationExpandButton;
import com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerInjectorImpl;
import com.miui.systemui.widget.CircleAndTickAnimView;

/** Exercises the production material class through mock Xposed setter chains. */
public final class MaterialRestoreTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        IosShadeMaterial.install();
        ViewGroup root = new ViewGroup();
        NotificationPanelView panel = new NotificationPanelView();
        NotificationBackgroundView row = new NotificationBackgroundView();
        CircleAndTickAnimView clear = new CircleAndTickAnimView();
        NotificationExpandButton collapse = new NotificationExpandButton();
        NotificationBackgroundView collapseBackground = new NotificationBackgroundView();
        View idCollapse = new View();
        idCollapse.id = 8;
        NotificationChildrenContainer group = new NotificationChildrenContainer();
        NotificationChildrenContainerInjectorImpl groupInjector =
                new NotificationChildrenContainerInjectorImpl();
        NotificationBackgroundView injectedCollapse = new NotificationBackgroundView();
        NotificationBackgroundView injectedHeader = new NotificationBackgroundView();
        groupInjector.mCollapsedButton = injectedCollapse;
        groupInjector.mMiuiGroupHeader = injectedHeader;
        group.mInjector = groupInjector;
        MiuiMediaHeaderView media = new MiuiMediaHeaderView();
        root.addView(panel);
        panel.addView(row);
        panel.addView(clear);
        root.addView(collapse);
        collapse.addView(collapseBackground);
        root.addView(idCollapse);
        root.addView(group);
        group.addView(injectedCollapse);
        group.addView(injectedHeader);
        panel.addView(media);
        View unrelated = new View();
        root.addView(unrelated);

        int cycles = args.length > 0 && args[0].equals("partial") ? 0 : 20;
        for (int cycle = 0; cycle < cycles; cycle++) {
            IosShadeMaterial.setActive(true, root);
            check(panel.mode == 0 && panel.type == 2 && panel.radius == 0 && panel.pass,
                    "opening must not enable a full-screen local material");
            check(row.mode == 1 && row.radius == 80 && !row.pass, "open notification material");
            check(clear.mode == 1 && !clear.pass, "clear button material");
            check(collapse.mode == 1 && !collapse.pass,
                    "group collapse button material");
            check(collapseBackground.mode == 1 && !collapseBackground.pass,
                    "group collapse background material");
            check(idCollapse.mode == 1 && !idCollapse.pass,
                    "resource-named group collapse material");
            check(injectedCollapse.mode == 1 && !injectedCollapse.pass,
                    "injected group collapse button material");
            check(injectedHeader.mode == 1 && !injectedHeader.pass,
                    "injected group header material");
            check(media.holder.mediaBg.mode == 1 && !media.holder.mediaBg.pass, "media material");
            check(unrelated.mode == 0 && unrelated.pass, "unrelated window child untouched");

            // OEM has already cached enabled=false; it need not repeat this setter later.
            panel.setMiBackgroundBlurMode(0);
            check(panel.mode == 0, "native full-screen shutdown must survive an unfinished exit");
            root.getViewTreeObserver().layout();
            check(panel.mode == 0, "layout must not reactivate the disabled panel");
            row.setMiBackgroundBlurRadius(37);
            IosShadeMaterial.setActive(false, root);
            check(panel.mode == 0 && panel.radius == 0 && panel.pass, "panel restored after close");
            check(row.mode == 0 && row.radius == 37 && row.pass, "latest row recipe restored");
            check(clear.mode == 0 && clear.pass && media.holder.mediaBg.pass, "other surfaces restored");
            check(!row.textureVisible && !clear.textureVisible && !media.holder.mediaBg.textureVisible,
                    "cleanup must not re-register disabled surfaces as visible window blur samplers");

            // A heads-up makes this same window draw; no iOS material may be reacquired.
            row.setMiBackgroundBlurRadius(12);
            row.setMiViewBlurMode(1);
            root.getViewTreeObserver().layout();
            check(panel.mode == 0 && row.radius == 12 && row.pass, "heads-up keeps native recipe");
            check(!row.textureVisible && !clear.textureVisible && !media.holder.mediaBg.textureVisible,
                    "heads-up redraw must not revive a stale window blur sampler");
            panel.setMiBackgroundBlurMode(1);
            panel.setMiBackgroundBlurRadius(45);
            panel.setPassWindowBlurEnabled(true);
            check(panel.mode == 1 && panel.radius == 45 && panel.pass && panel.type == 2,
                    "heads-up native panel recipe passes through after cleanup");
            panel.setMiBackgroundBlurMode(0);
            panel.setMiBackgroundBlurRadius(0);
            row.setMiBackgroundBlurRadius(0);
            int writes = panel.writes;
            IosShadeMaterial.setActive(false, root);
            check(panel.writes == writes, "repeated cleanup has no extra setter calls");
        }

        // Theme changes and heads-up reuse the full-screen panel, whose provider caches
        // enable/pass/radius independently. Do not require another mode=0 to undo our writes.
        for (int type : new int[]{0, 2}) {
            panel.setMiBackgroundBlurMode(1);
            panel.setMiBackgroundBlurType(type);
            panel.setMiBackgroundBlurRadius(46);
            panel.setPassWindowBlurEnabled(true);
            IosShadeMaterial.setActive(true, root);
            check(panel.mode == 1 && panel.type == type && panel.radius == 46 && panel.pass,
                    "native full-screen recipe survives activation for either theme");
            panel.setMiBackgroundBlurRadius(0);
            panel.setPassWindowBlurEnabled(false);
            check(panel.radius == 0 && !panel.pass,
                    "zero-radius native transition is not replaced with local blur");
            panel.setMiBackgroundBlurMode(0);
            root.getViewTreeObserver().layout();
            panel.setMiBackgroundBlurMode(1);
            panel.setMiBackgroundBlurRadius(23);
            panel.setPassWindowBlurEnabled(true);
            check(panel.mode == 1 && panel.type == type && panel.radius == 23 && panel.pass,
                    "heads-up native recipe survives while iOS exit is still active");
            IosShadeMaterial.setActive(false, root);
            check(panel.mode == 1 && panel.type == type && panel.radius == 23 && panel.pass,
                    "cleanup does not replay a stale full-screen recipe");
        }

        // Restore both enabled and disabled native samplers, including a native shutdown
        // received before the iOS exit finishes and a replacement shade-window root.
        for (boolean nativePass : new boolean[]{false, true}) {
            for (int nativeMode : new int[]{0, 1}) {
                ViewGroup recipeRoot = new ViewGroup();
                NotificationBackgroundView card = new NotificationBackgroundView();
                recipeRoot.addView(card);
                card.setPassWindowBlurEnabled(nativePass);
                card.setMiBackgroundBlurMode(1);
                card.setMiBackgroundBlurMode(nativeMode);
                IosShadeMaterial.setActive(true, recipeRoot);
                check(card.mode == 1 && !card.pass && !card.textureVisible,
                        "local material releases its window sampler while active");
                IosShadeMaterial.setActive(false, recipeRoot);
                check(card.mode == nativeMode && card.pass == nativePass
                                && card.textureVisible == (nativePass && nativeMode != 0),
                        "cleanup restores native flags and native sampler visibility together");
                IosShadeMaterial.setActive(true, recipeRoot);
                card.setMiBackgroundBlurMode(0);
                check(card.mode == 1, "local card stays owned until exit cleanup");
                IosShadeMaterial.setActive(true, new ViewGroup());
                check(card.mode == 0 && card.pass == nativePass && !card.textureVisible,
                        "root replacement retires old-window samplers using latest native mode");
                IosShadeMaterial.setActive(false, null);
            }
        }

        // A getter may exist but fail on a ROM. Never alter a value we cannot restore.
        NotificationPanelView partial = new NotificationPanelView();
        partial.failModeRead = partial.failTypeRead = partial.failRadiusRead = true;
        partial.failPassRead = partial.failContainRead = true;
        ViewGroup otherRoot = new ViewGroup();
        otherRoot.addView(partial);
        IosShadeMaterial.setActive(true, otherRoot);
        check(partial.writes == 0, "unknown native state must remain untouched");
        partial.setMiBackgroundBlurMode(1);
        partial.setMiBackgroundBlurRadius(29);
        partial.setPassWindowBlurEnabled(true);
        partial.disableMiBackgroundContainBelow(true);
        check(partial.mode == 1 && partial.radius == 29 && partial.pass && !partial.disabled,
                "explicit native setter establishes reversible ownership");
        partial.setMiBackgroundBlurMode(0);
        check(partial.mode == 0, "shutdown works even when getter fails");
        IosShadeMaterial.setActive(false, otherRoot);
        check(partial.mode == 0 && partial.radius == 29 && partial.pass && partial.disabled,
                "restore latest explicit requests without guessing missing properties");
        check(partial.type == 2, "unknown blur type never overwritten");

        NotificationBackgroundView unknownRow = new NotificationBackgroundView();
        unknownRow.failModeRead = unknownRow.failTypeRead = unknownRow.failRadiusRead = true;
        unknownRow.failPassRead = unknownRow.failContainRead = true;
        otherRoot.addView(unknownRow);
        IosShadeMaterial.setActive(true, otherRoot);
        check(unknownRow.writes == 0, "unknown card properties are still left untouched");
        unknownRow.setMiBackgroundBlurRadius(29);
        unknownRow.setPassWindowBlurEnabled(true);
        check(unknownRow.radius == 29 && !unknownRow.pass,
                "explicit card requests still establish local material ownership");
        IosShadeMaterial.setActive(false, otherRoot);
        check(unknownRow.radius == 29 && unknownRow.pass && unknownRow.type == 2,
                "known card properties restored without overwriting unknown values");
        System.out.println("PASS: " + checks + " material restore checks");
    }
}
