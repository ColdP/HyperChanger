package btm.m.os4.systemuihook.newnotificationcenter;

import android.view.View;
import android.view.ViewGroup;
import com.android.systemui.shade.NotificationPanelView;
import com.android.systemui.statusbar.notification.row.NotificationBackgroundView;
import com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView;
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
        MiuiMediaHeaderView media = new MiuiMediaHeaderView();
        root.addView(panel);
        panel.addView(row);
        panel.addView(clear);
        panel.addView(media);
        View unrelated = new View();
        root.addView(unrelated);

        int cycles = args.length > 0 && args[0].equals("partial") ? 0 : 20;
        for (int cycle = 0; cycle < cycles; cycle++) {
            IosShadeMaterial.setActive(true, root);
            check(panel.mode == 1 && panel.radius == 80 && !panel.pass, "open panel material");
            check(row.mode == 1 && row.radius == 80 && !row.pass, "open notification material");
            check(clear.mode == 1 && !clear.pass, "clear button material");
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

            // A heads-up makes this same window draw; no iOS material may be reacquired.
            row.setMiBackgroundBlurRadius(12);
            row.setMiViewBlurMode(1);
            root.getViewTreeObserver().layout();
            check(panel.mode == 0 && row.radius == 12 && row.pass, "heads-up keeps native recipe");
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
        check(partial.mode == 1 && partial.radius == 29 && !partial.pass && !partial.disabled,
                "explicit native setter establishes reversible ownership");
        partial.setMiBackgroundBlurMode(0);
        check(partial.mode == 0, "shutdown works even when getter fails");
        IosShadeMaterial.setActive(false, otherRoot);
        check(partial.mode == 0 && partial.radius == 29 && partial.pass && partial.disabled,
                "restore latest explicit requests without guessing missing properties");
        check(partial.type == 2, "unknown blur type never overwritten");
        System.out.println("PASS: " + checks + " material restore checks");
    }
}
