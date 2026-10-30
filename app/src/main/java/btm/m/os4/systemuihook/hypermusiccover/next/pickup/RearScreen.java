// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;

import btm.m.os4.systemuihook.R;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
/**
 * The back screen's allow-list, and the pickup card's way onto it.
 *
 * `com.xiaomi.subscreencenter` draws a card on the back screen for the packages its own list
 * names - a package and a business together, out of a resource of its own
 * (`<app pkg="..." business="..." group="...">`): 美团 and 淘宝 are `foodDelivery` there, 小爱 is
 * `memory`, and SystemUI is not in it at all. Our pickup notification is posted by SystemUI,
 * because the module lives in that process, so the card reaches the app, is logged, and is
 * dropped before anything is drawn:
 *
 *     postNotification: Package=com.android.systemui, NotificationId=1241, Key=0|com.android.systemui|...
 *     handleNotificationPosted: Package=com.android.systemui, ID=1241
 *     (nothing - no widget for it, while 小爱's own card is built and removed by compositeKey)
 *
 * That list is a compiled resource inside a signed apk, so the only place to say otherwise is in
 * the app's own process: the answer to "may this package draw a widget" is given here, for our
 * package alone.
 *
 * What the app then does with the notification is the pickup card's business, not this file's -
 * `miui.rear.rv` is the card it draws, and [PickupCard] is where the card is built the way a
 * process other than ours can inflate it.
 *
 * The names below are R8's, read off subscreencenter's dex on 2026-10-09 - they move with every
 * release of that app. A lookup that misses costs the card on the back screen and nothing else,
 * which is why each hook is allowed to fail on its own.
 */
public final class RearScreen {
    private static final String TAG = "MCRear: ";

    /** Our notification's package. The list has no entry for it; this is the whole problem. */
    private static final String OURS = "com.android.systemui";
    private static final String AMAP = "com.autonavi.minimap";
    /** AmapTransitIsland.ID, written out: that object is internal to its package. */
    private static final int AMAP_TRANSIT_ID = 1239;

    private RearScreen() {
    }

    public static void handle(ClassLoader cl) {
        // First, and before the lookup below that can return: the card this module posted and then
        // cancelled is still written down in the app's own widget file, and the app's restore brings
        // it back at every start as a card with nothing to draw. See prune().
        prune();

        Class<?> resolver;
        try {
            resolver = Xp.findClass("m2.d", cl);
        } catch (Throwable t) {
            Xp.log(TAG + "resolver class not found, back screen will not show the card: " + t);
            return;
        }
        // The two that ask "may this package's widget be built" and hand back a boolean: the
        // (String, String, map) around a LiveUpdate business, and the (String, Set, map) ours goes
        // through. Nothing here names a template or a widget - the answer is only ever yes or no,
        // which is why saying yes is enough.
        allow(resolver, "l");
        allow(resolver, "m");
        // `n`, `o` and `p` are deliberately left alone, though this once answered yes to all five:
        //
        //   n(String)           "is this a quick-access package". `m` asks it internally, so a yes
        //                       here made the app log a block it then had to be talked out of.
        //   o(String, String)   "is this widget protected" - the answer that makes the app *ignore
        //                       a removal*. A yes left every card we ever posted on the back screen
        //                       for good, and had it written into `notification_widget.json` on the
        //                       way, where a restart restores it with no RemoteViews in it - the
        //                       black box. Cards now go out under an id each (PickupCodeIsland's
        //                       ID), and the removal of the one before is what clears its widget,
        //                       so this must answer as the app means it: no.
        //   p(String)           a business (the ringing ones), never a package - it never saw ours.
        Xp.log(TAG + "allow-list hooks in place for " + OURS);
        startTheDrink();
    }

    /**
     * The drink, started.
     *
     * The card's picture is an animated WebP - ColorOS's cup filling - and the platform hands it
     * back as an `AnimatedImageDrawable` that is not running: `ImageDecoder`'s own contract says
     * the caller starts it, and neither `ImageView` nor anything in the RemoteViews path does. The
     * island's copy of the card is started by the module running in SystemUI, which is the process
     * that builds it; this card is *applied here*, so nothing would start this one and the cup
     * would hold its first frame - half full - for as long as the card is up.
     *
     * `setImageIcon` is the one call `RemoteViews.setImageViewIcon` makes, which is why it is what
     * to watch, and the layout's own id is how the two cards are told apart. Guarded throughout:
     * this runs inside the host's own method, and an exception here takes the card down with it -
     * and this app applies its cards on the main thread, so it takes the app down with it too.
     */
    private static void startTheDrink() {
        try {
            Xp.hookAll("sub.rear.image", android.widget.ImageView.class, "setImageIcon", chain -> {
                Object out = chain.proceed();
                Object view = chain.getThisObject();
                if (view instanceof android.widget.ImageView
                        && ((android.widget.ImageView) view).getId() == R.id.mc_pickup_rear_icon) {
                    try {
                        android.graphics.drawable.Drawable drawable =
                                ((android.widget.ImageView) view).getDrawable();
                        if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable) {
                            ((android.graphics.drawable.AnimatedImageDrawable) drawable).start();
                        }
                    } catch (Throwable t) {
                        Xp.log(TAG + "drink not started: " + t);
                    }
                }
                return out;
            });
            Xp.log(TAG + "watching the card's picture");
        } catch (Throwable t) {
            // The card then holds its first frame. Half a cup is a worse card, not a broken one.
            Xp.log(TAG + "no picture to start: " + t);
        }
    }

    private static void allow(Class<?> resolver, String name) {
        try {
            Xp.hookAll("sub.rear.allow." + Xp.slug(name), resolver, name, chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean ours = args.length > 0 && OURS.equals(args[0]);
                Object result = chain.proceed(args);
                if (ours) {
                    Xp.log(TAG + name + "(" + args[0]
                            + (args.length > 1 ? ", " + args[1] : "") + ") was " + result
                            + ", answered true");
                }
                return ours ? Boolean.TRUE : result;
            });
        } catch (Throwable t) {
            // A name that is not there any more is the expected way this ages: the card simply
            // does not appear on the back screen.
            Xp.log(TAG + "no " + name + "() to hook: " + t);
        }
    }

    // ------------------------------------------------------------------ the card left behind

    private static final String WIDGET_FILE = "notification_widget.json";

    /**
     * Our card taken out of the app's own record of what it has on the back screen.
     *
     * That record is a file (`PersistenceManager` logs `Save notification widgets to` it) written
     * whenever a notification arrives. A Bundle->JSON conversion keeps the plain types only
     * (`o2.AbstractC0666c.b`), so the two keys that *are* the card - `miui.rear.rv` and
     * `miui.rear.rvAOD`, both RemoteViews - are lost on the way in, and all that is left of them is
     * `is_remote_view: true`. A card that is cancelled or times out does not rewrite the file, so
     * its entry stays behind; the app's start-up restore (`LM/j`) reads it back into a `q2.o` with
     * nothing to draw, and that is the black box - `SubScreenWidget - updateViews: mInAod = false,
     * mRemoteViewsDark = null, mRemoteViewsLight = null`, on the back screen until the next start
     * makes it again. Seen four times on 2026-10-09 (19:04, 20:48, 22:52, 23:34), the last of them
     * from a card that had been deleted at 22:52:46.
     *
     * Nothing else can take that entry out: the widget goes when its *notification* is removed, and
     * by then ours is long gone - the module's own sweep cancels what is still posted, which is
     * exactly what this one is not. So it is removed here instead, in the app's own process, as the
     * app's own uid, just before the app reads the file: that is why this runs from handle(), at
     * package load, and not from the process that posts the card. Writing it from SystemUI would
     * also leave the file owned by SystemUI's uid, which is not ours to do to the app.
     */
    private static void prune() {
        try {
            java.io.File f = widgetFile();
            if (!f.isFile() || !f.canRead() || !f.canWrite()) return;
            org.json.JSONArray widgets = new org.json.JSONArray(new String(
                    java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            int dropped = drop(widgets);
            if (dropped == 0) return;
            if (!write(f, widgets.toString())) {
                complain("write refused");
                return;
            }
            Xp.log(TAG + "dropped " + dropped + " stale card entr" + (dropped == 1 ? "y" : "ies")
                    + " from " + WIDGET_FILE);
        } catch (Throwable t) {
            // A file we cannot read or parse is left exactly as it was, and the card is then what it
            // is today - the worst case here is the bug, never a broken app.
            complain(t.toString());
        }
    }

    /**
     * Where the app keeps that list: its own tree under /data/system rather than its data dir,
     * `/data/system/theme_magic/users/<user>/subscreencenter/notification/notification_widget.json`,
     * and `<user>` is the user id, `uid / 100000` - the same derivation [CoverPush] uses for the
     * wallpaper records it reads under the same tree.
     */
    private static java.io.File widgetFile() {
        return new java.io.File("/data/system/theme_magic/users/"
                + (android.os.Process.myUid() / 100000)
                + "/subscreencenter/notification/" + WIDGET_FILE);
    }

    /**
     * Our entries taken out of a loaded list, in place, answering how many went.
     *
     * Backwards, because `remove` shifts everything after it. Out of [prune] so a test can hand it
     * a list with no device and no file behind it.
     */
    static int drop(org.json.JSONArray widgets) {
        int dropped = 0;
        for (int i = widgets.length() - 1; i >= 0; i--) {
            org.json.JSONObject entry = widgets.optJSONObject(i);
            if (entry != null && ours(entry)) {
                widgets.remove(i);
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Whether an entry is ours: SystemUI's, under a tag only this module posts.
     *
     * The tag is the whole of it - `PickupCodeIsland` posts a card as `mc-<elapsedRealtime>` and
     * nothing else in this module tags a notification (the islands go out untagged, by id). The
     * business is no help: ours is `memory`, which is 小爱's as well. The package is asked for too,
     * so that an entry whose tag merely looks like ours is still left alone.
     */
    private static boolean ours(org.json.JSONObject entry) {
        org.json.JSONObject extra = entry.optJSONObject("extra");
        if (extra == null) return false;
        String key = extra.optString("notification_key", "");
        // The bus and subway trip's card (AmapTransitIsland, TransitRear) is posted as 高德, under
        // the one id only this module uses there, and is a RemoteViews card like the pickup's.
        if (key.contains("|" + AMAP + "|" + AMAP_TRANSIT_ID + "|")) return true;
        if (!key.contains("|mc-")) return false;
        return OURS.equals(extra.optString("package_name")) || key.contains("|" + OURS + "|");
    }

    /** Written beside it and renamed over it, as [CoverPush] does: the app may be reading it. */
    private static boolean write(java.io.File f, String json) {
        java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return false;
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(f)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    private static volatile boolean sComplained;

    /** Once per process: a start that cannot be cleaned is worth a line, not one per notification. */
    private static void complain(String why) {
        if (sComplained) return;
        sComplained = true;
        Xp.w(TAG + WIDGET_FILE + " not pruned: " + why);
    }
}
