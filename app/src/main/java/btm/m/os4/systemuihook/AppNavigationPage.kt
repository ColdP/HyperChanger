package btm.m.os4.systemuihook

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import io.github.libxposed.service.XposedService
import btm.m.os4.systemuihook.SeamlessCard as Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import btm.m.os4.systemuihook.InterfaceArrowPreference as ArrowPreference
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.NavigationAccentSettings
import btm.m.liquidglass.HyperDockedNavigationSettings
import btm.m.liquidglass.HyperFloatingAccentSettings
import btm.m.liquidglass.NavigationBadgeSettings
import btm.m.liquidglass.NavigationOffsetSettings
import btm.m.liquidglass.NavigationScaleSettings
import btm.m.liquidglass.NavigationCornerSettings
import btm.m.liquidglass.NavigationMaterialSettings
import btm.m.liquidglass.hook.WeChatAvatarRadiusSettings
import btm.m.liquidglass.rememberMomentumPredictiveBack
import btm.m.liquidglass.momentumBackTransform
import kotlin.math.roundToInt

private data class AppNavItem(val name: String, val packageName: String)

private data class AppNavMeta(val icon: ImageBitmap?, val versionName: String)

private data class RegisteredLaunchItem(
    val item: AppNavItem,
    val source: AppLaunchSource,
    val hitBounds: () -> Rect?,
)

private enum class NavigationAppListPage { DISABLED, UNINSTALLED }

// MIUIX defaults to .25; use 60% of the previous refresh distance.
internal const val NAVIGATION_REFRESH_THRESHOLD = .25f * .6f

private val appNavItems = listOf(
    AppNavItem("QQ", "com.tencent.mobileqq"),
    AppNavItem("Apple Music", "com.apple.android.music"),
    AppNavItem("微博", "com.sina.weibo"),
    AppNavItem("小红书", "com.xingin.xhs"),
    AppNavItem("小米商城", "com.xiaomi.shop"),
    AppNavItem("小米钱包", "com.mipay.wallet"),
    AppNavItem("小米运动健康", "com.mi.health"),
    AppNavItem("瑞幸咖啡", "com.lucky.luckyclient"),
    AppNavItem("Reddit", "com.reddit.frontpage"),
    AppNavItem("腾讯会议", "com.tencent.wemeet.app"),
    AppNavItem("抖省省", "com.ss.android.ugc.lifeservices"),
    AppNavItem("美团", "com.sankuai.meituan"),
    AppNavItem("美团外卖", "com.sankuai.meituan.takeoutnew"),
    AppNavItem("招商银行", "cmb.pb"),
    AppNavItem("微信", "com.tencent.mm"),
    AppNavItem("拼多多", "com.xunmeng.pinduoduo"),
    AppNavItem("Instagram", "com.instagram.android"),
    AppNavItem("Pinterest", "com.pinterest"),
    AppNavItem("X", "com.twitter.android"),
    AppNavItem("GitHub", "com.github.android"),
    AppNavItem("京东", "com.jingdong.app.mall"),
    AppNavItem("支付宝", "com.eg.android.AlipayGphone"),
)

@Composable
internal fun AppNavigationPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { AppNavigationSettingsStore(context) }
    val layoutStore = remember(context) { AppNavigationLayoutSettingsStore(context) }
    DisposableEffect(layoutStore) {
        val dispose = layoutStore.observe()
        onDispose { dispose() }
    }
    LaunchedEffect(service) { service?.let(layoutStore::syncRemote) }
    val gridMode = layoutStore.gridMode
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchQuery by remember { mutableStateOf("") }
    var appListPage by remember { mutableStateOf<NavigationAppListPage?>(null) }
    var lastAppListPage by remember { mutableStateOf(NavigationAppListPage.DISABLED) }
    var materialOpen by remember { mutableStateOf(false) }
    var materialPackage by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var installationRevision by remember { mutableIntStateOf(0) }
    val (installedApps, uninstalledApps) = remember(context, installationRevision) {
        appNavItems.partition { item ->
            runCatching { context.packageManager.getApplicationInfo(item.packageName, 0) }.isSuccess
        }
    }
    val appMetaMap = remember(context, installedApps, installationRevision) {
        installedApps.associate { item ->
            val info = runCatching { context.packageManager.getApplicationInfo(item.packageName, 0) }.getOrNull()
            val pkgInfo = runCatching { context.packageManager.getPackageInfo(item.packageName, 0) }.getOrNull()
            val icon = info?.loadIcon(context.packageManager)?.toBitmap(160, 160)?.asImageBitmap()
            item.packageName to AppNavMeta(icon, pkgInfo?.versionName ?: "?")
        }
    }
    // Membership changes only on entry/refresh; preference notifications still update switches.
    val (enabledApps, disabledApps) = remember(installedApps, store, installationRevision) {
        installedApps.partition { item ->
            ScopedSettings.getBoolean(store.preferences, item.packageName, ScopedSettings.KEY_ENABLED, true)
        }
    }
    val query = searchQuery.trim()
    val searchDocuments = enabledApps.map { item ->
        SettingsSearchDocument(item, tr(item.name, item.name), "", listOf(item.packageName), item.name)
    }
    val matches = rememberSettingsSearch(searchDocuments, query)
    // Search changes membership only; the established application order remains stable.
    val matchedPackages = matches.orEmpty().mapTo(HashSet()) { it.packageName }
    val visibleApps = enabledApps.filter { it.packageName in matchedPackages }
    val refreshApps: () -> Unit = {
        revision++
        installationRevision++
    }

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val sessions = remember { mutableStateListOf<IosAppLaunchSession>() }
    val sessionScope = rememberCoroutineScope()
    var nextSessionId by remember { mutableLongStateOf(1L) }
    val activeSession = sessions.lastOrNull { !it.closing }
    val mainSources = remember { mutableStateMapOf<String, RegisteredLaunchItem>() }
    val disabledSources = remember { mutableStateMapOf<String, RegisteredLaunchItem>() }

    var hadOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(appListPage, sessions.isEmpty(), activeSession != null, materialOpen) {
        val overlayOpen = appListPage != null || !sessions.isEmpty() || materialOpen
        if (overlayOpen) {
            hadOverlay = true
        } else if (hadOverlay) {
            hadOverlay = false
            refreshApps()
        }
        if (appListPage != null || activeSession != null || materialOpen) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(store, lifecycleOwner) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || isAppNavigationPreference(key)) revision++
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                revision++
                installationRevision++
            }
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val dismissSession: (IosAppLaunchSession) -> Unit = { target ->
        if (!target.closing) {
            val velocity = if (target.gesturing) target.releaseVelocity else 0f
            target.close(sessionScope, initialVelocity = velocity) { finished ->
                sessions.remove(finished)
            }
        }
    }

    val launchApp: (AppNavItem, AppLaunchOrigin, AppLaunchSource) -> Unit = { item, origin, source ->
        focusManager.clearFocus()
        keyboard?.hide()
        val existing = sessions.lastOrNull { it.packageName == item.packageName && it.origin == origin }
        if (existing != null) {
            if (existing.closing) {
                existing.source = source
                sessions.remove(existing)
                sessions.add(existing)
                existing.launch(sessionScope)
            }
        } else {
            activeSession?.let(dismissSession)
            val w = rootSize.width.toFloat().coerceAtLeast(1f)
            val h = rootSize.height.toFloat().coerceAtLeast(1f)
            val fallback = source.unscaledBounds() ?: Rect(w * 0.42f, h * 0.45f, w * 0.58f, h * 0.55f)
            val created = IosAppLaunchSession(
                id = nextSessionId++,
                packageName = item.packageName,
                origin = origin,
                source = source,
                fallbackUnscaledBounds = fallback,
            )
            sessions.add(created)
            created.launch(sessionScope)
        }
    }

    IosAppLaunchPredictiveBack(
        activeSession = activeSession,
        enabled = activeSession != null && !materialOpen,
        onCommitDismiss = dismissSession,
    )

    val materialBack = rememberMomentumPredictiveBack(enabled = materialOpen, onBack = { materialOpen = false })
    val materialExitDirection = remember(materialOpen) { materialBack.exitDirection }
    LaunchedEffect(materialOpen) { if (materialOpen) materialBack.reset() }
    val materialCoveredBlur by animateFloatAsState(if (materialOpen) 12f else 0f, tween(252), label = "navigationMaterialCoveredBlur")

    val appListBack = rememberMomentumPredictiveBack(
        enabled = appListPage != null && activeSession == null && sessions.isEmpty() && !materialOpen,
        onBack = { appListPage = null },
    )
    val appListExitDirection = remember(appListPage) { appListBack.exitDirection }
    LaunchedEffect(appListPage) { if (appListPage != null) appListBack.reset() }

    val mainLaunchProgress = sessions
        .filter { it.origin == AppLaunchOrigin.MAIN }
        .maxOfOrNull { it.progress.value.coerceIn(0f, 1f) } ?: 0f
    val disabledLaunchProgress = sessions
        .filter { it.origin == AppLaunchOrigin.DISABLED }
        .maxOfOrNull { it.progress.value.coerceIn(0f, 1f) } ?: 0f

    val secondaryOverviewBlur by animateFloatAsState(
        targetValue = if (appListPage != null || materialOpen) 12f else 0f,
        animationSpec = tween(252),
        label = "appNavigationSecondaryBlur",
    )
    val overviewBlur = maxOf(secondaryOverviewBlur, 18f * mainLaunchProgress)
    val overviewScale = 1f - 0.09f * mainLaunchProgress
    val overviewCorner = (26f * mainLaunchProgress).dp
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val overviewDimAlpha = (if (isDark) 0.36f else 0.14f) * mainLaunchProgress

    val secondaryDisabledBlur by animateFloatAsState(
        targetValue = if (materialOpen) 12f else 0f,
        animationSpec = tween(252),
        label = "navigationAppListCoveredBlur",
    )
    val disabledPageBlur = maxOf(secondaryDisabledBlur, 18f * disabledLaunchProgress)
    val disabledPageScale = 1f - 0.09f * disabledLaunchProgress
    val disabledPageCorner = (26f * disabledLaunchProgress).dp
    val disabledDimAlpha = (if (isDark) 0.36f else 0.14f) * disabledLaunchProgress

    val density = LocalDensity.current
    val hitSlopPx = with(density) { 8.dp.toPx() }
    val rootBackdropColor = if (isDark) ComposeColor.Black else ComposeColor(0xFFE8E8ED)

    Box(
        Modifier
            .fillMaxSize()
            .background(if (mainLaunchProgress > 0.005f) rootBackdropColor else MiuixTheme.colorScheme.surface)
            .onSizeChanged { rootSize = it }
            .onGloballyPositioned { rootCoords = it },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = overviewScale
                    scaleY = overviewScale
                }
                .then(if (overviewCorner > 0.5.dp) Modifier.clip(RoundedCornerShape(overviewCorner)) else Modifier)
                .then(if (overviewBlur > 0.05f) Modifier.blur(overviewBlur.dp) else Modifier),
        ) {
            AppPage(
                tr("应用底部导航", "应用底部导航"), back,
                restartScopes = appNavItems.mapNotNull { item ->
                    ScopeApplication.entries.firstOrNull { it.packageName == item.packageName }
                }.toSet(),
                restartEnabled = service != null,
                restartScrollableList = true,
                floatingToolbar = {
                    NavigationAppSearchField(searchQuery, onQueryChange = { searchQuery = it })
                },
            ) { padding, scroll ->
                // Keep the captured viewport full-size. IME space belongs to the scrollable
                // content padding, not a smaller opaque list surface above the keyboard.
                val listPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = maxOf(
                        padding.calculateBottomPadding(),
                        WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
                    ),
                )
                PullToRefresh(
                    isRefreshing = false,
                    onRefresh = refreshApps,
                    modifier = Modifier.fillMaxSize(),
                    pullToRefreshState = rememberPullToRefreshState(refreshThreshold = NAVIGATION_REFRESH_THRESHOLD),
                    contentPadding = padding,
                    topAppBarScrollBehavior = scroll,
                    color = MiuixTheme.colorScheme.primary,
                    refreshTexts = navigationRefreshTexts(),
                ) {
                    AppList(listPadding, scroll, 112) {
                        if (!gridMode) {
                            item {
                                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                                    AppNavigationOffsetPreferences(store.preferences, service)
                                    SharedViewArrowPreference(
                                        title = tr("navMaterialTitle", "底部导航栏材质参数调整"),
                                        onClick = { materialPackage = null; materialOpen = true },
                                    )
                                }
                            }
                            item { SmallTitle(tr("支持的应用", "支持的应用"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp)) }
                        }
                        if (query.isNotEmpty() && visibleApps.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                                    BasicComponent(title = if (matches == null) tr("category_search_loading", "正在搜索…")
                                        else tr("appNavigationSearchEmpty", "没有找到匹配的应用"))
                                }
                            }
                        } else if (visibleApps.isNotEmpty()) {
                            item(key = "supported_apps_section") {
                                AnimatedContent(
                                    targetState = gridMode,
                                    transitionSpec = {
                                        (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                                            scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.96f)) togetherWith
                                            (fadeOut(tween(150)) + scaleOut(tween(180), targetScale = 0.96f))
                                    },
                                    label = "appNavigationViewMode",
                                ) { isGrid ->
                                    if (isGrid) {
                                        val rows = visibleApps.chunked(4)
                                        Column(
                                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                                            verticalArrangement = Arrangement.spacedBy(18.dp),
                                        ) {
                                            rows.forEach { rowItems ->
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    for (col in 0 until 4) {
                                                        val item = rowItems.getOrNull(col)
                                                        if (item != null) {
                                                            val sessionForItem = sessions.lastOrNull {
                                                                it.origin == AppLaunchOrigin.MAIN && it.packageName == item.packageName
                                                            }
                                                            val launchProgress = sessionForItem?.progress?.value?.coerceIn(0f, 1f) ?: 0f
                                                            AppNavigationGridItem(
                                                                item = item,
                                                                meta = appMetaMap[item.packageName],
                                                                modifier = Modifier.weight(1f),
                                                                rootCoordsProvider = { rootCoords },
                                                                backgroundScaleProvider = { overviewScale },
                                                                hideIconVisual = launchProgress > 0.005f,
                                                                labelAlpha = 1f - (launchProgress * 3.2f).coerceIn(0f, 1f),
                                                                onRegister = { registered ->
                                                                    mainSources[item.packageName] = registered
                                                                },
                                                                onUnregister = {
                                                                    mainSources.remove(item.packageName)
                                                                },
                                                                onOpen = { source ->
                                                                    launchApp(item, AppLaunchOrigin.MAIN, source)
                                                                },
                                                            )
                                                        } else {
                                                            Spacer(Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Column(
                                            Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            visibleApps.forEach { item ->
                                                key(item.packageName) {
                                                    val sessionForItem = sessions.lastOrNull {
                                                        it.origin == AppLaunchOrigin.MAIN && it.packageName == item.packageName
                                                    }
                                                    val hideCard = (sessionForItem?.progress?.value ?: 0f) > 0.005f
                                                    AppNavigationCard(
                                                        item = item,
                                                        meta = appMetaMap[item.packageName],
                                                        store = store,
                                                        service = service,
                                                        revision = revision,
                                                        rootCoordsProvider = { rootCoords },
                                                        backgroundScaleProvider = { overviewScale },
                                                        hideVisual = hideCard,
                                                        onRegister = { registered ->
                                                            mainSources[item.packageName] = registered
                                                        },
                                                        onUnregister = {
                                                            mainSources.remove(item.packageName)
                                                        },
                                                        onOpen = { source ->
                                                            launchApp(item, AppLaunchOrigin.MAIN, source)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (gridMode) {
                            item {
                                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                                    AppNavigationOffsetPreferences(store.preferences, service)
                                    SharedViewArrowPreference(
                                        title = tr("navMaterialTitle", "底部导航栏材质参数调整"),
                                        onClick = { materialPackage = null; materialOpen = true },
                                    )
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                                SharedViewArrowPreference(
                                    title = tr("appNavigationDisabledApps", "未启用的应用"),
                                    onClick = {
                                        lastAppListPage = NavigationAppListPage.DISABLED
                                        appListPage = NavigationAppListPage.DISABLED
                                    },
                                )
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                                SharedViewArrowPreference(
                                    title = tr("appNavigationUninstalledApps", "未安装的应用"),
                                    onClick = {
                                        lastAppListPage = NavigationAppListPage.UNINSTALLED
                                        appListPage = NavigationAppListPage.UNINSTALLED
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (overviewDimAlpha > 0.002f) {
                Box(Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = overviewDimAlpha)))
            }
        }
        AnimatedVisibility(
            visible = appListPage != null,
            enter = slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } +
                fadeIn(tween(189)) + scaleIn(tween(252, easing = FastOutSlowInEasing), initialScale = .985f),
            exit = slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { appListExitDirection * it / 3 } +
                fadeOut(tween(162)) + scaleOut(tween(207, easing = FastOutSlowInEasing), targetScale = .975f),
        ) {
            val transitionBlur by transition.animateFloat(
                transitionSpec = { tween(207, easing = FastOutSlowInEasing) }, label = "navigationAppListTransitionBlur",
            ) { if (it == EnterExitState.Visible) 0f else 12f }
            val totalBlur = maxOf(transitionBlur, disabledPageBlur)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = disabledPageScale
                        scaleY = disabledPageScale
                    }
                    .then(if (disabledPageCorner > 0.5.dp) Modifier.clip(RoundedCornerShape(disabledPageCorner)) else Modifier)
                    .then(if (totalBlur > 0.05f) Modifier.blur(totalBlur.dp) else Modifier)
                    .momentumBackTransform(appListBack),
            ) {
                when (lastAppListPage) {
                    NavigationAppListPage.UNINSTALLED -> UninstalledNavigationAppsPage(uninstalledApps) { appListPage = null }
                    NavigationAppListPage.DISABLED -> DisabledNavigationAppsPage(
                        apps = disabledApps,
                        metaMap = appMetaMap,
                        store = store,
                        service = service,
                        revision = revision,
                        rootCoordsProvider = { rootCoords },
                        backgroundScaleProvider = { disabledPageScale },
                        sessions = sessions,
                        onRegister = { pkg, registered -> disabledSources[pkg] = registered },
                        onUnregister = { pkg -> disabledSources.remove(pkg) },
                        onRefresh = refreshApps,
                        onOpen = { item, source -> launchApp(item, AppLaunchOrigin.DISABLED, source) },
                        back = { appListPage = null },
                    )
                }
                if (disabledDimAlpha > 0.002f) {
                    Box(Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = disabledDimAlpha)))
                }
            }
        }
        sessions.forEach { session ->
            key(session.id) {
                val item = remember(session.packageName) {
                    appNavItems.firstOrNull { it.packageName == session.packageName }
                        ?: AppNavItem(session.packageName, session.packageName)
                }
                IosAppLaunchOverlay(
                    session = session,
                    rootSize = rootSize,
                    coveredBlurDp = materialCoveredBlur,
                ) {
                    AppNavigationSettings(
                        appItem = item,
                        store = store,
                        service = service,
                        onChanged = { revision++ },
                        openMaterial = {
                            materialPackage = item.packageName
                            materialOpen = true
                        },
                        back = { dismissSession(session) },
                    )
                }
            }
        }
        // While all open sessions are flying back to their icons, let taps on any grid/list item
        // interrupt or launch in parallel immediately without waiting for the flight to finish.
        if (activeSession == null && sessions.isNotEmpty() && !materialOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(appListPage) {
                        detectTapGestures { offset ->
                            val isDisabled = appListPage == NavigationAppListPage.DISABLED
                            val targetMap = if (isDisabled) disabledSources else mainSources
                            val origin = if (isDisabled) AppLaunchOrigin.DISABLED else AppLaunchOrigin.MAIN
                            val center = Offset(rootSize.width * 0.5f, rootSize.height * 0.5f)
                            val hit = targetMap.values.firstOrNull { entry ->
                                val bounds = entry.hitBounds() ?: return@firstOrNull false
                                val scaled = scaleRectAroundCenter(bounds, center, entry.source.backgroundScale())
                                val expanded = Rect(
                                    scaled.left - hitSlopPx,
                                    scaled.top - hitSlopPx,
                                    scaled.right + hitSlopPx,
                                    scaled.bottom + hitSlopPx,
                                )
                                expanded.contains(offset)
                            }
                            if (hit != null) launchApp(hit.item, origin, hit.source)
                        }
                    },
            )
        }
        AnimatedVisibility(
            visible = materialOpen,
            enter = slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(189)),
            exit = slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { materialExitDirection * it / 3 } + fadeOut(tween(162)),
        ) {
            Box(Modifier.fillMaxSize().momentumBackTransform(materialBack)) {
                NavigationMaterialPage(materialPackage) { materialOpen = false }
            }
        }
    }
}

internal fun navigationRefreshTexts() = listOf(
    tr("appNavigationPullToRefresh", "下拉刷新"),
    tr("appNavigationReleaseToRefresh", "松开刷新"),
    tr("appNavigationRefreshing", "正在刷新…"),
    tr("appNavigationRefreshed", "刷新完成"),
)

@Composable
private fun DisabledNavigationAppsPage(
    apps: List<AppNavItem>,
    metaMap: Map<String, AppNavMeta>,
    store: AppNavigationSettingsStore,
    service: XposedService?,
    revision: Int,
    rootCoordsProvider: () -> LayoutCoordinates?,
    backgroundScaleProvider: () -> Float,
    sessions: List<IosAppLaunchSession>,
    onRegister: (String, RegisteredLaunchItem) -> Unit,
    onUnregister: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (AppNavItem, AppLaunchSource) -> Unit,
    back: () -> Unit,
) {
    AppPage(tr("appNavigationDisabledApps", "未启用的应用"), back) { padding, scroll ->
        PullToRefresh(
            isRefreshing = false,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            pullToRefreshState = rememberPullToRefreshState(refreshThreshold = NAVIGATION_REFRESH_THRESHOLD),
            contentPadding = padding,
            topAppBarScrollBehavior = scroll,
            color = MiuixTheme.colorScheme.primary,
            refreshTexts = navigationRefreshTexts(),
        ) {
            AppList(padding, scroll, 28) {
                items(apps, key = { it.packageName }) { item ->
                    val sessionForItem = sessions.lastOrNull {
                        it.origin == AppLaunchOrigin.DISABLED && it.packageName == item.packageName
                    }
                    val hideCard = (sessionForItem?.progress?.value ?: 0f) > 0.005f
                    AppNavigationCard(
                        item = item,
                        meta = metaMap[item.packageName],
                        store = store,
                        service = service,
                        revision = revision,
                        rootCoordsProvider = rootCoordsProvider,
                        backgroundScaleProvider = backgroundScaleProvider,
                        hideVisual = hideCard,
                        onRegister = { onRegister(item.packageName, it) },
                        onUnregister = { onUnregister(item.packageName) },
                        onOpen = { source -> onOpen(item, source) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UninstalledNavigationAppsPage(apps: List<AppNavItem>, back: () -> Unit) {
    AppPage(tr("appNavigationUninstalledApps", "未安装的应用"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            items(apps, key = { it.packageName }) { item ->
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    BasicComponent(
                        title = tr(item.name, item.name),
                        summary = item.packageName,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigationGridItem(
    item: AppNavItem,
    meta: AppNavMeta?,
    modifier: Modifier = Modifier,
    rootCoordsProvider: () -> LayoutCoordinates?,
    backgroundScaleProvider: () -> Float,
    hideIconVisual: Boolean,
    labelAlpha: Float,
    onRegister: (RegisteredLaunchItem) -> Unit,
    onUnregister: () -> Unit,
    onOpen: (AppLaunchSource) -> Unit,
) {
    val density = LocalDensity.current
    val squircle = isSquircleEnabled()
    val iconShape = remember { RoundedRectangle(14.dp, RoundedCornerStyle.Continuous) }
    val iconRadiusPx = with(density) { 14.dp.toPx() }
    val layer = rememberGraphicsLayer()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "appGridIconPress",
    )
    var iconCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cellCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun unscaledBoundsOf(coords: LayoutCoordinates?): Rect? {
        val root = rootCoordsProvider()?.takeIf { it.isAttached }?.boundsInWindow() ?: return null
        val target = coords?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false) ?: return null
        if (target.width <= 0f || target.height <= 0f) return null
        val local = target.translate(Offset(-root.left, -root.top))
        val center = Offset(root.width * 0.5f, root.height * 0.5f)
        return unscaleRectAroundCenter(local, center, backgroundScaleProvider())
    }

    val source = remember(layer, iconRadiusPx, squircle) {
        AppLaunchSource(
            unscaledBounds = { unscaledBoundsOf(iconCoords) },
            backgroundScale = backgroundScaleProvider,
            layer = layer,
            cornerRadiusPx = iconRadiusPx,
            isGridIcon = true,
            isSquircle = squircle,
        )
    }

    DisposableEffect(item.packageName, source) {
        onRegister(RegisteredLaunchItem(item, source) { unscaledBoundsOf(cellCoords) })
        onDispose { onUnregister() }
    }

    val borderColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val fallbackBg = MiuixTheme.colorScheme.surfaceContainer

    Column(
        modifier = modifier
            .onGloballyPositioned { cellCoords = it }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onOpen(source) },
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .onGloballyPositioned { iconCoords = it }
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .drawWithContent {
                    layer.record {
                        this@drawWithContent.drawContent()
                    }
                    if (!hideIconVisual) {
                        drawLayer(layer)
                    }
                }
                .clip(iconShape)
                .background(fallbackBg)
                .border(0.5.dp, borderColor, iconShape),
            contentAlignment = Alignment.Center,
        ) {
            meta?.icon?.let {
                Image(
                    bitmap = it,
                    contentDescription = tr("应用图标", "应用图标"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = tr(item.name, item.name),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}

@Composable
private fun AppNavigationCard(
    item: AppNavItem,
    meta: AppNavMeta?,
    store: AppNavigationSettingsStore,
    service: XposedService?,
    revision: Int,
    rootCoordsProvider: () -> LayoutCoordinates?,
    backgroundScaleProvider: () -> Float,
    hideVisual: Boolean,
    onRegister: (RegisteredLaunchItem) -> Unit,
    onUnregister: () -> Unit,
    onOpen: (AppLaunchSource) -> Unit,
) {
    val prefs = store.preferences
    val density = LocalDensity.current
    val squircle = isSquircleEnabled()
    val cornerPx = with(density) { 22.5.dp.toPx() }
    val layer = rememberGraphicsLayer()
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var enabled by remember(item.packageName, revision, service) {
        mutableStateOf(ScopedSettings.getBoolean(prefs, item.packageName, ScopedSettings.KEY_ENABLED, true))
    }

    fun unscaledBoundsOf(coords: LayoutCoordinates?): Rect? {
        val root = rootCoordsProvider()?.takeIf { it.isAttached }?.boundsInWindow() ?: return null
        val target = coords?.takeIf { it.isAttached }?.boundsInWindow(clipBounds = false) ?: return null
        if (target.width <= 0f || target.height <= 0f) return null
        val local = target.translate(Offset(-root.left, -root.top))
        val center = Offset(root.width * 0.5f, root.height * 0.5f)
        return unscaleRectAroundCenter(local, center, backgroundScaleProvider())
    }

    val source = remember(layer, cornerPx, squircle) {
        AppLaunchSource(
            unscaledBounds = { unscaledBoundsOf(cardCoords) },
            backgroundScale = backgroundScaleProvider,
            layer = layer,
            cornerRadiusPx = cornerPx,
            isGridIcon = false,
            isSquircle = squircle,
        )
    }

    DisposableEffect(item.packageName, source) {
        onRegister(RegisteredLaunchItem(item, source) { unscaledBoundsOf(cardCoords) })
        onDispose { onUnregister() }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { cardCoords = it }
            .drawWithContent {
                layer.record {
                    this@drawWithContent.drawContent()
                }
                if (!hideVisual) {
                    drawLayer(layer)
                }
            },
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.5.dp))
                .clickable { onOpen(source) },
            cornerRadius = 22.5.dp,
            insideMargin = PaddingValues(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                meta?.icon?.let {
                    Image(
                        it,
                        tr("应用图标", "应用图标"),
                        Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(item.name, item.name), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.packageName, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta?.versionName ?: "?", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(28.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .35f)))
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        store.edit(service) {
                            putBoolean(
                                ScopedSettings.key(item.packageName, ScopedSettings.KEY_ENABLED),
                                it
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppNavigationSettings(appItem: AppNavItem, store: AppNavigationSettingsStore, service: XposedService?, onChanged: () -> Unit, openMaterial: () -> Unit, back: () -> Unit) {
    val prefs = store.preferences
    val enabledKey = ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_ENABLED)
    var enabled by remember(appItem.packageName, service) { mutableStateOf(ScopedSettings.getBoolean(prefs, appItem.packageName, ScopedSettings.KEY_ENABLED, true)) }
    var styleIndex by remember(appItem.packageName, service) { mutableIntStateOf(NavigationStyle.fromPreference(ScopedSettings.getString(prefs, appItem.packageName, NavigationStyle.PREFERENCE_KEY, NavigationStyle.DEFAULT_VALUE)).ordinal) }
    var labelIndex by remember(appItem.packageName, service) { mutableIntStateOf(LabelMode.fromPreference(ScopedSettings.getLabelMode(prefs, appItem.packageName)).ordinal) }
    var colorIndex by remember(appItem.packageName, service) { mutableIntStateOf(AppColorMode.fromPreference(ScopedSettings.getString(prefs, appItem.packageName, ScopedSettings.KEY_COLOR_MODE, AppColorMode.DEFAULT_VALUE)).ordinal) }
    var accentColor by remember(appItem.packageName, service) { mutableIntStateOf(NavigationAccentSettings.getColor(prefs, appItem.packageName)) }
    var hyperAccentEnabled by remember(appItem.packageName, service) { mutableStateOf(HyperFloatingAccentSettings.isEnabled(prefs, appItem.packageName)) }
    var hyperDockedAccentEnabled by remember(appItem.packageName, service) { mutableStateOf(HyperDockedNavigationSettings.accentEnabled(prefs, appItem.packageName)) }
    var showBadges by remember(appItem.packageName, service) { mutableStateOf(NavigationBadgeSettings.getShowBadges(prefs, appItem.packageName)) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var followsMaterial by remember(appItem.packageName, service) { mutableStateOf(NavigationMaterialSettings.followsGlobal(prefs, appItem.packageName)) }
    val hyperFloating = NavigationStyle.entries.getOrNull(styleIndex) == NavigationStyle.HYPER_OS_FLOATING
    val hyperDocked = NavigationStyle.entries.getOrNull(styleIndex) == NavigationStyle.HYPER_OS
    val accentEnabled = (hyperDocked && hyperDockedAccentEnabled) || (hyperFloating && hyperAccentEnabled) || NavigationStyle.entries.getOrNull(styleIndex) in setOf(
        NavigationStyle.LIQUID_GLASS,
        NavigationStyle.HARMONY_OS_FLOATING,

    )
    var walletTabs by remember(appItem.packageName, service) { mutableStateOf(ScopedSettings.getWalletVisibleTabs(prefs)) }
    var avatarRadius by remember(appItem.packageName, service) { mutableFloatStateOf(WeChatAvatarRadiusSettings.getOffset(prefs, appItem.packageName).toFloat()) }
    AppPage(tr(appItem.name, appItem.name), back,
        restartScopes = ScopeApplication.entries.filter { it.packageName == appItem.packageName }.toSet(),
        restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("appNavigationEnableBar", "启用自定义底部导航栏"),
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            if (!value) showAccentDialog = false
                            store.edit(service) { putBoolean(enabledKey, value) }
                            onChanged()
                        },
                    )
                }
            }
            item {
                PortalAppearanceVisibility(enabled) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Group(tr("外观调整", "外观调整")) {
                            OverlayDropdownPreference(title = tr("导航栏样式", "导航栏样式"), items = NavigationStyle.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = styleIndex) { value -> styleIndex = value; store.edit(service) { putString(ScopedSettings.key(appItem.packageName, NavigationStyle.PREFERENCE_KEY), NavigationStyle.entries[value].preferenceValue) }; onChanged() }
                            OverlayDropdownPreference(title = tr("标签显示方式", "标签显示方式"), items = LabelMode.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = labelIndex) { value -> labelIndex = value; store.edit(service) { putString(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_LABEL_MODE), LabelMode.entries[value].preferenceValue) }; onChanged() }
                            OverlayDropdownPreference(title = tr("颜色模式", "颜色模式"), items = AppColorMode.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = colorIndex) { value -> colorIndex = value; store.edit(service) { putString(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_COLOR_MODE), AppColorMode.entries[value].preferenceValue) }; onChanged() }
                            PortalAppearanceVisibility(hyperFloating || hyperDocked) {
                                SwitchPreference(
                                    title = tr("启用强调色", "启用强调色"),
                                    checked = if (hyperDocked) hyperDockedAccentEnabled else hyperAccentEnabled,
                                    onCheckedChange = { value ->
                                        if (hyperDocked) {
                                            hyperDockedAccentEnabled = value
                                            HyperDockedNavigationSettings.saveAccent(prefs, service, appItem.packageName, value)
                                        } else {
                                            hyperAccentEnabled = value
                                            HyperFloatingAccentSettings.save(prefs, service, appItem.packageName, value)
                                        }
                                        if (!value) showAccentDialog = false
                                        onChanged()
                                    },
                                )
                            }
                            PortalAppearanceVisibility(accentEnabled) {
                                NavigationAccentPreference(color = accentColor, enabled = enabled, onClick = { showAccentDialog = true })
                            }
                            PortalAppearanceVisibility(NavigationCornerSettings.supports(NavigationStyle.entries[styleIndex])) {
                                AppNavigationCornerPreferences(prefs, service, appItem.packageName, onChanged)
                            }
                            SwitchPreference(
                                title = tr("navMaterialUseGlobal", "使用全局导航栏材质参数"),
                                checked = followsMaterial,
                                onCheckedChange = { value ->
                                    followsMaterial = value
                                    NavigationMaterialSettings.saveFollow(prefs, service, appItem.packageName, value)
                                    onChanged()
                                },
                            )
                            PortalAppearanceVisibility(!followsMaterial) {
                                SharedViewArrowPreference(title = tr("navMaterialAppTitle", "材质参数单独调整"), onClick = openMaterial)
                            }
                        }
                        PortalAppearanceVisibility(NavigationScaleSettings.supports(NavigationStyle.entries[styleIndex])) {
                            AppNavigationScalePreferences(prefs, service, appItem.packageName, onChanged)
                        }
                        if (appItem.packageName == "com.xingin.xhs") {
                            XiaohongshuPublishPreferences(prefs, service, onChanged)
                        }
                        if (appItem.packageName == "com.reddit.frontpage") {
                            RedditCreatePreferences(prefs, service, onChanged)
                        }
                        if (NavigationBadgeSettings.supports(appItem.packageName)) {
                            Group(tr("appNavigationUnreadHints", "未读提示")) {
                                SwitchPreference(
                                    title = tr("显示小红点", "显示小红点"),
                                    summary = tr("在自定义底部导航栏图标旁显示未读提示", "在自定义底部导航栏图标旁显示未读提示"),
                                    checked = showBadges,
                                    onCheckedChange = { value ->
                                        showBadges = value
                                        store.edit(service) { putBoolean(ScopedSettings.key(appItem.packageName, NavigationBadgeSettings.KEY_SHOW_BADGES), value) }
                                        onChanged()
                                    },
                                )
                            }
                        }
                        if (appItem.packageName == "com.mipay.wallet") {
                            Group(tr("钱包导航标签", "钱包导航标签")) {
                                ScopedSettings.WALLET_TAB_IDS.forEach { tab ->
                                    val checked = tab in walletTabs
                                    SwitchPreference(title = walletTabName(tab), checked = checked, enabled = !checked || walletTabs.size > 1, onCheckedChange = { value ->
                                        walletTabs = if (value) walletTabs + tab else walletTabs - tab
                                        store.edit(service) { putFrameworkStringSet(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_VISIBLE_TABS), walletTabs) }; onChanged()
                                    })
                                }
                            }
                        }
                        if (appItem.packageName == "com.tencent.mm") {
                            Group(tr("微信头像圆角", "微信头像圆角")) {
                                EditableSliderPreference(
                                    title = tr("头像圆角半径", "头像圆角半径"),
                                    value = avatarRadius,
                                    valueText = if (avatarRadius == 0f) tr("微信默认", "微信默认") else avatarRadius.toInt().toString(),
                                    valueRange = WeChatAvatarRadiusSettings.MIN.toFloat()..WeChatAvatarRadiusSettings.MAX.toFloat(),
                                    steps = 159,
                                    defaultValue = 0f,
                                    onValueChange = { avatarRadius = it },
                                    onValueChangeFinished = {
                                        store.edit(service) {
                                            putInt(ScopedSettings.key(appItem.packageName, WeChatAvatarRadiusSettings.KEY),
                                                avatarRadius.toInt().coerceIn(WeChatAvatarRadiusSettings.MIN, WeChatAvatarRadiusSettings.MAX))
                                        }
                                        onChanged()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    NavigationAccentDialog(
        show = showAccentDialog,
        initialColor = accentColor,
        defaultColor = NavigationAccentSettings.defaultColor(appItem.packageName),
        onDismiss = { showAccentDialog = false },
        onSave = { selected ->
            accentColor = selected
            store.edit(service) {
                putInt(ScopedSettings.key(appItem.packageName, NavigationAccentSettings.KEY), selected or 0xFF000000.toInt())
            }
            onChanged()
            showAccentDialog = false
        },
    )
}

@Composable
private fun AppNavigationScalePreferences(
    preferences: SharedPreferences, service: XposedService?, packageName: String, onChanged: () -> Unit,
) {
    var settings by remember(preferences, packageName, service) { mutableStateOf(NavigationScaleSettings.load(preferences, packageName)) }
    var width by remember(settings.widthHundredths) { mutableFloatStateOf(settings.widthHundredths / 100f) }
    var height by remember(settings.heightHundredths) { mutableFloatStateOf(settings.heightHundredths / 100f) }
    DisposableEffect(preferences, packageName) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || (NavigationScaleSettings.isPreference(key) && key.startsWith("scope.$packageName."))) {
                settings = NavigationScaleSettings.load(preferences, packageName)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun save(value: NavigationScaleSettings.Settings) {
        settings = value.normalized()
        NavigationScaleSettings.save(preferences, service, packageName, settings)
        onChanged()
    }
    Group(tr("appNavigationScale", "缩放与定位")) {
        SwitchPreference(
            title = tr("appNavigationWidthScale", "底部导航栏宽度缩放"),
            checked = settings.widthEnabled,
            onCheckedChange = { save(settings.copy(widthEnabled = it)) },
        )
        PortalAppearanceVisibility(settings.widthEnabled) {
            EditableSliderPreference(
                title = tr("appNavigationWidthScale", "底部导航栏宽度缩放"),
                value = width,
                valueText = tr("appNavigationScalePercent", "%.2f%%").format(java.util.Locale.getDefault(), width),
                valueRange = -100f..100f, steps = 19999, defaultValue = 0f, centerValue = 0f,
                onValueChange = { width = NavigationScaleSettings.hundredths(it) / 100f },
                onValueChangeFinished = { save(settings.copy(widthHundredths = NavigationScaleSettings.hundredths(width))) },
            )
        }
        SwitchPreference(
            title = tr("appNavigationHeightScale", "底部导航栏高度缩放"),
            checked = settings.heightEnabled,
            onCheckedChange = { save(settings.copy(heightEnabled = it)) },
        )
        PortalAppearanceVisibility(settings.heightEnabled) {
            EditableSliderPreference(
                title = tr("appNavigationHeightScale", "底部导航栏高度缩放"),
                value = height,
                valueText = tr("appNavigationScalePercent", "%.2f%%").format(java.util.Locale.getDefault(), height),
                valueRange = -100f..100f, steps = 19999, defaultValue = 0f, centerValue = 0f,
                onValueChange = { height = NavigationScaleSettings.hundredths(it) / 100f },
                onValueChangeFinished = { save(settings.copy(heightHundredths = NavigationScaleSettings.hundredths(height))) },
            )
        }
        AppNavigationOffsetPreferences(preferences, service, packageName)
    }
}

@Composable
private fun AppNavigationCornerPreferences(
    preferences: SharedPreferences, service: XposedService?, packageName: String, onChanged: () -> Unit,
) {
    var settings by remember(preferences, packageName, service) { mutableStateOf(NavigationCornerSettings.load(preferences, packageName)) }
    var draft by remember(settings.value) { mutableFloatStateOf(settings.value.toFloat()) }
    DisposableEffect(preferences, packageName) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || (NavigationCornerSettings.isPreference(key) && key.startsWith("scope.$packageName."))) {
                settings = NavigationCornerSettings.load(preferences, packageName)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun save(value: NavigationCornerSettings.Settings) {
        settings = value.normalized()
        NavigationCornerSettings.save(preferences, service, packageName, settings)
        onChanged()
    }
    Column(Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = tr("appNavigationCornerAdjustment", "圆角调整"),
            checked = settings.enabled,
            onCheckedChange = { save(settings.copy(enabled = it)) },
        )
        PortalAppearanceVisibility(settings.enabled) {
            EditableSliderPreference(
                title = tr("appNavigationCornerAdjustment", "圆角调整"),
                value = draft,
                valueText = draft.roundToInt().toString(),
                valueRange = NavigationCornerSettings.MIN.toFloat()..NavigationCornerSettings.MAX.toFloat(),
                steps = NavigationCornerSettings.MAX - NavigationCornerSettings.MIN - 1,
                defaultValue = 0f,
                onValueChange = { draft = it },
                onValueChangeFinished = { save(settings.copy(value = draft.roundToInt())) },
            )
        }
    }
}

@Composable
private fun AppNavigationOffsetPreferences(preferences: SharedPreferences, service: XposedService?, packageName: String? = null) {
    var settings by remember(preferences, packageName, service) { mutableStateOf(NavigationOffsetSettings.load(preferences, packageName)) }
    var draft by remember(settings.valueDp) { mutableFloatStateOf(settings.valueDp.toFloat()) }
    DisposableEffect(preferences, packageName) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == NavigationOffsetSettings.enabledKey(packageName) || key == NavigationOffsetSettings.valueKey(packageName)) {
                settings = NavigationOffsetSettings.load(preferences, packageName)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun save(updated: NavigationOffsetSettings.Settings) {
        settings = updated
        NavigationOffsetSettings.save(preferences, service, packageName, updated)
    }
    SwitchPreference(
        title = if (packageName == null) tr("appNavigationVerticalOffset", "自定义底部导航栏垂直偏移")
            else tr("appNavigationAppVerticalOffset", "单独设置底部导航栏垂直偏移"),
        summary = if (packageName == null) tr("appNavigationVerticalOffsetSummary", "正值向上，负值向下，重启目标应用后生效")
            else tr("appNavigationAppVerticalOffsetSummary", "开启后覆盖全局偏移，关闭后跟随全局设置"),
        checked = settings.enabled,
        onCheckedChange = { save(settings.copy(enabled = it)) },
    )
    PortalAppearanceVisibility(settings.enabled) {
        EditableSliderPreference(
            title = tr("appNavigationVerticalOffsetValue", "垂直偏移"),
            value = draft,
            valueText = tr("appNavigationVerticalOffsetDp", "%d dp").format(draft.roundToInt()),
            valueRange = NavigationOffsetSettings.MIN.toFloat()..NavigationOffsetSettings.MAX.toFloat(),
            steps = NavigationOffsetSettings.MAX - NavigationOffsetSettings.MIN - 1,
            defaultValue = NavigationOffsetSettings.DEFAULT.toFloat(),
            centerValue = 0f,
            onValueChange = { draft = it },
            onValueChangeFinished = { save(settings.copy(valueDp = draft.roundToInt())) },
        )
    }
}

@Composable
internal fun NavigationAccentPreference(
    color: Int, enabled: Boolean,
    title: String = tr("底部导航栏强调色", "底部导航栏强调色"),
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = .38f))
        Box(Modifier.size(28.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), androidx.compose.foundation.shape.CircleShape).padding(2.dp).background(ComposeColor(color), androidx.compose.foundation.shape.CircleShape))
    }
}

@Composable
internal fun NavigationAccentDialog(
    show: Boolean,
    initialColor: Int,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    title: String = tr("底部导航栏强调色", "底部导航栏强调色"),
) {
    var draftColor by remember(show, initialColor) { mutableIntStateOf(initialColor) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { draftColor = it.toArgb() or 0xFF000000.toInt() },
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(onClick = { draftColor = defaultColor }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("恢复默认", "恢复默认"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton({ onSave(draftColor) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private fun walletTabName(id: String) = when (id) {
    "HOME" -> tr("首页", "首页"); "SAVINGS" -> tr("余额宝", "余额宝"); "SHORT_DRAMA" -> tr("短剧", "短剧"); "LOAN" -> tr("借钱", "借钱"); else -> tr("我的", "我的")
}
