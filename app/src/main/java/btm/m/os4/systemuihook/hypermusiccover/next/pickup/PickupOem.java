// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup;
import btm.m.os4.systemuihook.BuildConfig;
import btm.m.os4.systemuihook.hypermusiccover.next.Main;
import btm.m.os4.systemuihook.hypermusiccover.next.Xp;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DelegateLastClassLoader;

/**
 * ColorOS's own pickup-code recognizer, driven from SystemUI.
 *
 * <p>The plugin is OPPO's {@code com.oplus.aiunit.plugin.pcr} bundle; what is shipped beside this
 * file is that bundle with {@code assets/ner_model/} and the two onnxruntime libraries taken out
 * (14.8MB -> 1.8MB). The NER model is the second rung of OPPO's ladder and only runs when the
 * rule-based first rung finds nothing; the rules themselves are in the dex and are what answers
 * for every page tested so far. The files can be put back without touching this code.
 *
 * <p>The plugin does not bundle its own {@code kotlin-stdlib}, {@code gson} or {@code protobuf} -
 * its host supplies them - so this dex must. Missing any of them fails inside the plugin's
 * {@code <clinit>} with {@code NoClassDefFoundError}.
 *
 * <p>Entry point and Bundle keys are the original ones: {@code AssetUtils.setApplicationContext}
 * once, then {@code OrderInfoProcessor.INSTANCE.oderInfoExtract(lines, appName, appPath)}. The
 * plugin's {@code INSTANCE} carries mutable per-order statics, so the whole transaction is
 * serialized. Anything that goes wrong is swallowed: the caller falls back, and a page that reads
 * wrong is worse than a page that reads as unknown.
 */
final class PickupOem {

    private static final String TAG = "MCPickupOem: ";
    private static final String ASSET = "assets/coloros/pcr_plugin.apk";
    private static final String DIR = "hyperchanger_hmc_next_pickup";
    private static final String APK = "pcr_plugin.apk";
    private static final String[] LIBS = {
            "libaiunit_sdk_core.so", "libonnxruntime4j_jni.so", "libonnxruntime.so",
    };

    /** What the plugin answered about one page. Empty [code] means it found none. */
    static final class Result {
        String code = "";
        String product = "";
        String status = "";
        String orderTime = "";
        String processType = "";
        String temperature = "";
        boolean orderPage;

        /**
         * OPPO's own "the code is coming" state: its config asks for a placeholder card rather than
         * a code. The original also requires that the call did not fail - here a failed call is a
         * null [Result] instead of one carrying a failure, so that is already true by the time this
         * can be asked.
         */
        boolean waitingForCode(PickupEvent event) {
            return event != null && event.needWaitingStatus && code.isEmpty()
                    && "waiting".equals(status);
        }
    }

    private static final Object LOCK = new Object();
    private static Object processor;      // OrderInfoProcessor.INSTANCE
    private static Method extract;        // oderInfoExtract(List, String, String)
    private static boolean tried;
    private static boolean inspected;
    private static String failure = "";

    /**
     * The plugin's answer for one page's texts, or null when it is not there to ask - a 32-bit
     * phone, a plugin that will not load, a call that threw. Never a guess.
     */
    static Result recognize(List<String> lines, String appName, String appPath) {
        if (lines.isEmpty() || !prepare()) return null;
        try {
            synchronized (LOCK) {
                Bundle bundle = (Bundle) extract.invoke(processor, lines, appName, appPath);
                inspect();
                return read(bundle);
            }
        } catch (Throwable error) {
            fail(site(error));
            return null;
        }
    }

    /** The page's text as lines, the way the recognizer reads them ([PickupPolicy]'s state port). */
    static List<String> contentLines(String content) {
        ArrayList<String> lines = new ArrayList<>();
        if (content == null) return lines;
        for (String raw : content.split("[\\r\\n]+")) {
            String line = raw.trim();
            if (!line.isEmpty()) lines.add(line);
            if (lines.size() >= 6_000) break;
        }
        return lines;
    }

    /**
     * One of this module's own assets. SystemUI has no context for this module - it has the apk the
     * classes came out of - so the apk is opened as the zip it is, the same way the plugin is read.
     */
    static InputStream asset(String name) throws Exception {
        try (ZipFile zip = new ZipFile(ownApk(Main.appContext()))) {
            ZipEntry entry = zip.getEntry("assets/" + name);
            if (entry == null) throw new java.io.FileNotFoundException(name);
            try (InputStream in = zip.getInputStream(entry)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                return new java.io.ByteArrayInputStream(out.toByteArray());
            }
        }
    }

    /** Loads the plugin once. False means every later call goes straight to the fallback. */
    private static boolean prepare() {
        if (processor != null) return true;
        synchronized (PickupOem.class) {
            if (processor != null) return true;
            if (tried) return false;
            tried = true;
            try {
                Context host = Main.appContext();
                if (host == null) throw new IllegalStateException("no SystemUI context");
                if (Build.SUPPORTED_ABIS.length == 0 || !Build.SUPPORTED_ABIS[0].contains("arm64"))
                    throw new IllegalStateException("not arm64: " + Build.SUPPORTED_ABIS[0]);

                File root = new File(host.getCodeCacheDir(), DIR);
                File apk = unpack(host, root);
                File lib = new File(root, "lib");
                if (!lib.isDirectory() && !lib.mkdirs()) throw new IllegalStateException("lib dir");

                ClassLoader loader = new DelegateLastClassLoader(
                        apk.getAbsolutePath(), lib.getAbsolutePath(), PickupOem.class.getClassLoader());
                Context assets = assets(host, apk, loader);

                Class<?> util = loader.loadClass("com.oplus.aiunit.plugin.utils.AssetUtils");
                util.getMethod("setApplicationContext", Context.class).invoke(null, assets);

                Class<?> type = loader.loadClass("com.oplus.aiunit.plugin.business.OrderInfoProcessor");
                Field instance = type.getField("INSTANCE");
                processor = instance.get(null);
                extract = type.getMethod("oderInfoExtract", List.class, String.class, String.class);
                Xp.log(TAG + "plugin loaded");
                return true;
            } catch (Throwable error) {
                fail(site(error));
                return false;
            }
        }
    }

    /** Copies the plugin out of this apk and unpacks the libraries its loader will be given. */
    private static File unpack(Context host, File root) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("cache dir");
        File apk = new File(root, APK);
        File from = ownApk(host);
        try (ZipFile zip = new ZipFile(from)) {
            ZipEntry entry = zip.getEntry(ASSET);
            if (entry == null) throw new IllegalStateException("no " + ASSET + " in " + from);
            // canWrite() is in here because a copy made before this file was made read-only would
            // otherwise be kept for good, and ART would keep refusing it.
            if (!apk.isFile() || apk.length() != entry.getSize() || apk.canWrite()) {
                File temp = new File(root, APK + ".tmp");
                try (InputStream in = zip.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    copy(in, out);
                    out.getFD().sync();
                }
                if (apk.exists() && !apk.delete()) throw new IllegalStateException("old plugin apk");
                if (!temp.renameTo(apk)) throw new IllegalStateException("plugin rename");
                // ART refuses a dex that the process could still write to - it is the whole point
                // of the check - and says so as a SecurityException out of openDexFileNative.
                if (!apk.setReadOnly()) throw new IllegalStateException("plugin read-only");
            }
            for (String name : LIBS) {
                ZipEntry so = zip.getEntry("lib/" + abi() + "/" + name);
                if (so == null) continue;
                File out = new File(root + "/lib", name);
                if (out.isFile() && out.length() == so.getSize() && !out.canWrite()) continue;
                File temp = new File(root + "/lib", name + ".tmp");
                try (InputStream in = zip.getInputStream(so);
                     FileOutputStream os = new FileOutputStream(temp)) {
                    copy(in, os);
                    os.getFD().sync();
                }
                if (out.exists() && !out.delete()) throw new IllegalStateException("old " + name);
                if (!temp.renameTo(out)) throw new IllegalStateException("rename " + name);
                if (!out.setReadOnly()) throw new IllegalStateException("read-only " + name);
            }
        }
        return apk;
    }

    /**
     * Where this module's own apk is. LSPosed loads these classes out of it, so the code source
     * is it; the package manager is the answer when that comes back as a jar or a bare directory.
     */
    private static File ownApk(Context host) throws Exception {
        try {
            java.security.CodeSource source = PickupOem.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                File file = new File(source.getLocation().getPath());
                if (file.isFile()) return file;
            }
        } catch (Throwable ignored) {
        }
        return new File(host.getPackageManager()
                .getApplicationInfo(BuildConfig.APPLICATION_ID, 0).sourceDir);
    }

    /**
     * A context whose assets and resources are the plugin's, which is how it reads its own
     * {@code config/product_info.json}. Same shape as the original host's.
     */
    private static Context assets(Context host, File apk, ClassLoader loader) throws Exception {
        ResourcesProvider provider;
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)) {
            provider = ResourcesProvider.loadFromApk(fd);
        }
        ResourcesLoader loaderFor = new ResourcesLoader();
        loaderFor.addProvider(provider);

        java.lang.reflect.Constructor<Resources> ctor = Resources.class.getDeclaredConstructor(
                AssetManager.class, DisplayMetrics.class, Configuration.class);
        ctor.setAccessible(true);
        Resources resources = ctor.newInstance(host.getResources().getAssets(),
                host.getResources().getDisplayMetrics(), host.getResources().getConfiguration());
        Method addLoaders = Resources.class.getMethod("addLoaders", ResourcesLoader[].class);
        addLoaders.setAccessible(true);
        addLoaders.invoke(resources, (Object) new ResourcesLoader[]{loaderFor});

        final AssetManager assets = resources.getAssets();
        return new ContextWrapper(host) {
            @Override public AssetManager getAssets() { return assets; }
            @Override public Resources getResources() { return resources; }
            @Override public Context getApplicationContext() { return this; }
            @Override public ClassLoader getClassLoader() { return loader; }
        };
    }

    /**
     * Once, after the plugin has run: what its brand table came out as.
     *
     * <p>The plugin fills it from {@code config/product_info.json} with Gson, resolving the whole
     * library out of this dex - and Gson's generic path is reflective. A table that deserialized
     * into the wrong type throws nothing and answers nothing: no code, no exception, just an empty
     * result for exactly the pages that need the table. One line settles which it is.
     */
    private static void inspect() {
        if (inspected) return;
        inspected = true;
        try {
            Field field = processor.getClass().getDeclaredField("productNames");
            field.setAccessible(true);
            List<?> names = (List<?>) field.get(null);
            String first = names == null || names.isEmpty() ? "-" : names.get(0).getClass().getName();
            Xp.log(TAG + "productNames=" + (names == null ? -1 : names.size()) + " first=" + first);
        } catch (Throwable error) {
            fail(site(error));
        }
    }

    private static Result read(Bundle bundle) {
        Result out = new Result();
        if (bundle == null) return out;
        ArrayList<String> codes = bundle.getStringArrayList("orderCodeList");
        // The first candidate, unchanged - the rule path takes it the same way. Never rebuild a
        // code out of a date, a price or a store number by stripping punctuation.
        if (codes != null && !codes.isEmpty() && codes.get(0) != null) out.code = codes.get(0).trim();
        out.product = text(bundle.getString("productName"));
        out.status = text(bundle.getString("orderStatus"));
        out.orderTime = text(bundle.getString("orderTime"));
        out.processType = text(bundle.getString("processType"));
        out.temperature = text(bundle.getString("drinkTemperature"));
        out.orderPage = bundle.getBoolean("orderPageFlag", false);
        return out;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String abi() {
        return Build.SUPPORTED_ABIS.length == 0 ? "arm64-v8a" : Build.SUPPORTED_ABIS[0];
    }

    /** Which class and method threw - never the page text, which is what the arguments carry. */
    private static String site(Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        StackTraceElement[] frames = cause.getStackTrace();
        return cause.getClass().getSimpleName() + (frames.length > 0 ? "@" + frames[0] : "");
    }

    private static void fail(String why) {
        if (why.equals(failure)) return;
        failure = why;
        Xp.log(TAG + "unavailable: " + why + " - falling back");
    }

    private static void copy(InputStream in, FileOutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
    }

    private PickupOem() {
    }
}
