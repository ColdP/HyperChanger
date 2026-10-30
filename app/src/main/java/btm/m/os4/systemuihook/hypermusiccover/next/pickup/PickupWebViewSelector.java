// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Port of WebViewExtractHelper.findWebView* / isWebView, checked against smali.
 * The trusted platform adapter supplies ONLY the target Activity's ordered roots
 * and its isVisibleToUser implementation. No all-process window enumeration,
 * guessed visibility fallback, hooks or long-lived View references are installed.
 */
public final class PickupWebViewSelector {
    public record Options(List<String> classes, String evaluateMethod, boolean lastOnly,
                          boolean ignoreVisible, boolean enableWebGroup) {
        public Options {
            classes = classes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(classes));
            evaluateMethod = evaluateMethod == null || evaluateMethod.isEmpty() ? "evaluateJavascript" : evaluateMethod;
        }
    }
    public interface Visibility { boolean visibleToUser(View view) throws Exception; }
    public enum Status { OK, NO_MATCH, UNSUPPORTED, FAILED, LIMIT }
    public record Selection(Status status, List<View> views) { }
    private static final int MAX_ROOTS = 64, MAX_NODES = 8192, MAX_DEPTH = 128, MAX_VIEWS = 32;
    private PickupWebViewSelector() { }

    /** Hidden API availability is a capability check, NOT an exemption/bypass.
     * If OS4 denies this access, the caller must supply the platform component's
     * implementation or fail closed, never substitute isShown()/screen OCR.
     */
    public static Visibility frameworkVisibility() {
        try {
            Method method = View.class.getMethod("isVisibleToUser");
            return view -> Boolean.TRUE.equals(method.invoke(view));
        } catch (Throwable ignored) { return null; }
    }

    public static Selection select(List<View> roots, Options options, Visibility visibility) {
        if (Looper.myLooper() != Looper.getMainLooper() || roots == null || options == null
                || (visibility == null && !(options.lastOnly && options.ignoreVisible)))
            return empty(Status.UNSUPPORTED);
        try {
            if (roots.size() > MAX_ROOTS || options.classes.size() > 256 || options.evaluateMethod.length() > 128)
                return empty(Status.LIMIT);
            Walker walker = new Walker(options, visibility);
            if (options.lastOnly) {
                for (int i = roots.size() - 1; i >= 0; i--) if (walker.visit(roots.get(i), 0)) break;
            } else {
                for (View root : roots) walker.visit(root, 0);
            }
            return new Selection(walker.matches.isEmpty() ? Status.NO_MATCH : Status.OK,
                    List.copyOf(walker.matches));
        } catch (Limit ignored) { return empty(Status.LIMIT); }
        catch (Throwable ignored) { return empty(Status.FAILED); }
    }
    private static Selection empty(Status status) { return new Selection(status, List.of()); }
    private static final class Limit extends RuntimeException { }
    private static final class Walker {
        final Options options;
        final Visibility visibility;
        final ArrayList<View> matches = new ArrayList<>();
        int visited;
        Walker(Options options, Visibility visibility) { this.options = options; this.visibility = visibility; }
        boolean visit(View view, int depth) throws Exception {
            if (view == null) return false; // Host guard; never continue from a truncated result after a limit.
            if (++visited > MAX_NODES || depth > MAX_DEPTH) throw new Limit();
            // ignoreViewVisible is used ONLY by the original reverse/last-only path.
            if (!(options.lastOnly && options.ignoreVisible) && !visibility.visibleToUser(view)) return false;
            if (isWebView(view, options)) {
                if (matches.size() >= MAX_VIEWS) throw new Limit();
                matches.add(view);
                if (options.lastOnly) return true;
                if (!options.enableWebGroup) return false;
            }
            if (view instanceof ViewGroup group) {
                int count = group.getChildCount();
                if (options.lastOnly) {
                    for (int i = count - 1; i >= 0; i--) if (visit(group.getChildAt(i), depth + 1)) return true;
                } else {
                    for (int i = 0; i < count; i++) visit(group.getChildAt(i), depth + 1);
                }
            }
            return false;
        }
    }
    private static boolean isWebView(View view, Options options) {
        if (view instanceof WebView || options.classes.contains(view.getClass().getName())) return true;
        String name = view.getClass().getName();
        String last = name.substring(name.lastIndexOf('.') + 1);
        if (!last.contains("WebView") && !last.contains("Webview")) return false;
        // Original android.util.Reflect.methodWithParamCount accepts any two-arg
        // signature, including declared/inherited methods. Safe invocation is a
        // SEPARATE step in ColorOsWebViewClient (ambiguous signatures fail closed).
        for (Method method : view.getClass().getMethods()) if (matches(method, options.evaluateMethod)) return true;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass())
            for (Method method : type.getDeclaredMethods()) if (matches(method, options.evaluateMethod)) return true;
        return false;
    }
    private static boolean matches(Method method, String name) {
        return name.equals(method.getName()) && method.getParameterTypes().length == 2;
    }
}
