// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;

/**
 * Who may drive the module's probe receivers.
 *
 * Those receivers live in other apps' processes - SystemUI, the wallpaper process, 高德 - and
 * have to be exported: the app, the other hooked processes and adb all reach them from outside.
 * Exported with nothing in front of them, any app on the phone could do the same: read what is
 * playing out of `query`, wipe the user's lock wallpaper with `lockwp --ez clear true`, put a
 * picture of its choosing on the lock screen, or rewrite every setting the module keeps.
 *
 * So each receiver is registered twice, and every broadcast is let through by exactly one of them:
 *
 *   - Without a permission, for the module's own senders. Every send in this module shares its
 *     identity ({@link #options()}), so the system tells the receiver which package sent it, and
 *     only the packages named at registration - the app and the processes the module runs in - and
 *     the core uids (root, system, shell) are answered.
 *   - Behind android.permission.DUMP, for adb. `am broadcast` cannot share an identity, but the
 *     shell holds DUMP and no third-party app can. Only an anonymous broadcast is answered here;
 *     one that carries an identity is the other registration's, so nothing is handled twice.
 *
 * The wallpaper process's broadcasts to SystemUI arrived with no identity on the test phone, and
 * that process does not hold DUMP, so neither registration took them - silently: SystemUI never
 * heard `wphello`, never sent the source, and every tap into the cover composed and encoded the
 * full-screen JPEG in SystemUI first (2026-10-01, `op tail` empty for op=wp). So SystemUI also
 * mints a token ({@link #mint}) that rides on everything it sends the wallpaper process - which
 * setPackage keeps from any other app - and the wallpaper process hands it back on everything it
 * sends SystemUI. An anonymous broadcast carrying that token is the open registration's; one
 * without it is still adb's.
 */
public final class ProbeGuard {

    private ProbeGuard() {
    }

    private static final String DUMP = "android.permission.DUMP";
    private static final int SHELL_UID = 2000;

    /** The options every probe broadcast goes out with: the sender's identity, for the check. */
    public static Bundle options() {
        BroadcastOptions o = BroadcastOptions.makeBasic();
        o.setShareIdentityEnabled(true);
        return o.toBundle();
    }

    /** A probe broadcast, sent the way the receivers will take it. */
    public static void send(Context ctx, Intent intent) {
        stamp(intent);
        ctx.sendBroadcast(intent, null, options());
    }

    private static final String TOKEN = "mctoken";
    private static final String WALLPAPER = "com.miui.miwallpaper";
    private static final String SYSTEMUI = "com.android.systemui";

    /** SystemUI's token, or the one the wallpaper process last heard from SystemUI. */
    private static volatile String sToken;
    /** This process minted sToken: the one that checks it, not the one that hands it back. */
    private static volatile boolean sMinted;

    /**
     * The token as it stands, for a hot-reloaded generation to inherit. Null when this process
     * has not minted (or been given) one yet.
     */
    static synchronized String token() {
        return sMinted ? sToken : null;
    }

    /**
     * The token this process learned from SystemUI, for a hot-reloaded generation to inherit.
     * Null for the process that mints (it is handed over through {@link #token}).
     */
    static synchronized String learnedToken() {
        return sMinted ? null : sToken;
    }

    /**
     * Takes on the token the retiring generation learned from SystemUI. The wallpaper process
     * does not mint one; a fresh generation that started with nothing would have every message
     * it sends refused until SystemUI next said hello.
     */
    static synchronized void adoptLearnedToken(String token) {
        if (token == null || token.isEmpty() || sMinted) return;
        sToken = token;
        Xp.log("[MCProbe] learned token inherited from the previous generation");
    }

    /**
     * Takes on the retiring generation's token instead of minting a new one: the wallpaper
     * process holds the old token and would refuse every hello until it heard a new one.
     */
    static synchronized void adoptToken(String token) {
        if (token == null || token.isEmpty() || sMinted) return;
        sToken = token;
        sMinted = true;
        Xp.log("[MCProbe] token inherited from the previous generation");
    }

    /** SystemUI's half, once: the token it hands the wallpaper process and takes back from it. */
    static synchronized void mint() {
        if (sMinted) return;
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        sToken = sb.toString();
        sMinted = true;
    }

    /** On its way out: to the wallpaper process from SystemUI, and back to SystemUI from it. */
    private static void stamp(Intent intent) {
        String token = sToken;
        if (token == null) return;
        String pkg = intent.getPackage();
        if (sMinted ? WALLPAPER.equals(pkg) : SYSTEMUI.equals(pkg)) intent.putExtra(TOKEN, token);
    }

    /** Whether [i] carries this process's own token, which only the wallpaper process was given. */
    private static boolean carriesOurs(Intent i) {
        String token = sToken;
        return sMinted && token != null && token.equals(i.getStringExtra(TOKEN));
    }

    /** A receiver that knows which of its two registrations it came in through, and whom it trusts. */
    public abstract static class Receiver extends BroadcastReceiver {
        private boolean viaDump;
        private String[] trusted = new String[0];
        private String tag = "";
    }

    /**
     * Registers two receivers from [make] for [filter]. [trusted] are the packages whose
     * broadcasts are answered; [tag] prefixes the log line for a broadcast that is not.
     */
    public static void register(Context ctx, IntentFilter filter, String tag,
                         java.util.function.Supplier<? extends Receiver> make, String... trusted) {
        register(ctx, filter, tag, make, true, trusted);
    }

    /**
     * The same pair, outside the ledger: for an inert generation, whose ledger refuses every
     * registration and whose receivers have all been swept. Never unregistered - the process
     * has to restart to leave that state, and the pair goes with it.
     */
    static void registerOutsideLedger(Context ctx, IntentFilter filter, String tag,
                         java.util.function.Supplier<? extends Receiver> make, String... trusted) {
        register(ctx, filter, tag, make, false, trusted);
    }

    private static void register(Context ctx, IntentFilter filter, String tag,
                         java.util.function.Supplier<? extends Receiver> make, boolean ledger,
                         String... trusted) {
        Receiver open = make.get();
        open.trusted = trusted;
        open.tag = tag;
        Receiver adb = make.get();
        adb.viaDump = true;
        adb.trusted = trusted;
        adb.tag = tag;
        if (ledger) {
            Gen.receiver(ctx, open, filter, Context.RECEIVER_EXPORTED);
            Gen.receiver(ctx, adb, new IntentFilter(filter), DUMP, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(open, filter, Context.RECEIVER_EXPORTED); // gen-ok
            ctx.registerReceiver(adb, new IntentFilter(filter), DUMP, null, // gen-ok
                    Context.RECEIVER_EXPORTED);
        }
    }

    /** How often a refused sender is logged: a hostile app could otherwise flood the log. */
    private static final long REFUSED_LOG_MS = 10_000L;
    private static volatile long sRefusedLoggedAt;

    /**
     * Whether [r] should act on the broadcast [i] it is handling now. Called first thing in
     * onReceive; a refused broadcast is left exactly as it arrived, so an ordered one comes back
     * unanswered. An admitted one has the token taken out, so no log line from its extras has it.
     */
    public static boolean admit(Receiver r, Intent i) {
        boolean ok = check(r, i);
        // Only from a broadcast already let through: the wallpaper process learns SystemUI's
        // token from what SystemUI sends it, not from whoever claims to have one.
        if (!ok) return false;
        if (!sMinted) {
            String token = i.getStringExtra(TOKEN);
            if (token != null) sToken = token;
        }
        // Never into the log lines the receivers write out of the extras. Only once let through:
        // the other registration may be handed the same Intent, and still has to read it.
        i.removeExtra(TOKEN);
        return true;
    }

    private static boolean check(Receiver r, Intent i) {
        int uid = r.getSentFromUid();
        // One carrying our token is the open registration's, even with DUMP behind it.
        if (r.viaDump) return uid == Process.INVALID_UID && !carriesOurs(i);
        if (uid == Process.INVALID_UID) {
            // The wallpaper process, handing SystemUI's token back.
            if (carriesOurs(i)) return true;
            // Otherwise adb's, answered by the DUMP registration if the sender holds it.
            return false;
        }
        // root, system, shell, and this process's own app.
        if (uid == 0 || uid == Process.SYSTEM_UID || uid == SHELL_UID || uid == Process.myUid()) {
            return true;
        }
        String pkg = r.getSentFromPackage();
        if (pkg != null) {
            for (String t : r.trusted) {
                if (pkg.equals(t)) return true;
            }
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sRefusedLoggedAt > REFUSED_LOG_MS) {
            sRefusedLoggedAt = now;
            Xp.log(r.tag + "refused a probe broadcast from " + pkg + " (uid "
                    + uid + ")");
        }
        return false;
    }
}
