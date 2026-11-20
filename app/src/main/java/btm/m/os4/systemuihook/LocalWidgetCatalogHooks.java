// SPDX-License-Identifier: GPL-3.0-only
// Adapted from HyperModifier; see licenses/HyperModifier-GPL-3.0.txt and NOTICE.
package btm.m.os4.systemuihook;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Extends the stock flat application list and native AppWidget details, never synthesizes cards. */
final class LocalWidgetCatalogHooks {
    static final String PACKAGE = "com.miui.personalassistant";
    private static final String ROOT = "com.miui.personalassistant.picker.";
    private static final String DETAIL = ROOT + "business.detail.bean.";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    static void install(XposedModule module, ClassLoader loader, SharedPreferences preferences,
                        java.util.function.Function<Executable, XposedInterface.HookBuilder> hookBuilder) throws Throwable {
        if (Build.VERSION.SDK_INT < 36 || !INSTALLED.compareAndSet(false, true)) return;
        Binding binding = null;
        try {
            // Resolve the complete API/model contract before installing any Hook.
            binding = new Binding(module, loader, preferences, hookBuilder);
            binding.install();
            android.util.Log.i("HyperChangerWidgets", "Assistant local widget catalogue hooks ready");
        } catch (Throwable error) {
            if (binding != null) binding.rollback();
            INSTALLED.set(false);
            throw error;
        }
    }
    private static final class Binding {
        final XposedModule module;
        final java.util.function.Function<Executable, XposedInterface.HookBuilder> hookBuilder;
        final SharedPreferences preferences;
        final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        final AtomicInteger generation = new AtomicInteger();
        final AtomicBoolean failed = new AtomicBoolean();
        final AtomicBoolean deliveryReported = new AtomicBoolean();
        final Method prepareIcons, iconBody, convertApps, detailLoad, listLoad, resume,
                intercepted, completion, continuationContext, contextElement, jobActive, parseMetadata, getTargetItem;
        final Class<?> homeRequest, baseContinuation, continuation, wrapperType, detailState, resultFailure;
        final Field requestPage, application, appPackage, appCount, responseList, responseCode,
                detailCode, responseWidget, responsePackage, widgetProvider, itemProvider, itemComponent, itemUser, itemMiui,
                failureCause;
        final Field[] wrapperFields;
        final Constructor<?> appConstructor, detailConstructor, widgetConstructor, tipConstructor, failureConstructor;
        final Object suspended, jobKey;
        final LocalWidgetCatalogFlow detailFlow;
        final LocalWidgetCatalogModels localModels;
        final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
        volatile boolean enabled;
        volatile WeakReference<Context> context = new WeakReference<>(null);
        volatile Snapshot snapshot;
        LocalWidgetCatalogSizes sizes;
        boolean registered;
        final List<XposedInterface.HookHandle> handles = new ArrayList<>();
        boolean listening;

        Binding(XposedModule module, ClassLoader loader, SharedPreferences preferences,
                java.util.function.Function<Executable, XposedInterface.HookBuilder> hookBuilder) throws Throwable {
            this.module = module; this.preferences = preferences;
            this.hookBuilder = hookBuilder;
            Class<?> repo = type(loader, ROOT + "business.detail.PickerDetailRepository");
            detailLoad = exactMethod(repo, "getPickerData", 10);
            detailState = type(loader, ROOT + "business.detail.PickerDetailRepository$getPickerData$1");
            continuation = detailLoad.getParameterTypes()[9];
            detailFlow = new LocalWidgetCatalogFlow(
                    type(loader, String.join(".", "kotlinx", "coroutines", "flow", "q")),
                    type(loader, String.join(".", "kotlinx", "coroutines", "flow", "d")),
                    type(loader, String.join(".", "kotlinx", "coroutines", "flow", "e")), continuation);
            resume = continuation.getMethod("resumeWith", Object.class);
            continuationContext = continuation.getMethod("getContext");
            baseContinuation = type(loader, String.join(".", "kotlin", "coroutines", "jvm", "internal", "BaseContinuationImpl"));
            completion = baseContinuation.getMethod("getCompletion");
            intercepted = type(loader, String.join(".", "kotlin", "coroutines", "jvm", "internal", "ContinuationImpl"))
                    .getMethod("intercepted");
            LocalWidgetCatalogJob job = new LocalWidgetCatalogJob(continuationContext.getReturnType(),
                    type(loader, String.join(".", "kotlinx", "coroutines", "JobCancellationException"))
                            .getField("job").getType());
            jobKey = job.key;
            contextElement = job.contextGet;
            jobActive = job.active;
            suspended = type(loader, String.join(".", "kotlin", "coroutines", "intrinsics", "CoroutineSingletons"))
                    .getField("COROUTINE_SUSPENDED").get(null);
            resultFailure = type(loader, String.join(".", "kotlin", "Result$Failure"));
            failureCause = field(resultFailure, "exception");
            failureConstructor = resultFailure.getConstructor(Throwable.class);
            Class<?> listRepo = type(loader, ROOT + "business.list.viewmodel.PickerAppListRepository");
            listLoad = listRepo.getDeclaredMethod("loadDataFromService", int.class, int.class, continuation);
            prepareIcons = type(loader, ROOT + "business.list.viewmodel.PickerAppListRepositoryKt")
                    .getDeclaredMethod("traversalAppListDataSetLocalIcon", List.class, continuation);
            iconBody = type(loader, ROOT + "business.list.viewmodel.PickerAppListRepositoryKt$traversalAppListDataSetLocalIcon$2")
                    .getDeclaredMethod("invokeSuspend", Object.class);
            Class<?> home = type(loader, ROOT + "business.home.viewmodel.PickerMainViewModel");
            convertApps = home.getDeclaredMethod("convertAppListToCards", List.class);
            homeRequest = type(loader, ROOT + "business.home.viewmodel.PickerMainViewModel$loadAppList$1");
            requestPage = field(homeRequest, "$requestPage");
            application = field(type(loader, "com.miui.personalassistant.PAApplication"), "f");
            Class<?> app = type(loader, ROOT + "business.list.bean.PickerListAppData");
            appConstructor = app.getConstructor(String.class, String.class, String.class, int.class,
                    String.class, String.class, int.class, String.class, Drawable.class, int.class);
            appPackage = field(app, "appPackage"); appCount = field(app, "appWidgetAmount");
            Class<?> detail = type(loader, DETAIL + "PickerDetailResponse");
            Class<?> widget = type(loader, DETAIL + "PickerDetailResponseWidget");
            Class<?> tip = type(loader, DETAIL + "PickerDetailTipStr");
            widgetConstructor = widget.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, int.class, int.class, int.class, List.class,
                    int.class, boolean.class, android.os.Bundle.class);
            tipConstructor = tip.getConstructor(String.class, String.class);
            detailConstructor = detail.getConstructor(String.class, String.class, int.class,
                    int.class, int.class, String.class, String.class, String.class, String.class,
                    int.class, String.class, String.class, String.class, String.class, String.class,
                    tip, type(loader, DETAIL + "PickerDetailSupperAppInfo"), widget,
                    type(loader, DETAIL + "PickerDetailResponseMaml"), Long.class,
                    type(loader, "miui.drm.DrmManager$DrmResult"));
            wrapperType = type(loader, DETAIL + "PickerDetailResponseWrapper");
            localModels = new LocalWidgetCatalogModels(wrapperType, detail, tip);
            responseList = field(wrapperType, "pickerDetailResponses"); responseCode = field(wrapperType, "responseCode");
            wrapperFields = wrapperType.getDeclaredFields();
            for (Field f : wrapperFields) if (!Modifier.isStatic(f.getModifiers())) f.setAccessible(true);
            detailCode = field(detail, "implUniqueCode"); responseWidget = field(detail, "widgetImplInfo");
            responsePackage = field(detail, "appPackage"); widgetProvider = field(widget, "widgetProviderName");
            getTargetItem = type(loader, ROOT + "business.detail.utils.PickerDetailUtil")
                    .getDeclaredMethod("getAddTargetItemInfo", detail);
            Class<?> item = type(loader, "com.miui.personalassistant.widget.iteminfo.AppWidgetItemInfo");
            parseMetadata = item.getDeclaredMethod("parseWidgetMetaData");
            itemProvider = field(item, "providerInfo"); itemComponent = field(item, "provider");
            itemUser = item.getField("user"); itemMiui = item.getField("isMIUIWidget");
            // Validate metadata once; compatible releases are accepted regardless of versionCode.
            requireType(requestPage, int.class); requireType(application, Context.class);
            requireType(appPackage, String.class); requireType(appCount, int.class);
            requireType(responseList, List.class); requireType(responseCode, int.class);
            requireType(detailCode, String.class); requireType(responsePackage, String.class);
            requireType(widgetProvider, String.class); requireType(itemMiui, boolean.class);
            requireType(itemComponent, android.content.ComponentName.class);
            requireType(itemUser, android.os.UserHandle.class);
            convertApps.setAccessible(true);
            preferenceListener = (prefs, name) -> {
                if (name == null || name.startsWith(WidgetSettingsKt.WIDGET_SETTINGS_PREFIX)) readEnabled();
            };
        }

        void install() {
            handles.add(hookBuilder.apply(prepareIcons).setId("widget-catalog:prepare-icons").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled || failed.get()) return chain.proceed();
                int page = homePage(chain.getArg(1));
                if (page < 0) return chain.proceed();
                // Preserve raw cloud size/end-of-page semantics; expanded rows are consumed only
                // by the stock flat-card converter, after the paging decision has already run.
                LocalWidgetCatalogPage rows = new LocalWidgetCatalogPage((List<?>) chain.getArg(0), page == 0, generation.get());
                return chain.proceed(new Object[]{rows, chain.getArg(1)});
            }));
            handles.add(hookBuilder.apply(iconBody).setId("widget-catalog:icon-body").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof LocalWidgetCatalogPage && enabled && !failed.get()
                        && Looper.myLooper() != Looper.getMainLooper()) {
                    LocalWidgetCatalogPage rows = (LocalWidgetCatalogPage) result;
                    if (rows.epoch == generation.get()) {
                        try { expandRows(rows); } catch (Throwable error) { fail(error); }
                    }
                }
                return result;
            }));
            handles.add(hookBuilder.apply(convertApps).setId("widget-catalog:convert-apps").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object input = chain.getArg(0);
                if (input instanceof LocalWidgetCatalogPage) {
                    LocalWidgetCatalogPage rows = (LocalWidgetCatalogPage) input;
                    if (enabled && rows.epoch == generation.get() && rows.expanded != null) {
                        Object result = chain.proceed(new Object[]{rows.expanded});
                        Snapshot local = snapshot;
                        if (rows.first && local != null && !local.apps.isEmpty()
                                && deliveryReported.compareAndSet(false, true)) {
                            android.util.Log.i("HyperChangerWidgets", "Local widget catalogue delivered: rows=" + rows.expanded.size());
                            // Delivered through the stock flat-card converter.
                        }
                        return result;
                    }
                }
                return chain.proceed();
            }));
            // Cloud failure can occur before the stock background icon stage. Only the home
            // request gets a local fallback; cancellation and other list screens stay stock.
            handles.add(hookBuilder.apply(listLoad).setId("widget-catalog:load-list").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object target = chain.getArg(2);
                if (!enabled || failed.get() || !homeRequest.isInstance(target)) return chain.proceed();
                boolean first = (int) chain.getArg(0) == 0;
                int epoch = generation.get();
                Object proxy = Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation}, new ForwardingContinuation(target, (self, method, args) -> {
                    if (!"resumeWith".equals(method.getName())) return invoke(method, target, args);
                    Object value = args[0];
                    if (!resultFailure.isInstance(value) || cancelled(value)) return invoke(resume, target, args);
                    worker.execute(() -> {
                        Object result = value;
                        try {
                            LocalWidgetCatalogPage rows = new LocalWidgetCatalogPage(Collections.emptyList(), first, epoch);
                            expandRows(rows);
                            if (!rows.isEmpty()) result = rows;
                        } catch (Throwable error) { fail(error); }
                        deliver(target, enabled && epoch == generation.get() ? result : value);
                    });
                    return null;
                }));
                return chain.proceed(new Object[]{chain.getArg(0), chain.getArg(1), proxy});
            }));
            handles.add(hookBuilder.apply(detailLoad).setId("widget-catalog:load-details").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Snapshot data = snapshot;
                String pkg = (String) chain.getArg(1);
                // Only application overviews: never replace an individual paid/MAML product.
                if (!enabled || failed.get() || data == null || !data.apps.containsKey(pkg)
                        || detailState.isInstance(chain.getArg(9)) || !empty(chain.getArg(4))
                        || !empty(chain.getArg(5)) || !empty(chain.getArg(7))) return chain.proceed();
                Object target = chain.getArg(9);
                int epoch = generation.get();
                AtomicBoolean delivered = new AtomicBoolean();
                Object proxy = Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation}, new ForwardingContinuation(target, (self, method, args) -> {
                    if (!"resumeWith".equals(method.getName())) return invoke(method, target, args);
                    if (!detailFlow.supports(args[0])) return invoke(method, target, args);
                    mergeAndDeliver(pkg, target, args[0], epoch, delivered);
                    return null;
                }));
                Object[] args = new Object[10];
                for (int i = 0; i < args.length; ++i) args[i] = chain.getArg(i);
                args[9] = proxy;
                Object result = chain.proceed(args);
                if (result == suspended) return result;
                if (!detailFlow.supports(result)) return result;
                mergeAndDeliver(pkg, target, result, epoch, delivered);
                return suspended;
            }));
            handles.add(hookBuilder.apply(parseMetadata).setId("widget-catalog:metadata").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (enabled) {
                    Snapshot data = snapshot;
                    Object component = itemComponent.get(chain.getThisObject());
                    if (data != null && component instanceof android.content.ComponentName
                            && data.providers.containsKey(((android.content.ComponentName) component).flattenToString()))
                        itemMiui.setBoolean(chain.getThisObject(), true);
                }
                return result;
            }));
            handles.add(hookBuilder.apply(getTargetItem).setId("widget-catalog:target-item").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object source = chain.getArg(0);
                if (!enabled || !LocalWidgetCatalogPolicy.local((String) detailCode.get(source))) return chain.proceed();
                Object result = chain.proceed();
                Snapshot data = snapshot;
                AppWidgetProviderInfo info = data == null ? null : data.providers.get(
                        ((String) detailCode.get(source)).substring(LocalWidgetCatalogPolicy.PREFIX.length()));
                if (result != null && info != null && itemProvider.getDeclaringClass().isInstance(result)) {
                    itemProvider.set(result, info); itemUser.set(result, info.getProfile()); itemMiui.setBoolean(result, true);
                }
                return result;
            }));
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            listening = true;
            readEnabled();
        }
        void rollback() {
            enabled = false;
            failed.set(true);
            generation.incrementAndGet();
            snapshot = null;
            if (listening) {
                preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
                listening = false;
            }
            for (int i = handles.size() - 1; i >= 0; --i) {
                try { handles.get(i).unhook(); }
                catch (Throwable error) { android.util.Log.w("HyperChangerWidgets", "Catalogue rollback failed", error); }
            }
            handles.clear();
            worker.shutdownNow();
        }
        int homePage(Object frame) throws Throwable {
            for (int depth = 0; frame != null && depth < 6; ++depth) {
                if (homeRequest.isInstance(frame)) return requestPage.getInt(frame);
                if (Proxy.isProxyClass(frame.getClass())) {
                    InvocationHandler handler = Proxy.getInvocationHandler(frame);
                    if (handler instanceof ForwardingContinuation) {
                        frame = ((ForwardingContinuation) handler).target;
                        continue;
                    }
                }
                if (!baseContinuation.isInstance(frame)) return -1;
                frame = completion.invoke(frame);
            }
            return -1;
        }
        boolean cancelled(Object result) throws IllegalAccessException {
            return resultFailure.isInstance(result) && failureCause.get(result) instanceof java.util.concurrent.CancellationException;
        }
        void deliver(Object target, Object result) {
            try {
                Object job = contextElement.invoke(continuationContext.invoke(target), jobKey);
                if (job != null && !Boolean.TRUE.equals(jobActive.invoke(job))) {
                    // A cancelled caller must still unwind its finally blocks. Dropping the
                    // continuation here would strand its coroutine in a suspended state.
                    result = failureConstructor.newInstance(new java.util.concurrent.CancellationException("Local widget request cancelled"));
                }
                // Resume using the target's own dispatcher instead of assuming the main thread.
                Object destination = intercepted.getDeclaringClass().isInstance(target) ? intercepted.invoke(target) : target;
                resume.invoke(destination, result);
            } catch (Throwable error) { fail(error); }
        }
        void mergeAndDeliver(String pkg, Object target, Object original, int epoch, AtomicBoolean once) {
            if (!once.compareAndSet(false, true)) return;
            worker.execute(() -> {
                Object result = original;
                try {
                    // getPickerData returns Flow<Wrapper>, including its null/error cases.
                    // Rebuild only the verified native flowOf implementation; collect/emit,
                    // suspension and downstream cancellation remain entirely target-owned.
                    if (enabled && generation.get() == epoch)
                        result = detailFlow.map(original, value -> value == null || wrapperType.isInstance(value)
                                ? mergeDetails(pkg, value) : value);
                } catch (Throwable error) { fail(error); }
                deliver(target, enabled && generation.get() == epoch ? result : original);
            });
        }
        @SuppressWarnings("unchecked")
        Object mergeDetails(String pkg, Object original) throws Throwable {
            Object local = details(pkg);
            List<Object> added = (List<Object>) responseList.get(local);
            if (added.isEmpty()) return original;
            if (!wrapperType.isInstance(original) || responseCode.getInt(original) != 0) return local;
            List<?> remote = (List<?>) responseList.get(original);
            if (remote == null || remote.isEmpty()) return local;
            LinkedHashSet<String> providers = new LinkedHashSet<>();
            ArrayList<Object> merged = new ArrayList<>(remote);
            for (Object row : remote) {
                Object widget = responseWidget.get(row);
                if (widget != null) providers.add(LocalWidgetCatalogPolicy.providerKey((String) responsePackage.get(row), (String) widgetProvider.get(widget)));
            }
            for (Object row : added) {
                Object widget = responseWidget.get(row);
                if (providers.add(LocalWidgetCatalogPolicy.providerKey((String) responsePackage.get(row), (String) widgetProvider.get(widget)))) merged.add(row);
            }
            if (merged.size() == remote.size()) return original;
            for (Field field : wrapperFields) if (!Modifier.isStatic(field.getModifiers())) field.set(local, field.get(original));
            responseList.set(local, merged);
            return local;
        }
        void expandRows(LocalWidgetCatalogPage rows) throws Throwable {
            if (!enabled || rows.epoch != generation.get()) return;
            Snapshot data = load(); Context app = context.get();
            if (data == null || app == null) return;
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
            for (Object row : rows) {
                String pkg = (String) appPackage.get(row);
                if (rows.first || !data.apps.containsKey(pkg)) merged.putIfAbsent(pkg, row);
            }
            if (rows.first) for (Map.Entry<String, List<AppWidgetProviderInfo>> entry : data.apps.entrySet()) {
                String pkg = entry.getKey();
                if (merged.containsKey(pkg)) continue;
                try {
                    android.content.pm.ApplicationInfo info = app.getPackageManager().getApplicationInfo(pkg, 0);
                    String name = app.getPackageManager().getApplicationLabel(info).toString();
                    merged.put(pkg, appConstructor.newInstance("app", pkg, name, 0, "", "", entry.getValue().size(),
                            name, app.getPackageManager().getApplicationIcon(info), 0));
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* uninstalled during load */ }
            }
            rows.expand(new ArrayList<>(merged.values()));
        }
        void readEnabled() {
            boolean next = preferences.getBoolean(WidgetSettingsKt.KEY_WIDGET_WHITELIST, false)
                    || preferences.getBoolean(WidgetSettingsKt.KEY_WIDGET_STACK, false)
                    || preferences.getBoolean(WidgetSettingsKt.KEY_WIDGET_GLASS, false);
            if (enabled != next) { enabled = next; generation.incrementAndGet(); snapshot = null; }
        }
        synchronized Snapshot load() throws Throwable {
            Context app = context.get();
            if (app == null) {
                app = (Context) application.get(null);
                if (app != null) context = new WeakReference<>(app.getApplicationContext());
            }
            if (app == null || !enabled) return null;
            if (!registered) {

                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addDataScheme("package");
                app.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        generation.incrementAndGet(); snapshot = null;
                    }
                }, filter, Context.RECEIVER_NOT_EXPORTED);
                registered = true;
            }
            Snapshot current = snapshot;
            if (current != null) return current;
            int epoch = generation.get();
            Map<String, AppWidgetProviderInfo> providers = new LinkedHashMap<>();
            Map<String, List<AppWidgetProviderInfo>> apps = new java.util.TreeMap<>();
            for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(app).getInstalledProviders()) {
                // Current-profile catalogue only: never merge identical names across profiles.
                if (!Process.myUserHandle().equals(info.getProfile()) || info.provider == null) continue;
                String component = info.provider.flattenToString();
                if (providers.putIfAbsent(component, info) != null) continue;
                apps.computeIfAbsent(info.provider.getPackageName(), key -> new ArrayList<>()).add(info);
            }
            Snapshot next = new Snapshot(providers, apps);
            if (enabled && generation.get() == epoch) snapshot = next;
            return next;
        }
        Object details(String pkg) throws Throwable {
            Snapshot data = load(); Context app = context.get();
            if (data == null || app == null || !enabled) return emptyDetails(pkg);
            List<AppWidgetProviderInfo> providers = data.apps.getOrDefault(pkg, Collections.emptyList());
            String name;
            android.content.pm.ApplicationInfo appInfo;
            try {
                appInfo = app.getPackageManager().getApplicationInfo(pkg, 0);
                name = app.getPackageManager().getApplicationLabel(appInfo).toString();
            }
            catch (android.content.pm.PackageManager.NameNotFoundException gone) { return emptyDetails(pkg); }
            android.content.res.Resources resources = app.getResources();
            if (sizes == null || !sizes.configuration.equals(resources.getConfiguration()))
                sizes = new LocalWidgetCatalogSizes(resources, PACKAGE);
            List<Object> rows = new ArrayList<>();
            for (AppWidgetProviderInfo info : providers) {
                int style = sizes.select(info.targetCellWidth, info.targetCellHeight, info.minWidth, info.minHeight);
                if (style == 0) continue;
                int x = LocalWidgetCatalogSizes.spanX(style), y = LocalWidgetCatalogSizes.spanY(style);
                String preview = LocalWidgetCatalogPolicy.previewUri(pkg, info.previewImage, info.icon, appInfo.icon);
                Object widget = widgetConstructor.newInstance(info.provider.getClassName(),
                        info.loadLabel(app.getPackageManager()), "", preview, preview, rows.size(), x, y,
                        Collections.emptyList(), 1, false, null);
                Object detail = detailConstructor.newInstance(LocalWidgetCatalogPolicy.PREFIX + info.provider.flattenToString(),
                        "", 1, style, style, name, "", pkg, "", 0, "", "", "", "", "",
                        tipConstructor.newInstance("", ""), null, widget, null, Long.valueOf(0), null);
                localModels.prepareLocalDetail(detail);
                rows.add(detail);
            }
            return localModels.wrap(rows, pkg, name);
        }
        Object emptyDetails(String pkg) {
            try { return localModels.wrap(Collections.emptyList(), pkg, ""); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        void fail(Throwable error) {
            if (failed.compareAndSet(false, true)) {
                enabled = false;
                generation.incrementAndGet();
                snapshot = null;
                android.util.Log.w("HyperChangerWidgets", "Catalogue disabled; keeping original cloud flow", error);
            }
        }
    }
    private static final class ForwardingContinuation implements InvocationHandler {
        final Object target;
        final InvocationHandler delegate;
        ForwardingContinuation(Object target, InvocationHandler delegate) { this.target = target; this.delegate = delegate; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return delegate.invoke(proxy, method, args);
        }
    }
    private static final class Snapshot {
        final Map<String, AppWidgetProviderInfo> providers;
        final Map<String, List<AppWidgetProviderInfo>> apps;
        Snapshot(Map<String, AppWidgetProviderInfo> providers, Map<String, List<AppWidgetProviderInfo>> apps) {
            this.providers = Collections.unmodifiableMap(providers); this.apps = Collections.unmodifiableMap(apps);
        }
    }
    private static boolean empty(Object value) { return value == null || "".equals(value); }
    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void requireType(Field field, Class<?> expected) throws NoSuchFieldException {
        if (!expected.isAssignableFrom(field.getType()))
            throw new NoSuchFieldException(field.getDeclaringClass().getName() + "." + field.getName()
                    + ": expected " + expected.getName() + ", actual " + field.getType().getName());
    }
    private static Method exactMethod(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
        for (Method method : owner.getDeclaredMethods()) if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        throw new NoSuchMethodException(name);
    }
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
