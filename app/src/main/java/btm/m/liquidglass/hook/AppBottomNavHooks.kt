package btm.m.liquidglass.hook

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Checkable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.TextureView
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.NavigationStyle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap

object AppBottomNavHooks {
    private const val TAG = "CustomNav-Apps"

    private val xiaohongshu = AppConfig(
        displayName = "Xiaohongshu",
        packageName = "com.xingin.xhs",
        activityName = "com.xingin.xhs.index.v2.IndexActivityV2",
        barResourceName = null,
        anchorResourceName = "index_store",
        requiredTabs = setOf(TabKey.HOME, TabKey.MARKET, TabKey.PUBLISH, TabKey.MESSAGES, TabKey.PROFILE),
        targetVersion = "9.41.0 (9410808)"
    )

    private val tim = AppConfig(
        displayName = "TIM",
        packageName = "com.tencent.tim",
        activityName = "com.tencent.mobileqq.activity.SplashActivity",
        barResourceName = null,
        anchorResourceName = null,
        requiredTabs = setOf(TabKey.MESSAGES, TabKey.CONTACTS, TabKey.DISCOVER, TabKey.DYNAMIC),
        targetVersion = "4.1.0 (4050)",
        fallbackTabOrder = listOf(TabKey.MESSAGES, TabKey.CONTACTS, TabKey.DISCOVER, TabKey.DYNAMIC)
    )

    private val cloudMusic = AppConfig(
        displayName = "CloudMusic",
        packageName = "com.netease.cloudmusic",
        activityName = "android.app.Activity",
        componentPrefix = "com.netease.cloudmusic.activity.IconChange",
        barResourceName = "bottomNav",
        anchorResourceName = null,
        requiredTabs = setOf(TabKey.HOME, TabKey.FOLLOWING, TabKey.PROFILE),
        targetVersion = "9.5.37 (9005037)",
        navigationLayerResourceNames = setOf(
            "bottomNavDividerLine", "v4ShadowView", "v4ShadowViewMask", "shadowView"
        ),
        miniPlayerResourceName = "minPlayerBarContainer"
    )

    private val qqMusic = AppConfig(
        displayName = "QQMusic",
        packageName = "com.tencent.qqmusic",
        activityName = "com.tencent.qqmusic.activity.AppStarterActivity",
        barResourceName = "fx5",
        anchorResourceName = null,
        requiredTabs = setOf(
            TabKey.HOME, TabKey.VIDEO, TabKey.MUSIC_DISCOVER, TabKey.STARLIGHT, TabKey.PROFILE
        ),
        targetVersion = "20.6.0.8 (7208)",
        navigationLayerResourceNames = setOf("kj1", "h_8"),
        miniPlayerResourceName = "fxq",
        fallbackTabOrder = listOf(
            TabKey.HOME, TabKey.VIDEO, TabKey.MUSIC_DISCOVER, TabKey.STARLIGHT, TabKey.PROFILE
        )
    )

    private val weibo = AppConfig(
        displayName = "Weibo",
        packageName = "com.sina.weibo",
        activityName = "com.sina.weibo.MainTabActivity",
        barResourceName = "main_radio",
        anchorResourceName = null,
        requiredTabs = setOf(TabKey.HOME, TabKey.DISCOVER, TabKey.MESSAGES, TabKey.PROFILE),
        targetVersion = "16.5.3 (7982)",
        navigationLayerResourceNames = setOf("iv_bottom_shadow")
    )

    private val appleMusic = AppConfig(
        displayName = "AppleMusic",
        packageName = "com.apple.android.music",
        activityName = "com.apple.android.music.common.MainContentActivity",
        barResourceName = "bottom_navigation",
        anchorResourceName = null,
        requiredTabs = setOf(
            TabKey.LISTEN_NOW, TabKey.BROWSE, TabKey.RADIO, TabKey.LIBRARY, TabKey.MUSIC_SEARCH
        ),
        targetVersion = "6.5.1 (1583)",
        navigationLayerResourceNames = setOf("navigation_tabs_divider", "nav_tabs_top_shadow"),
        miniPlayerResourceName = "mini_player",
        fallbackTabOrder = listOf(
            TabKey.LISTEN_NOW, TabKey.BROWSE, TabKey.RADIO, TabKey.LIBRARY, TabKey.MUSIC_SEARCH
        )
    )

    private val reddit = AppConfig(
        displayName = "Reddit",
        packageName = "com.reddit.frontpage",
        activityName = "com.reddit.launch.main.MainActivity",
        barResourceName = "main_activity_navhost",
        anchorResourceName = null,
        requiredTabs = setOf(
            TabKey.HOME, TabKey.COMMUNITIES, TabKey.PUBLISH, TabKey.CHAT, TabKey.INBOX
        ),
        targetVersion = "2026.31.1 (2631040)",
        fallbackTabOrder = listOf(
            TabKey.HOME, TabKey.COMMUNITIES, TabKey.PUBLISH, TabKey.CHAT, TabKey.INBOX
        ),
        coordinateTabSurface = true
    )

    private val xiaomiStore = AppConfig(
        displayName = "XiaomiStore",
        packageName = "com.xiaomi.shop",
        activityName = "com.xiaomi.shop2.activity.MainActivity",
        barResourceName = null,
        anchorResourceName = null,
        requiredTabs = setOf(
            TabKey.HOME, TabKey.CATEGORY, TabKey.SERVICE, TabKey.CART, TabKey.PROFILE
        ),
        targetVersion = "5.52.0.20260423.r1 (20260423)",
        navigationLayerResourceNames = setOf(
            "main_bottom_tab_shadow", "main_bottom_tab_bg"
        ),
        fallbackTabOrder = listOf(
            TabKey.HOME, TabKey.CATEGORY, TabKey.SERVICE, TabKey.CART, TabKey.PROFILE
        )
    )

    private val xiaomiWallet = AppConfig(
        displayName = "XiaomiWallet",
        packageName = "com.mipay.wallet",
        activityName = "com.xiaomi.jr.app.MiFinanceActivity",
        barResourceName = "flutter_container",
        anchorResourceName = null,
        requiredTabs = setOf(
            TabKey.HOME, TabKey.SAVINGS, TabKey.SHORT_DRAMA, TabKey.LOAN, TabKey.PROFILE
        ),
        targetVersion = "6.110.0.5679.2731 (20577679)",
        fallbackTabOrder = listOf(
            TabKey.HOME, TabKey.SAVINGS, TabKey.SHORT_DRAMA, TabKey.LOAN, TabKey.PROFILE
        )
    )

    private val sessions = WeakHashMap<Activity, Session>()

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installXiaohongshu(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) = install(module, loader, xiaohongshu, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installTim(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) = install(module, loader, tim, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installCloudMusic(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) {
        install(module, loader, cloudMusic, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)
        installCloudMusicMiniPlayerHook(module)
    }

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installQQMusic(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) {
        install(module, loader, qqMusic, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)
        val clipLayout = Class.forName(
            "com.tencent.qqmusic.ui.minibar.ClipBottomConstraintLayout",
            false,
            loader
        )
        val setClipBottom = clipLayout.getDeclaredMethod(
            "setClipBottom",
            Float::class.javaPrimitiveType
        ).apply { isAccessible = true }
        module.hook(setClipBottom)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> chain.proceed(arrayOf<Any>(0f)) }
        installQQMusicMainDeskLayoutHook(module)
    }

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installWeibo(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) = install(module, loader, weibo, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installAppleMusic(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) = install(module, loader, appleMusic, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installReddit(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) {
        installRedditBottomBarHook(module, loader)
        install(module, loader, reddit, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)
    }

    private fun installRedditBottomBarHook(module: XposedModule, loader: ClassLoader) {
        val bottomNavScreen = Class.forName(
            "com.reddit.launch.bottomnav.BottomNavScreen",
            false,
            loader
        )
        val bottomBarContent = bottomNavScreen.declaredMethods.single {
            it.name == "l5" && it.parameterCount == 3
        }.apply { isAccessible = true }
        module.hook(bottomBarContent)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { Unit }
        module.log(Log.INFO, TAG, "Reddit native bottom navigation content hook installed")
    }

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installXiaomiStore(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String
    ) = install(module, loader, xiaomiStore, blurRadius, labelMode, navigationStyle, advancedMaterial, colorMode)

    @JvmStatic
    @Throws(ReflectiveOperationException::class)
    fun installXiaomiWallet(
        module: XposedModule,
        loader: ClassLoader,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String,
        visibleTabs: Set<String>
    ) {
        installWalletTextureRenderMode(module, loader)
        install(
            module,
            loader,
            xiaomiWallet,
            blurRadius,
            labelMode,
            navigationStyle,
            advancedMaterial,
            colorMode,
            visibleTabs
        )
    }

    private fun installWalletTextureRenderMode(module: XposedModule, loader: ClassLoader) {
        val renderModeClass = Class.forName(
            "io.flutter.embedding.android.RenderMode",
            false,
            loader
        )
        val textureMode = renderModeClass.enumConstants.first {
            (it as Enum<*>).name == "texture"
        }
        val builderClass = Class.forName(
            "io.flutter.embedding.android.FlutterFragment\$NewEngineInGroupFragmentBuilder",
            false,
            loader
        )
        val renderMode = builderClass.getDeclaredMethod("renderMode", renderModeClass).apply {
            isAccessible = true
        }
        module.hook(renderMode)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> chain.proceed(arrayOf(textureMode)) }
        module.log(Log.INFO, TAG, "XiaomiWallet Flutter TextureView render hook installed")
    }

    private fun installCloudMusicMiniPlayerHook(module: XposedModule) {
        val onLayout = LinearLayout::class.java.declaredMethods.first {
            it.name == "onLayout" && it.parameterCount == 5
        }.apply { isAccessible = true }
        module.hook(onLayout)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? LinearLayout
                if (view != null && isCloudMusicMiniPlayer(view)) {
                    view.post { dispatchCloudMusicMiniPlayerLayout(view) }
                }
                result
            }
        module.log(Log.INFO, TAG, "CloudMusic minPlayerBarContainer onLayout hook installed")
    }

    private fun isCloudMusicMiniPlayer(view: View): Boolean {
        if (view.id == View.NO_ID || view.context.packageName != cloudMusic.packageName) return false
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ==
            "minPlayerBarContainer"
    }

    private fun isQQMusicMainDeskRoot(view: View): Boolean {
        if (view.id == View.NO_ID || view.context.packageName != qqMusic.packageName) return false
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ==
            "fxu"
    }

    private fun installQQMusicMainDeskLayoutHook(module: XposedModule) {
        val onLayout = android.widget.RelativeLayout::class.java.declaredMethods.first {
            it.name == "onLayout" && it.parameterCount == 5
        }.apply { isAccessible = true }
        module.hook(onLayout)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val root = chain.thisObject as? ViewGroup
                if (root != null && isQQMusicMainDeskRoot(root)) {
                    dispatchQQMusicMainDeskLayout(root)
                }
                result
            }
    }

    private fun dispatchQQMusicMainDeskLayout(root: ViewGroup) {
        val session = synchronized(sessions) {
            sessions.values.firstOrNull { it.ownsQQMusicMainDeskRoot(root) }
        }
        session?.onQQMusicMainDeskRootLayout(root)
    }

    private fun dispatchCloudMusicMiniPlayerLayout(view: View) {
        val session = synchronized(sessions) {
            sessions.values.firstOrNull { it.ownsCloudMusicView(view) }
        }
        session?.onCloudMusicMiniPlayerLayout(view)
    }

    private fun install(
        module: XposedModule,
        loader: ClassLoader,
        config: AppConfig,
        blurRadius: Int,
        labelMode: String,
        navigationStyle: String,
        advancedMaterial: Boolean,
        colorMode: String,
        visibleTabs: Set<String>? = null
    ) {
        val activityClass = Class.forName(config.activityName, false, loader)
        val onResume = findMethod(activityClass, "onResume")
        val onPause = findMethod(activityClass, "onPause")
        val onDestroy = findMethod(activityClass, "onDestroy")

        module.hook(onResume)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && matchesActivity(activityClass, activity, config)) {
                    synchronized(sessions) {
                        sessions.getOrPut(activity) {
                            Session(
                                module,
                                activity,
                                config,
                                blurRadius.coerceIn(0, 40),
                                labelMode,
                                navigationStyle,
                                advancedMaterial,
                                colorMode,
                                visibleTabs
                            )
                        }
                    }.resume()
                }
                result
            }

        module.hook(onPause)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null && matchesActivity(activityClass, activity, config)) {
                    synchronized(sessions) { sessions[activity] }?.pause()
                }
                result
            }

        module.hook(onDestroy)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null && matchesActivity(activityClass, activity, config)) {
                    synchronized(sessions) { sessions.remove(activity) }?.destroy()
                }
                chain.proceed()
            }

        module.log(Log.INFO, TAG, "Hooks installed for ${config.displayName} ${config.targetVersion}")
    }

    private fun matchesActivity(type: Class<*>, activity: Activity, config: AppConfig): Boolean {
        if (!type.isInstance(activity)) return false
        val prefix = config.componentPrefix ?: return true
        return activity.intent?.component?.className?.startsWith(prefix) == true
    }

    private fun findMethod(type: Class<*>, name: String): Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}#$name/0")
    }

    private class Session(
        private val module: XposedModule,
        private val activity: Activity,
        private val config: AppConfig,
        private val blurRadius: Int,
        private val labelMode: String,
        private val navigationStyle: String,
        private val advancedMaterial: Boolean,
        private val colorMode: String,
        private val visibleTabs: Set<String>?
    ) {
        private val selectedIndex = mutableIntStateOf(0)
        private val originalViews = linkedSetOf<View>()
        private val originalStates = IdentityHashMap<View, OriginalViewState>()
        private val originalContentBottomMargins = IdentityHashMap<View, Int>()
        private val originalContentPaddings = IdentityHashMap<View, IntArray>()
        private val originalContentLayoutWidths = IdentityHashMap<View, Int>()
        private val originalContentLayoutHeights = IdentityHashMap<View, Int>()
        private val originalContentBackgrounds = IdentityHashMap<View, Drawable?>()
        private val originalContentWillNotDraw = IdentityHashMap<ViewGroup, Boolean>()
        private val originalAppleBottomChromeAlphas = IdentityHashMap<View, Float>()
        private val originalClipStates = IdentityHashMap<ViewGroup, ClipState>()
        private val originalRelativeBottomRules = IdentityHashMap<View, RelativeBottomRules>()
        private var resumed = false
        private var attempts = 0
        private var originalBar: ViewGroup? = null
        private var detectedTabs = emptyList<DetectedTab>()
        private var navigationTabs = emptyList<DetectedTab>()
        private var nav: ComposeView? = null
        private var lifecycleOwner: InjectedLifecycleOwner? = null
        private var guardRoot: ViewGroup? = null
        private var guard: ViewTreeObserver.OnPreDrawListener? = null
        private var originalNavigationBarColor: Int? = null
        private var originalNavigationBarContrastEnforced: Boolean? = null
        private var originalSystemUiVisibility: Int? = null
        private var originallyDrawsSystemBarBackgrounds = false
        private var miniPlayer: View? = null
        private var originalMiniPlayerTranslationY = 0f
        private var originalMiniPlayerAlpha = 1f
        private var miniPlayerTranslationTarget: View? = null
        private var originalMiniPlayerTargetTranslationY = 0f
        private var appliedMiniPlayerOffset = 0f
        private var miniPlayerBackground: ComposeView? = null
        private var miniPlayerBackgroundHost: FrameLayout? = null
        private var lastQQMusicBottomLayers: String? = null
        private var applePlayerSheet: View? = null
        private var applePlayerSheetBehavior: Any? = null
        private var originalApplePlayerSheetState: Int? = null
        private var applePlayerSheetForcedHidden = false
        private var applePlayerExpansionRequested = false
        private var applePlayerExpansionObserved = false
        private var applePlayerCollapsedTop: Int? = null
        private val cloudMiniPlayerState = mutableStateOf(MusicMiniPlayerState())
        private val appleMiniPlayerState = mutableStateOf(MusicMiniPlayerState())
        private val qqMiniPlayerState = mutableStateOf(MusicMiniPlayerState())
        private var appleArtworkSignature: Long? = null
        private var appleArtworkRetryTitle = ""
        private var appleArtworkRetryUntil = 0L
        private var lastAppleArtworkCaptureAt = 0L

        fun ownsCloudMusicView(view: View): Boolean =
            config === cloudMusic && view.rootView === activity.window.decorView

        fun ownsQQMusicMainDeskRoot(view: View): Boolean =
            config === qqMusic && view.rootView === activity.window.decorView

        fun onCloudMusicMiniPlayerLayout(view: View) {
            if (miniPlayer !== view) {
                miniPlayer?.alpha = originalMiniPlayerAlpha
                resetMiniPlayerPosition()
                miniPlayer = view
                originalMiniPlayerTranslationY = view.translationY
                originalMiniPlayerAlpha = view.alpha
                updateMiniPlayerTranslationTarget(view)
                appliedMiniPlayerOffset = 0f
            }
            if (resumed) {
                (activity.findViewById<ViewGroup>(android.R.id.content))?.let {
                    syncCloudMusicMiniPlayer(it)
                }
            }
        }

        fun onQQMusicMainDeskRootLayout(root: ViewGroup) {
            if (!resumed || nav == null) return
            val pager = findResourceView(root, "main_desk_fragment_pager") ?: return
            if (pager.parent !== root) return

            releaseQQMusicPagerReservations(pager)
            forceQQMusicChildToParent(pager, root)
            forceQQMusicVisiblePageChain(pager)
        }

        fun resume() {
            resumed = true
            nav?.visibility = View.VISIBLE
            attempts = 0
            activity.window.decorView.postDelayed(::attachOrRetry, 300L)
        }

        fun pause() {
            resumed = false
            nav?.visibility = View.GONE
            resetMiniPlayerPosition()
        }

        fun destroy() {
            guard?.let { listener ->
                guardRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
            guard = null
            guardRoot = null
            nav?.let {
                it.disposeComposition()
                (it.parent as? ViewGroup)?.removeView(it)
            }
            nav = null
            removeMiniPlayerBackground()
            lifecycleOwner?.destroy()
            lifecycleOwner = null
            restoreMiniPlayer()
            restoreHostContentSpace()
            restoreOriginalBar()
            ImmersiveNavigationHooks.deactivate(activity)
            restoreSystemNavigationBar()
        }

        private fun attachOrRetry() {
            if (!resumed || activity.isFinishing || activity.isDestroyed || nav != null) return
            val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
            val detected = detectBottomBar(content)
            if (detected == null) {
                if (++attempts < 40) content.postDelayed(::attachOrRetry, 300L)
                else module.log(Log.WARN, TAG, "No ${config.displayName} bottom navigation found")
                return
            }

            try {
                originalBar = detected.container
                detectedTabs = detected.tabs
                navigationTabs = if (config === xiaomiWallet) {
                    detectedTabs.filter { it.key.name in visibleTabs.orEmpty() }
                        .ifEmpty { detectedTabs }
                } else {
                    detectedTabs
                }
                var sourceView: View = detected.container
                while (sourceView.parent is View && sourceView.parent !== content) {
                    sourceView = sourceView.parent as View
                }
                if (sourceView === detected.container) sourceView = content

                rememberOriginalNavigationLayers(content, detected.container)
                config.navigationLayerResourceNames.forEach { rememberResourceView(content, it) }
                config.miniPlayerResourceName?.let { resourceName ->
                    miniPlayer = findResourceView(content, resourceName)?.also {
                        originalMiniPlayerTranslationY = it.translationY
                        originalMiniPlayerAlpha = it.alpha
                        updateMiniPlayerTranslationTarget(it)
                    }
                }
                syncCloudMusicMiniPlayer(content)
                syncAppleMusicMiniPlayer(content)
                syncQQMusicMiniPlayer(content)
                selectedIndex.intValue = navigationTabs.indexOfFirst {
                    it.key != TabKey.PUBLISH && isSelectedRecursively(it.view)
                }.takeIf { it >= 0 } ?: 0
                releaseOriginalBarSpace()
                hideOriginalBar()
                configureSystemNavigationBar()

                val owner = InjectedLifecycleOwner.create(activity)
                lifecycleOwner = owner
                val overlayHost = activity.window.decorView as? ViewGroup ?: content
                owner.attachTo(overlayHost)
                val navigationBackdropSource = when (config) {
                    cloudMusic, qqMusic -> content
                    appleMusic -> findResourceView(content, "navigation_host_group") ?: sourceView
                    else -> sourceView
                }
                val miniPlayerBackdropSource = when (config) {
                    qqMusic -> findResourceView(content, "main_desk_fragment_pager") ?: navigationBackdropSource
                    cloudMusic -> content
                    appleMusic -> navigationBackdropSource
                    else -> sourceView
                }
                val composeView = ComposeView(activity).apply {
                    owner.attachTo(this)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        Box(Modifier.fillMaxSize()) {
                            CustomNavigation(
                                sourceView = navigationBackdropSource,
                                tabs = navigationTabs.mapIndexed { index, tab ->
                                    HostTab(
                                        label = tab.displayLabel,
                                        className = tab.key.name,
                                        icon = extractedTabIcon(tab.key)
                                    ) {
                                        if (tab.key != TabKey.PUBLISH) selectedIndex.intValue = index
                                        openTab(tab)
                                    }
                                },
                                selectedIndex = selectedIndex,
                                blurRadius = blurRadius,
                                labelMode = labelMode,
                                navigationStyle = navigationStyle,
                                advancedMaterial = advancedMaterial,
                                colorMode = colorMode,
                                adaptiveFloatingWidth = true,
                                liquidBottomSpacingDp = if (config === xiaomiWallet) -8 else 8,
                                concealHostBottomBar = config.concealHostBottomBar ||
                                    config === xiaomiWallet,
                                accentColorOverride = if (config === appleMusic) {
                                    androidx.compose.ui.graphics.Color(0xFFFA243C)
                                } else {
                                    null
                                },
                                tabImageVector = if (config === appleMusic) {
                                    ::appleMusicTabIcon
                                } else {
                                    null
                                },
                                redrawNativeText = config === cloudMusic ||
                                    config === qqMusic || config === appleMusic,
                                onHostPreDraw = ::syncHostState
                            )
                            if (config === cloudMusic || config === appleMusic || config === qqMusic) {
                                val playerNavigationStyle =
                                    NavigationStyle.fromPreference(navigationStyle)
                                val miniPlayerBottomPadding = when (playerNavigationStyle) {
                                    NavigationStyle.HYPER_OS -> 64.dp
                                    NavigationStyle.LIQUID_GLASS,
                                    NavigationStyle.HYPER_OS_FLOATING -> 86.dp
                                }
                                val miniPlayerHorizontalPadding =
                                    if (playerNavigationStyle == NavigationStyle.HYPER_OS) 0.dp else 14.dp
                                if (config === appleMusic) {
                                    AppleMusicMiniPlayer(
                                        sourceView = miniPlayerBackdropSource,
                                        state = appleMiniPlayerState.value,
                                        blurRadius = blurRadius,
                                        navigationStyle = navigationStyle,
                                        advancedMaterial = advancedMaterial,
                                        colorMode = colorMode,
                                        onOpenPlayer = {
                                            clickAppleMusicPlayerControl(content, "mini_player_touch_panel")
                                        },
                                        onPrevious = {
                                            dispatchMusicMediaKey(
                                                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                                                "Apple Music"
                                            )
                                        },
                                        onPlayPause = {
                                            clickAppleMusicPlayerControl(content, "mini_player_play_btn")
                                        },
                                        onNext = {
                                            clickAppleMusicPlayerControl(content, "mini_player_next_btn")
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .padding(
                                                start = miniPlayerHorizontalPadding,
                                                end = miniPlayerHorizontalPadding,
                                                bottom = miniPlayerBottomPadding
                                            )
                                    )
                                } else if (config === qqMusic) {
                                    NativeBackdropMusicMiniPlayer(
                                        sourceView = miniPlayerBackdropSource,
                                        state = qqMiniPlayerState.value,
                                        backdropRefreshKey = selectedIndex.intValue,
                                        useLiquidGlassMaterial = false,
                                        blurRadius = blurRadius,
                                        navigationStyle = navigationStyle,
                                        advancedMaterial = advancedMaterial,
                                        colorMode = colorMode,
                                        onOpenPlayer = {
                                            clickQQMusicPlayerControl(content, "hdc", "hd6", "fxx")
                                        },
                                        onPrevious = {
                                            dispatchMusicMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "QQ Music")
                                        },
                                        onPlayPause = {
                                            clickQQMusicPlayerControl(content, "g6_")
                                        },
                                        onNext = {
                                            dispatchMusicMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "QQ Music")
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .padding(
                                                start = miniPlayerHorizontalPadding,
                                                end = miniPlayerHorizontalPadding,
                                                bottom = miniPlayerBottomPadding
                                            )
                                    )
                                } else {
                                    NativeBackdropMusicMiniPlayer(
                                        sourceView = miniPlayerBackdropSource,
                                        state = cloudMiniPlayerState.value,
                                        backdropRefreshKey = selectedIndex.intValue,
                                        useLiquidGlassMaterial = true,
                                        blurRadius = blurRadius,
                                        navigationStyle = navigationStyle,
                                        advancedMaterial = advancedMaterial,
                                        colorMode = colorMode,
                                        onOpenPlayer = {
                                            clickCloudMusicPlayerControl(
                                                content,
                                                true,
                                                "minPlayerBar",
                                                "miniPlayBarReallyRoot"
                                            )
                                        },
                                        onPrevious = {
                                            clickCloudMusicPlayerControl(content, false, "minPrevBtn")
                                        },
                                        onPlayPause = {
                                            clickCloudMusicPlayerControl(content, false, "minPlayBtn")
                                        },
                                        onNext = {
                                            clickCloudMusicPlayerControl(content, false, "minNextBtn")
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .padding(
                                                start = miniPlayerHorizontalPadding,
                                                end = miniPlayerHorizontalPadding,
                                                bottom = miniPlayerBottomPadding
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
                nav = composeView
                overlayHost.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        navigationOverlayHeight(),
                        Gravity.BOTTOM
                    )
                )
                installGuard(content)
                composeView.post(::syncHostState)
                module.log(
                    Log.INFO,
                    TAG,
                    "Custom navigation attached to ${config.displayName} ($navigationStyle): " +
                        navigationTabs.joinToString(" | ") { it.displayLabel }
                )
            } catch (error: Throwable) {
                destroy()
                module.log(Log.ERROR, TAG, "Keeping ${config.displayName}'s original navigation", error)
            }
        }

        private fun installGuard(content: ViewGroup) {
            if (guard != null) return
            guardRoot = content
            guard = ViewTreeObserver.OnPreDrawListener {
                val bar = if (config === qqMusic || config === weibo) {
                    refreshDetectedBar(content)?.container ?: originalBar
                } else {
                    originalBar
                }
                val applePlayerExpanded = config === appleMusic &&
                    isAppleMusicPlayerExpandedOrMoving(content)
                val applePlayerConceal = resumed && !applePlayerExpanded &&
                    !applePlayerExpansionRequested
                updateAppleMusicPlayerSheet(content, conceal = applePlayerConceal)
                // Keep the native sheet laid out at its real collapsed position so its
                // BottomSheetBehavior can expand normally. Only its collapsed drawing is
                // hidden; the full player is restored as soon as expansion starts.
                updateAppleMusicBottomChrome(
                    content,
                    showPlayer = applePlayerExpanded || applePlayerExpansionRequested
                )
                val show = resumed && bar != null && isBottomBarVisible(bar)
                    && (config !== xiaomiWallet || isWalletMainSurfaceReady(bar))
                    && !applePlayerExpanded
                    && !applePlayerExpansionRequested
                nav?.visibility = if (show) View.VISIBLE else View.GONE
                if (show) syncHostState() else resetMiniPlayerPosition()
                true
            }
            content.viewTreeObserver.addOnPreDrawListener(guard)
        }

        private fun syncHostState() {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            if (content != null) {
                syncCloudMusicMiniPlayer(content)
                syncAppleMusicMiniPlayer(content)
                syncQQMusicMiniPlayer(content)
            }
            if (config === qqMusic || config === weibo) {
                if (content != null) refreshDetectedBar(content)
            }
            // Xiaomi Store reapplies its system-bar appearance while the home page refreshes.
            // Reapply the verified edge-to-edge state after that write on every pre-draw.
            ImmersiveNavigationHooks.activate(activity)
            hideXiaomiNavigationLayers()
            updateNavigationOverlayHeight()
            releaseHostContentSpace()
            releaseOriginalBarSpace()
            hideOriginalBar()
            adaptMiniPlayer()
            if (config !== xiaohongshu && config !== qqMusic && config !== weibo) {
                navigationTabs.indexOfFirst {
                    it.key != TabKey.PUBLISH && isSelectedRecursively(it.view)
                }.takeIf { it >= 0 }?.let { selectedIndex.intValue = it }
            }
        }

        private fun hideXiaomiNavigationLayers() {
            if (config !== xiaomiStore) return
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            config.navigationLayerResourceNames.forEach { name ->
                findResourceView(content, name)?.let { layer ->
                    rememberOriginalView(layer)
                    if (layer.alpha != 0f) layer.alpha = 0f
                    (layer as? ViewGroup)?.setWillNotDraw(true)
                }
            }
        }

        private fun openTab(tab: DetectedTab) {
            if (config === xiaohongshu) {
                openXiaohongshuTab(tab)
                return
            }
            if (config === reddit) {
                openRedditTab(tab)
                return
            }
            if (config.coordinateTabSurface) {
                openCoordinateTab(tab)
                return
            }
            if (config === xiaomiWallet) {
                openWalletTab(tab)
                return
            }
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val currentTab = if (content != null && (config === qqMusic || config === weibo)) {
                refreshDetectedBar(content)?.tabs?.firstOrNull { it.key == tab.key } ?: tab
            } else {
                tab
            }
            hideOriginalBar()
            val touchResult = if (config === qqMusic || config === weibo) {
                dispatchSyntheticTap(currentTab.clickTarget)
            } else {
                false
            }
            val clickResult = if (touchResult) false else currentTab.clickTarget.callOnClick() ||
                currentTab.clickTarget.performClick()
            val viewResult = if (touchResult || clickResult) false else
                currentTab.view.callOnClick() || currentTab.view.performClick()
            if (!touchResult && !clickResult && !viewResult) {
                module.log(Log.WARN, TAG, "${config.displayName} rejected click for ${tab.displayLabel}")
            }
            currentTab.view.postDelayed(::syncHostState, 120L)
        }

        private fun openXiaohongshuTab(tab: DetectedTab) {
            val tabIndex = when (tab.key) {
                TabKey.HOME -> 0
                TabKey.MARKET -> 1
                TabKey.MESSAGES -> 2
                TabKey.PROFILE -> 3
                TabKey.PUBLISH -> 5
                else -> return
            }
            val handled = runCatching {
                val interfaceClass = Class.forName(
                    "android.xingin.com.spi.homepage.IMainTabBarProxy",
                    false,
                    activity.classLoader
                )
                val reflectionClass = Class.forName(
                    "kotlin.jvm.internal.Reflection",
                    false,
                    activity.classLoader
                )
                val kotlinClass = reflectionClass.getMethod(
                    "getOrCreateKotlinClass",
                    Class::class.java
                ).invoke(null, interfaceClass)
                val serviceLoaderClass = Class.forName(
                    "com.xingin.spi.service.ServiceLoaderKtKt",
                    false,
                    activity.classLoader
                )
                val serviceMethod = serviceLoaderClass.declaredMethods.first {
                    it.name == "service\$default" && it.parameterTypes.size == 5
                }.apply { isAccessible = true }
                val proxy = serviceMethod.invoke(null, kotlinClass, null, null, 3, null)
                    ?: error("IMainTabBarProxy service unavailable")
                interfaceClass.getMethod(
                    "dispatchTabClick",
                    android.content.Context::class.java,
                    Int::class.javaPrimitiveType
                ).invoke(proxy, activity, tabIndex)
                true
            }.onFailure { error ->
                module.log(Log.WARN, TAG, "Xiaohongshu rejected click for ${tab.displayLabel}", error)
            }.getOrDefault(false)
            if (!handled) {
                val currentTab = detectedTabs.firstOrNull { it.key == tab.key } ?: tab
                currentTab.clickTarget.callOnClick() || currentTab.clickTarget.performClick()
            }
            originalBar?.postDelayed(::syncHostState, 120L)
        }

        private fun openRedditTab(tab: DetectedTab) {
            val redditTabName = when (tab.key) {
                TabKey.HOME -> "Home"
                TabKey.COMMUNITIES -> "Communities"
                TabKey.PUBLISH -> "Post"
                TabKey.CHAT -> "Chat"
                TabKey.INBOX -> "Inbox"
                else -> return
            }
            runCatching {
                val bottomNavTab = Class.forName(
                    "com.reddit.launch.bottomnav.BottomNavTab",
                    false,
                    activity.classLoader
                )
                val tabValue = bottomNavTab.enumConstants.first {
                    (it as Enum<*>).name == redditTabName
                }
                activity.javaClass.getMethod(
                    "J2",
                    bottomNavTab,
                    Boolean::class.javaPrimitiveType
                ).invoke(activity, tabValue, false)
            }.onFailure { error ->
                module.log(Log.WARN, TAG, "Reddit rejected click for ${tab.displayLabel}", error)
            }
            originalBar?.postDelayed(::syncHostState, 120L)
        }

        private fun openCoordinateTab(tab: DetectedTab) {
            val index = detectedTabs.indexOfFirst { it.key == tab.key }
            val surface = originalBar ?: return
            if (index !in detectedTabs.indices || surface.width <= 0 || surface.height <= 0) return
            val density = activity.resources.displayMetrics.density
            val x = (index + 0.5f) * surface.width / detectedTabs.size
            val y = surface.height - navigationBarInset() - 28f * density
            if (!dispatchSyntheticTap(surface, x, y)) {
                module.log(Log.WARN, TAG, "${config.displayName} rejected click for ${tab.displayLabel}")
            }
            surface.postDelayed(::syncHostState, 120L)
        }

        private fun openWalletTab(tab: DetectedTab) {
            val index = detectedTabs.indexOfFirst { it.key == tab.key }
            val flutterContainer = originalBar ?: return
            if (index !in detectedTabs.indices || flutterContainer.width <= 0 || flutterContainer.height <= 0) return
            val density = activity.resources.displayMetrics.density
            val navigationInset = navigationBarInset()
            val x = (index + 0.5f) * flutterContainer.width / detectedTabs.size
            val y = flutterContainer.height - navigationInset - 24f * density
            if (!dispatchSyntheticTap(flutterContainer, x, y)) {
                module.log(Log.WARN, TAG, "XiaomiWallet rejected click for ${tab.displayLabel}")
            }
            flutterContainer.postDelayed(::syncHostState, 120L)
        }

        private fun dispatchSyntheticTap(target: View): Boolean {
            if (target.width <= 0 || target.height <= 0) return false
            return dispatchSyntheticTap(target, target.width / 2f, target.height / 2f)
        }

        private fun dispatchSyntheticTap(target: View, x: Float, y: Float): Boolean {
            val eventTime = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(
                eventTime,
                eventTime,
                android.view.MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )
            val up = android.view.MotionEvent.obtain(
                eventTime,
                eventTime + 24L,
                android.view.MotionEvent.ACTION_UP,
                x,
                y,
                0
            )
            return try {
                val downHandled = target.dispatchTouchEvent(down)
                val upHandled = target.dispatchTouchEvent(up)
                downHandled || upHandled
            } finally {
                down.recycle()
                up.recycle()
            }
        }

        private fun hideOriginalBar() {
            if (config === xiaomiWallet || config.coordinateTabSurface) return
            originalViews.forEach { view ->
                if (view.alpha != 0f) view.alpha = 0f
                if (view is ViewGroup) view.setWillNotDraw(true)
            }
        }

        private fun releaseOriginalBarSpace() {
            if (config === qqMusic || config === weibo || config === xiaomiWallet ||
                config.coordinateTabSurface
            ) return
            val bar = originalBar ?: return
            val params = bar.layoutParams ?: return
            if (params.height != 0) {
                params.height = 0
                bar.layoutParams = params
                bar.requestLayout()
            }
        }

        private fun releaseHostContentSpace() {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (config === appleMusic) {
                releaseAppleMusicPageNavigationInset(content)
            } else if (config === reddit) {
                releaseRedditPageNavigationInset(content)
            } else if (config === xiaomiStore) {
                releaseXiaomiStorePageNavigationInset(content)
            } else if (config === xiaohongshu) {
                val pager = descendants(content).firstOrNull {
                    it.javaClass.simpleName == "ExploreScrollableViewPager"
                } ?: return
                val params = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                originalContentBottomMargins.putIfAbsent(pager, params.bottomMargin)
                if (params.bottomMargin != 0) {
                    params.bottomMargin = 0
                    pager.layoutParams = params
                }
            } else if (config === cloudMusic) {
                val header = descendants(content).firstOrNull {
                    it.javaClass.simpleName == "TopHeaderLayout"
                } ?: return
                originalContentPaddings.putIfAbsent(
                    header,
                    intArrayOf(header.paddingLeft, header.paddingTop, header.paddingRight, header.paddingBottom)
                )
                if (header.paddingBottom != 0) {
                    header.setPadding(header.paddingLeft, header.paddingTop, header.paddingRight, 0)
                }
            } else if (config === qqMusic) {
                clearQQMusicBottomContainerBackground(content, "fya")
                clearQQMusicBottomContainerBackground(content, "fx4")

                releaseQQMusicPageNavigationInset(content)
                releaseQQMusicPager(content)
                if (selectedIndex.intValue == 3) logQQMusicBottomLayers(content)

                val player = findResourceView(content, config.miniPlayerResourceName ?: return) ?: return
                if (miniPlayer !== player) {
                    removeMiniPlayerBackground()
                    resetMiniPlayerPosition()
                    miniPlayer = player
                    originalMiniPlayerTranslationY = player.translationY
                    updateMiniPlayerTranslationTarget(player)
                }
                val params = player.layoutParams ?: return
                originalContentLayoutHeights.putIfAbsent(player, params.height)
                val density = content.resources.displayMetrics.density
                val targetPlayerHeight = (64f * density).toInt()
                if (params.height != targetPlayerHeight) {
                    params.height = targetPlayerHeight
                    player.layoutParams = params
                    player.requestLayout()
                }
                originalContentPaddings.putIfAbsent(
                    player,
                    intArrayOf(
                        player.paddingLeft,
                        player.paddingTop,
                        player.paddingRight,
                        player.paddingBottom
                    )
                )
                if (player.paddingTop != 0 || player.paddingBottom != 0) {
                    player.setPadding(
                        player.paddingLeft,
                        0,
                        player.paddingRight,
                        0
                    )
                }
            } else if (config === weibo) {
                val density = content.resources.displayMetrics.density
                descendants(content).filterIsInstance<ViewGroup>().forEach { tabContent ->
                    if (tabContent.id != android.R.id.tabcontent) return@forEach
                    if (tabContent.paddingBottom != 0) {
                        originalContentPaddings.putIfAbsent(
                            tabContent,
                            intArrayOf(
                                tabContent.paddingLeft,
                                tabContent.paddingTop,
                                tabContent.paddingRight,
                                tabContent.paddingBottom
                            )
                        )
                        tabContent.setPadding(
                            tabContent.paddingLeft,
                            tabContent.paddingTop,
                            tabContent.paddingRight,
                            0
                        )
                    }
                    for (index in 0 until tabContent.childCount) {
                        val pageRoot = tabContent.getChildAt(index)
                        val gap = tabContent.height - pageRoot.height
                        if (!pageRoot.isShown || pageRoot.width < tabContent.width * 3 / 4 ||
                            gap !in (32 * density).toInt()..(80 * density).toInt()
                        ) continue
                        val params = pageRoot.layoutParams ?: continue
                        originalContentLayoutHeights.putIfAbsent(pageRoot, params.height)
                        if (params.height != tabContent.height) {
                            params.height = tabContent.height
                            pageRoot.layoutParams = params
                            pageRoot.requestLayout()
                        }
                    }
                }
            }
        }

        /** Releases only the real system-bar reservation in Apple Music's page host. */
        private fun releaseAppleMusicPageNavigationInset(content: ViewGroup) {
            val navigationInset = navigationBarInset()
            if (navigationInset <= 0) return
            val pageHosts = sequenceOf<View>(content).plus(descendants(content))
                .filterIsInstance<ViewGroup>()
                .filter { resourceEntryName(it) == "navigation_host_group" }
                .toList()
            val pageHost = pageHosts.maxByOrNull { it.width.toLong() * it.height } ?: return
            sequenceOf<View>(pageHost).plus(descendants(pageHost)).forEach { view ->
                if (!view.isShown || view === nav || view === originalBar ||
                    view is ComposeView ||
                    view.height < navigationInset * 4 ||
                    view.width < pageHost.width * 3 / 4
                ) return@forEach

                if (view.paddingBottom == navigationInset) {
                    originalContentPaddings.putIfAbsent(
                        view,
                        intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    )
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
                }

                val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                if (params.bottomMargin == navigationInset) {
                    originalContentBottomMargins.putIfAbsent(view, params.bottomMargin)
                    params.bottomMargin = 0
                    view.layoutParams = params
                }
            }

            // PlayerScrollingViewBehavior shortens the active fragment host by the full
            // native player/navigation peek height, not merely the system navigation inset.
            // Derive that reservation from the two same-named hosts and release it locally.
            val visiblePage = pageHosts
                .filter { it !== pageHost && it.isShown && isDescendantOf(it, pageHost) }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: return
            val hostLocation = IntArray(2).also(pageHost::getLocationInWindow)
            val pageLocation = IntArray(2).also(visiblePage::getLocationInWindow)
            val reservedBottom = hostLocation[1] + pageHost.height -
                (pageLocation[1] + visiblePage.height)
            if (reservedBottom <= navigationInset || reservedBottom > pageHost.height / 2) return

            if (pageHost.paddingBottom == reservedBottom) {
                originalContentPaddings.putIfAbsent(
                    pageHost,
                    intArrayOf(
                        pageHost.paddingLeft,
                        pageHost.paddingTop,
                        pageHost.paddingRight,
                        pageHost.paddingBottom
                    )
                )
                pageHost.setPadding(
                    pageHost.paddingLeft,
                    pageHost.paddingTop,
                    pageHost.paddingRight,
                    0
                )
            }
            val parent = visiblePage.parent as? ViewGroup ?: return
            if (parent !== pageHost) return
            val params = visiblePage.layoutParams
            originalContentLayoutHeights.putIfAbsent(visiblePage, params.height)
            if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                visiblePage.layoutParams = params
            }
            val left = pageHost.paddingLeft
            val top = pageHost.paddingTop
            val right = pageHost.width - pageHost.paddingRight
            val bottom = pageHost.height - pageHost.paddingBottom
            if (right > left && bottom > top &&
                (visiblePage.left != left || visiblePage.top != top ||
                    visiblePage.right != right || visiblePage.bottom != bottom)
            ) {
                visiblePage.measure(
                    View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY)
                )
                visiblePage.layout(left, top, right, bottom)
            }
        }

        private fun updateAppleMusicBottomChrome(content: ViewGroup, showPlayer: Boolean) {
            if (config !== appleMusic) return
            listOf("bottom_navigation_tabs_frame", "player_sheet_container")
                .forEach { resourceName ->
                val view = findResourceView(content, resourceName) ?: return@forEach
                if (!originalContentBackgrounds.containsKey(view)) {
                    originalContentBackgrounds[view] = view.background
                }
                originalAppleBottomChromeAlphas.putIfAbsent(view, view.alpha)
                if (view is ViewGroup) {
                    originalContentWillNotDraw.putIfAbsent(view, view.willNotDraw())
                }
                val conceal = resourceName == "bottom_navigation_tabs_frame" || !showPlayer
                if (conceal) {
                    if (view.background != null) view.background = null
                    view.alpha = 0f
                    (view as? ViewGroup)?.setWillNotDraw(true)
                } else {
                    view.background = originalContentBackgrounds[view]
                    view.alpha = originalAppleBottomChromeAlphas[view] ?: 1f
                    (view as? ViewGroup)?.let { group ->
                        originalContentWillNotDraw[group]?.let(group::setWillNotDraw)
                    }
                }
            }
        }

        /** Releases only Reddit host containers that reserve the real system navigation inset. */
        private fun releaseRedditPageNavigationInset(content: ViewGroup) {
            val navigationInset = navigationBarInset()
            if (navigationInset <= 0) return
            val injectedNav = nav
            sequenceOf<View>(content).plus(descendants(content)).forEach { view ->
                if (!view.isShown || view === injectedNav ||
                    (injectedNav != null && isDescendantOf(view, injectedNav)) ||
                    view.height < navigationInset * 4 ||
                    view.width < content.width * 3 / 4
                ) return@forEach

                if (view.paddingBottom == navigationInset) {
                    originalContentPaddings.putIfAbsent(
                        view,
                        intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    )
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
                }

                val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                if (params.bottomMargin == navigationInset) {
                    originalContentBottomMargins.putIfAbsent(view, params.bottomMargin)
                    params.bottomMargin = 0
                    view.layoutParams = params
                }
            }
        }

        /** Removes only host containers that explicitly reserve the real system navigation inset. */
        private fun releaseXiaomiStorePageNavigationInset(content: ViewGroup) {
            val navigationInset = navigationBarInset()
            if (navigationInset <= 0) return
            val originalBar = originalBar
            val injectedNav = nav
            sequenceOf<View>(content).plus(descendants(content)).forEach { view ->
                if (!view.isShown || view === injectedNav || view === originalBar ||
                    view.height < navigationInset * 4 || view.width < content.width * 3 / 4 ||
                    (originalBar != null && (isDescendantOf(view, originalBar) || isDescendantOf(originalBar, view)))
                ) return@forEach

                if (view.paddingBottom == navigationInset) {
                    originalContentPaddings.putIfAbsent(
                        view,
                        intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    )
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
                }

                val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                if (params.bottomMargin == navigationInset) {
                    originalContentBottomMargins.putIfAbsent(view, params.bottomMargin)
                    params.bottomMargin = 0
                    view.layoutParams = params
                }
            }
        }

        private fun restoreHostContentSpace() {
            originalContentBottomMargins.forEach { (view, bottomMargin) ->
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    if (params.bottomMargin != bottomMargin) {
                        params.bottomMargin = bottomMargin
                        view.layoutParams = params
                    }
                }
            }
            originalContentBottomMargins.clear()
            originalContentPaddings.forEach { (view, padding) ->
                view.setPadding(padding[0], padding[1], padding[2], padding[3])
            }
            originalContentPaddings.clear()
            originalContentLayoutWidths.forEach { (view, width) ->
                view.layoutParams?.takeIf { it.width != width }?.let { params ->
                    params.width = width
                    view.layoutParams = params
                }
            }
            originalContentLayoutWidths.clear()
            originalContentLayoutHeights.forEach { (view, height) ->
                view.layoutParams?.takeIf { it.height != height }?.let { params ->
                    params.height = height
                    view.layoutParams = params
                }
            }
            originalContentLayoutHeights.clear()
            originalContentBackgrounds.forEach { (view, background) ->
                view.background = background
            }
            originalContentBackgrounds.clear()
            originalAppleBottomChromeAlphas.forEach { (view, alpha) -> view.alpha = alpha }
            originalAppleBottomChromeAlphas.clear()
            originalContentWillNotDraw.forEach { (view, willNotDraw) ->
                view.setWillNotDraw(willNotDraw)
            }
            originalContentWillNotDraw.clear()
            originalClipStates.forEach { (view, state) ->
                view.clipChildren = state.clipChildren
                view.clipToPadding = state.clipToPadding
            }
            originalClipStates.clear()
            originalRelativeBottomRules.forEach { (view, rules) ->
                (view.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let { params ->
                    params.removeRule(android.widget.RelativeLayout.ABOVE)
                    params.removeRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                    if (rules.above != 0) {
                        params.addRule(android.widget.RelativeLayout.ABOVE, rules.above)
                    }
                    if (rules.alignParentBottom != 0) {
                        params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                    }
                    view.layoutParams = params
                }
            }
            originalRelativeBottomRules.clear()
            restoreAppleMusicPlayerSheet()
        }

        private fun resizeQQMusicMiniPlayerContent(content: ViewGroup, density: Float) {
            val artworkSize = (44f * density).toInt()
            setQQMusicViewSize(content, "hd9", artworkSize, artworkSize)
            setQQMusicViewSize(content, "g6i", artworkSize, artworkSize)
            setQQMusicViewSize(content, "hd4", artworkSize, artworkSize)
            setQQMusicViewSize(content, "hd_", artworkSize, artworkSize)

            listOf("fxm", "fxx", "fxr", "hdc", "hd6").forEach { resourceName ->
                val view = findResourceView(content, resourceName) ?: return@forEach
                val params = view.layoutParams ?: return@forEach
                originalContentLayoutHeights.putIfAbsent(view, params.height)
                if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = params
                    view.requestLayout()
                }
            }
        }

        private fun setQQMusicViewSize(
            content: ViewGroup,
            resourceName: String,
            width: Int,
            height: Int
        ) {
            val view = findResourceView(content, resourceName) ?: return
            val params = view.layoutParams ?: return
            originalContentLayoutWidths.putIfAbsent(view, params.width)
            originalContentLayoutHeights.putIfAbsent(view, params.height)
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                view.layoutParams = params
                view.requestLayout()
            }
        }

        private fun logQQMusicBottomLayers(content: ViewGroup) {
            val density = content.resources.displayMetrics.density
            val contentLocation = IntArray(2).also(content::getLocationInWindow)
            val contentBottom = contentLocation[1] + content.height
            val bottomBandTop = contentBottom - (220f * density).toInt()
            val layers = descendants(content)
                .mapNotNull { view ->
                    if (!view.isShown || view.width < content.width * 3 / 4) return@mapNotNull null
                    val visibleBounds = android.graphics.Rect()
                    if (!view.getGlobalVisibleRect(visibleBounds) ||
                        visibleBounds.bottom < bottomBandTop || visibleBounds.top >= contentBottom
                    ) return@mapNotNull null
                    val isBottomOverlay = view.height <= (240f * density).toInt()
                    if (view.background == null && !isBottomOverlay) return@mapNotNull null
                    view to visibleBounds
                }
                .sortedWith(
                    compareByDescending<Pair<View, android.graphics.Rect>> { it.first.z }
                        .thenByDescending { it.second.top }
                )
                .map { (view, visibleBounds) ->
                    val location = IntArray(2).also(view::getLocationInWindow)
                    val name = if (view.id == View.NO_ID) "-" else runCatching {
                        view.resources.getResourceEntryName(view.id)
                    }.getOrDefault("?")
                    val margin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                    val background = when (val drawable = view.background) {
                        is android.graphics.drawable.ColorDrawable ->
                            "Color#${drawable.color.toUInt().toString(16)}"
                        null -> "none"
                        else -> drawable.javaClass.simpleName
                    }
                    "$name:${view.javaClass.simpleName}@${location[1]}+${view.height}" +
                        "/visible=${visibleBounds.top}-${visibleBounds.bottom}/z${view.z}" +
                        "/p${view.paddingBottom}/m$margin/a${view.alpha}/$background"
                }
                .take(64)
                .joinToString(" | ")
            if (layers == lastQQMusicBottomLayers) return
            lastQQMusicBottomLayers = layers
            layers.chunked(3000).forEachIndexed { index, chunk ->
                Log.i(TAG, "QQMusic starlight bottom[$index]: $chunk")
            }
        }

        private fun clearQQMusicBottomContainerBackground(content: ViewGroup, resourceName: String): View? {
            val view = findResourceView(content, resourceName) ?: return null
            if (!originalContentBackgrounds.containsKey(view)) {
                originalContentBackgrounds[view] = view.background
            }
            if (view.background != null) view.background = null
            (view as? ViewGroup)?.let { group ->
                originalContentWillNotDraw.putIfAbsent(group, group.willNotDraw())
                group.setWillNotDraw(true)
            }
            return view
        }

        private fun releaseQQMusicPageNavigationInset(content: ViewGroup) {
            val navigationInset = navigationBarInset()
            if (navigationInset <= 0) return
            val originalBar = originalBar
            val player = miniPlayer
            val background = miniPlayerBackground
            descendants(content).forEach { view ->
                if (!view.isShown || view === nav || view === background ||
                    view.height < navigationInset * 4 || view.width < content.width * 3 / 4 ||
                    isInQQMusicBottomChrome(view, originalBar, player, background)
                ) return@forEach

                if (view.paddingBottom == navigationInset) {
                    originalContentPaddings.putIfAbsent(
                        view,
                        intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    )
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
                }

                val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                if (params.bottomMargin == navigationInset) {
                    originalContentBottomMargins.putIfAbsent(view, params.bottomMargin)
                    params.bottomMargin = 0
                    view.layoutParams = params
                }
            }
        }

        private fun isInQQMusicBottomChrome(
            view: View,
            originalBar: View?,
            player: View?,
            background: View?
        ): Boolean = listOfNotNull(originalBar, player, background).any { chrome ->
            view === chrome || isDescendantOf(view, chrome) || isDescendantOf(chrome, view)
        }

        private fun isDescendantOf(view: View, ancestor: View): Boolean =
            generateSequence(view.parent as? View) { it.parent as? View }.any { it === ancestor }

        private fun navigationBarInset(): Int {
            val insets = activity.window.decorView.rootWindowInsets ?: return 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
        }

        private fun releaseQQMusicPager(content: ViewGroup) {
            val pager = findResourceView(content, "main_desk_fragment_pager") ?: return
            val params = pager.layoutParams as? android.widget.RelativeLayout.LayoutParams ?: return
            if (!originalRelativeBottomRules.containsKey(pager)) {
                originalRelativeBottomRules[pager] = RelativeBottomRules(
                    above = params.rules[android.widget.RelativeLayout.ABOVE],
                    alignParentBottom = params.rules[android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM]
                )
            }
            originalContentBottomMargins.putIfAbsent(pager, params.bottomMargin)
            var changed = false
            if (params.rules[android.widget.RelativeLayout.ABOVE] != 0) {
                params.removeRule(android.widget.RelativeLayout.ABOVE)
                changed = true
            }
            if (params.rules[android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM] == 0) {
                params.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                changed = true
            }
            if (params.bottomMargin != 0) {
                params.bottomMargin = 0
                changed = true
            }
            val parent = pager.parent as? ViewGroup
            if (parent != null && pager.top == 0 && parent.height > pager.height) {
                originalContentLayoutHeights.putIfAbsent(pager, params.height)
                if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    params.height = ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
            }
            if (changed) {
                pager.layoutParams = params
                pager.requestLayout()
            }
            releaseQQMusicPagerReservations(pager)
            forceQQMusicChildToParent(pager, pager.parent as? ViewGroup ?: return)
            forceQQMusicVisiblePageChain(pager)
        }

        private fun releaseQQMusicPagerReservations(rootPager: View) {
            val navigationInset = navigationBarInset()
            if (navigationInset <= 0) return
            descendants(rootPager)
                .filter { view ->
                    view === rootPager || view.javaClass.name.contains("ViewPager")
                }
                .forEach { pager ->
                    val parent = pager.parent as? ViewGroup ?: return@forEach
                    if (!pager.isShown || pager.width < parent.width * 3 / 4 ||
                        pager.height < navigationInset * 4 || pager.top !in 0..navigationInset
                    ) return@forEach

                    val bottomGap = parent.height - pager.bottom
                    if (bottomGap !in 1..(navigationInset * 4)) return@forEach

                    var changed = false
                    if (parent.paddingBottom == bottomGap) {
                        originalContentPaddings.putIfAbsent(
                            parent,
                            intArrayOf(
                                parent.paddingLeft,
                                parent.paddingTop,
                                parent.paddingRight,
                                parent.paddingBottom
                            )
                        )
                        parent.setPadding(
                            parent.paddingLeft,
                            parent.paddingTop,
                            parent.paddingRight,
                            0
                        )
                        changed = true
                    }

                    val params = pager.layoutParams as? ViewGroup.MarginLayoutParams
                        ?: return@forEach
                    if (params.bottomMargin == bottomGap) {
                        originalContentBottomMargins.putIfAbsent(pager, params.bottomMargin)
                        params.bottomMargin = 0
                        changed = true
                    }
                    if (pager.height < parent.height &&
                        params.height != ViewGroup.LayoutParams.MATCH_PARENT
                    ) {
                        originalContentLayoutHeights.putIfAbsent(pager, params.height)
                        params.height = ViewGroup.LayoutParams.MATCH_PARENT
                        changed = true
                    }
                    if (changed) {
                        pager.layoutParams = params
                        parent.requestLayout()
                    }
                }
        }

        private fun forceQQMusicVisiblePageChain(rootPager: View) {
            val navigationInset = navigationBarInset()
            if (navigationInset <= 0) return
            var parent = rootPager as? ViewGroup ?: return
            repeat(12) {
                val viewportLeft = parent.scrollX + parent.paddingLeft
                val viewportTop = parent.scrollY + parent.paddingTop
                val viewportRight = parent.scrollX + parent.width - parent.paddingRight
                val viewportBottom = parent.scrollY + parent.height - parent.paddingBottom
                val targetWidth = viewportRight - viewportLeft
                val targetHeight = viewportBottom - viewportTop
                if (targetWidth <= 0 || targetHeight <= 0) return

                val child = (0 until parent.childCount)
                    .map(parent::getChildAt)
                    .filter { view ->
                        view.isShown && view.width >= targetWidth * 3 / 4 &&
                            view.height >= navigationInset * 4 &&
                            kotlin.math.abs(view.left - viewportLeft) <= navigationInset &&
                            kotlin.math.abs(view.top - viewportTop) <= navigationInset
                    }
                    .maxByOrNull { view -> view.width.toLong() * view.height.toLong() }
                    ?: return

                val bottomGap = viewportBottom - child.bottom
                if (bottomGap !in 0..(navigationInset * 4)) return
                if (bottomGap != 0 || child.measuredWidth != targetWidth ||
                    child.measuredHeight != targetHeight
                ) {
                    child.measure(
                        View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
                    )
                    child.layout(viewportLeft, viewportTop, viewportRight, viewportBottom)
                }
                parent = child as? ViewGroup ?: return
            }
        }

        private fun forceQQMusicChildToParent(child: View, parent: ViewGroup) {
            val left = parent.scrollX + parent.paddingLeft
            val top = parent.scrollY + parent.paddingTop
            val right = parent.scrollX + parent.width - parent.paddingRight
            val bottom = parent.scrollY + parent.height - parent.paddingBottom
            val targetWidth = right - left
            val targetHeight = bottom - top
            if (targetWidth <= 0 || targetHeight <= 0 ||
                (child.left == left && child.top == top && child.right == right &&
                    child.bottom == bottom && child.measuredWidth == targetWidth &&
                    child.measuredHeight == targetHeight)
            ) return

            // QQMusic measures page roots against a stale bottom-bar height before laying parents full-screen.
            child.measure(
                View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
            )
            child.layout(left, top, right, bottom)
        }

        private fun ensureMiniPlayerBackground(sourceView: View) {
            if (config !== qqMusic || miniPlayerBackground != null) return
            val player = miniPlayer as? ViewGroup ?: return
            val owner = lifecycleOwner ?: return
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val host = findResourceView(
                content,
                "fya"
            ) as? FrameLayout ?: return
            if (!isDescendantOf(player, host)) return
            allowMiniPlayerBackgroundOverflow(host)
            val background = ComposeView(activity).apply {
                owner.attachTo(this)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    MiniPlayerBackground(
                        sourceView = sourceView,
                        blurRadius = blurRadius,
                        navigationStyle = navigationStyle,
                        advancedMaterial = advancedMaterial,
                        colorMode = colorMode,
                        redrawNativeText = true,
                        onHostPreDraw = ::syncHostState
                    )
                }
            }
            host.addView(
                background,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
            miniPlayerBackground = background
            miniPlayerBackgroundHost = host
            layoutMiniPlayerBackground()
        }

        private fun allowMiniPlayerBackgroundOverflow(host: FrameLayout) {
            listOfNotNull(host, host.parent as? ViewGroup).forEach { group ->
                originalClipStates.putIfAbsent(
                    group,
                    ClipState(group.clipChildren, group.clipToPadding)
                )
                group.clipChildren = false
                group.clipToPadding = false
            }
        }

        private fun layoutMiniPlayerBackground() {
            val player = miniPlayer ?: return
            val background = miniPlayerBackground ?: return
            val host = miniPlayerBackgroundHost ?: return
            if (player.height <= 0 || host.height <= 0) return
            val playerLocation = IntArray(2).also(player::getLocationInWindow)
            val hostLocation = IntArray(2).also(host::getLocationInWindow)
            val playerTop = playerLocation[1] - hostLocation[1]
            val extraHeight = (8f * host.resources.displayMetrics.density).toInt()
            val backgroundHeight = maxOf(
                player.height + extraHeight,
                (64f * host.resources.displayMetrics.density).toInt()
            )
            val targetTopMargin = playerTop + (player.height - backgroundHeight) / 2
            val params = background.layoutParams as? FrameLayout.LayoutParams ?: return
            if (params.height != backgroundHeight || params.topMargin != targetTopMargin) {
                params.height = backgroundHeight
                params.topMargin = targetTopMargin
                background.layoutParams = params
            }
        }

        private fun removeMiniPlayerBackground() {
            miniPlayerBackground?.let { background ->
                (background.parent as? ViewGroup)?.removeView(background)
                background.disposeComposition()
            }
            miniPlayerBackground = null
            miniPlayerBackgroundHost = null
        }

        private fun restoreOriginalBar() {
            originalStates.forEach { (view, state) ->
                view.alpha = state.alpha
                if (view is ViewGroup) view.setWillNotDraw(state.willNotDraw)
                view.layoutParams?.takeIf { it.height != state.layoutHeight }?.let { params ->
                    params.height = state.layoutHeight
                    view.layoutParams = params
                }
            }
            originalBar = null
            originalViews.clear()
            originalStates.clear()
        }

        private fun rememberOriginalView(view: View) {
            if (originalViews.add(view)) {
                originalStates[view] = OriginalViewState(
                    alpha = view.alpha,
                    willNotDraw = view is ViewGroup && view.willNotDraw(),
                    layoutHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }

        private fun rememberOriginalNavigationLayers(content: ViewGroup, bar: ViewGroup) {
            if (config === xiaomiWallet || config.coordinateTabSurface) return
            rememberOriginalView(bar)
            if (config !== xiaohongshu) return

            var layer = bar.parent as? ViewGroup
            while (layer != null && layer !== content && layer.childCount == 1) {
                rememberOriginalView(layer)
                layer = layer.parent as? ViewGroup
            }
        }

        private fun rememberResourceView(root: View, entryName: String) {
            findResourceView(root, entryName)?.let(::rememberOriginalView)
        }

        private fun findResourceView(root: View, entryName: String): View? {
            val packageNames = if (config === xiaomiStore) {
                listOf(config.packageName, "com.xiaomi.shop.plugin.homepage")
            } else {
                listOf(config.packageName)
            }
            packageNames.forEach { packageName ->
                val id = root.resources.getIdentifier(entryName, "id", packageName)
                if (id != 0) root.findViewById<View>(id)?.let { return it }
            }
            // Split APK resources may expose the ID under a different package name.
            descendants(root).firstOrNull { view ->
                view.id != View.NO_ID && runCatching {
                    view.resources.getResourceEntryName(view.id) == entryName
                }.getOrDefault(false)
            }?.let { return it }
            return null
        }

        private fun resourceEntryName(view: View): String? =
            if (view.id == View.NO_ID) null else runCatching {
                view.resources.getResourceEntryName(view.id)
            }.getOrNull()

        private fun isBottomBarVisible(bar: View): Boolean {
            if (!bar.isAttachedToWindow || bar.visibility != View.VISIBLE) return false
            var ancestor = bar.parent
            while (ancestor is View) {
                if (ancestor.visibility != View.VISIBLE) return false
                ancestor = ancestor.parent
            }
            return true
        }

        private fun syncCloudMusicMiniPlayer(content: ViewGroup) {
            if (config !== cloudMusic) return
            val player = findResourceView(content, config.miniPlayerResourceName ?: return)
            if (player == null) {
                if (cloudMiniPlayerState.value.visible) {
                    cloudMiniPlayerState.value = MusicMiniPlayerState()
                }
                return
            }
            if (miniPlayer !== player) {
                miniPlayer?.alpha = originalMiniPlayerAlpha
                resetMiniPlayerPosition()
                miniPlayer = player
                originalMiniPlayerTranslationY = player.translationY
                originalMiniPlayerAlpha = player.alpha
                updateMiniPlayerTranslationTarget(player)
            }

            val title = sequenceOf(
                "tv_music", "musicName", "songName", "musicNameTv", "mini_fly_song_name"
            )
                .mapNotNull { findResourceView(player, it) as? TextView }
                .map { it.text?.toString()?.trim().orEmpty() }
                .firstOrNull { it.isNotEmpty() }
                ?: descendants(player)
                    .filterIsInstance<TextView>()
                    .filter { it.isShown }
                    .map { it.text?.toString()?.trim().orEmpty() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            val visible = player.isAttachedToWindow && player.visibility == View.VISIBLE &&
                title.isNotEmpty()
            if (!visible) {
                player.alpha = originalMiniPlayerAlpha
                if (cloudMiniPlayerState.value.visible) {
                    cloudMiniPlayerState.value = MusicMiniPlayerState()
                }
                return
            }
            val previous = cloudMiniPlayerState.value
            val artwork = if (previous.title != title || previous.artwork == null) {
                sequenceOf(
                    "iv_smallAlbumCover", "music_cover", "iv_aidj_cover",
                    "miniCover", "miniDiskContainer", "minibarAvatar"
                )
                    .mapNotNull { findResourceView(player, it) }
                    .mapNotNull(::extractArtworkBitmap)
                    .firstOrNull()
                    ?.asImageBitmap()
                    ?: previous.artwork
            } else {
                previous.artwork
            }
            val playButton = findResourceView(player, "minPlayBtn")
            val description = playButton?.contentDescription?.toString().orEmpty()
            val isPlaying = readBooleanField(playButton, "mIsPlaying")
                ?: (description.contains("pause", ignoreCase = true) ||
                    (description.contains("\u6682\u505c") &&
                        !description.contains("\u64ad\u653e\u6682\u505c")) ||
                    playButton?.isActivated == true || playButton?.isSelected == true)
            val updated = MusicMiniPlayerState(true, title, artwork, isPlaying)
            if (updated != previous) cloudMiniPlayerState.value = updated
            if (player.alpha != 0f) player.alpha = 0f
        }

        private fun readBooleanField(view: View?, fieldName: String): Boolean? {
            view ?: return null
            return generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .mapNotNull { type ->
                    runCatching {
                        type.getDeclaredField(fieldName).apply { isAccessible = true }.getBoolean(view)
                    }.getOrNull()
                }
                .firstOrNull()
        }

        private fun clickCloudMusicPlayerControl(
            content: ViewGroup,
            fallbackToPlayer: Boolean,
            vararg resourceNames: String
        ) {
            val target = resourceNames.firstNotNullOfOrNull { findResourceView(content, it) }
                ?: miniPlayer?.takeIf { fallbackToPlayer }
            val handled = target?.let {
                it.callOnClick() || it.performClick() || dispatchSyntheticTap(it)
            } == true
            if (!handled) {
                val mediaKey = when (resourceNames.firstOrNull()) {
                    "minPrevBtn" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "minPlayBtn" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "minNextBtn" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> null
                }
                if (mediaKey != null) dispatchMusicMediaKey(mediaKey, "CloudMusic")
                else module.log(Log.WARN, TAG, "CloudMusic rejected ${resourceNames.joinToString()}")
            }
            content.postDelayed(::syncHostState, 100L)
        }

        private fun syncQQMusicMiniPlayer(content: ViewGroup) {
            if (config !== qqMusic) return
            val player = findResourceView(content, config.miniPlayerResourceName ?: return)
            if (player == null) {
                if (qqMiniPlayerState.value.visible) {
                    qqMiniPlayerState.value = MusicMiniPlayerState()
                }
                return
            }
            if (miniPlayer !== player) {
                miniPlayer?.alpha = originalMiniPlayerAlpha
                resetMiniPlayerPosition()
                miniPlayer = player
                originalMiniPlayerTranslationY = player.translationY
                originalMiniPlayerAlpha = player.alpha
                updateMiniPlayerTranslationTarget(player)
            }

            val title = (findResourceView(player, "g5y") as? TextView)
                ?.text?.toString()?.trim().orEmpty()
            val visible = player.isAttachedToWindow && player.visibility == View.VISIBLE &&
                title.isNotEmpty()
            if (!visible) {
                player.alpha = originalMiniPlayerAlpha
                if (qqMiniPlayerState.value.visible) {
                    qqMiniPlayerState.value = MusicMiniPlayerState()
                }
                return
            }

            val previous = qqMiniPlayerState.value
            val artwork = if (previous.title != title || previous.artwork == null) {
                sequenceOf("hd4", "g6i", "hd_", "hd9")
                    .mapNotNull { findResourceView(player, it) }
                    .mapNotNull(::extractArtworkBitmap)
                    .firstOrNull()
                    ?.asImageBitmap()
                    ?: previous.artwork
            } else {
                previous.artwork
            }
            val isPlaying = isQQMusicPlaying(player)
            val updated = MusicMiniPlayerState(
                visible = true,
                title = title,
                artwork = artwork,
                isPlaying = isPlaying
            )
            if (updated != previous) qqMiniPlayerState.value = updated
            if (player.alpha != 0f) player.alpha = 0f
        }

        private fun isQQMusicPlaying(player: View): Boolean {
            val playButton = findResourceView(player, "g6_")
            val description = playButton?.contentDescription?.toString().orEmpty()
            if (description.contains("pause", ignoreCase = true) ||
                description.contains("\u6682\u505c")
            ) return true
            if (playButton?.isActivated == true || playButton?.isSelected == true) return true
            return runCatching {
                activity.getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true
            }.getOrDefault(false)
        }

        private fun clickQQMusicPlayerControl(
            content: ViewGroup,
            vararg resourceNames: String
        ) {
            val target = resourceNames.firstNotNullOfOrNull { findResourceView(content, it) }
                ?: return
            val handled = target.callOnClick() || target.performClick() || dispatchSyntheticTap(target)
            if (!handled) {
                module.log(Log.WARN, TAG, "QQ Music rejected ${resourceNames.joinToString()}")
            }
            content.postDelayed(::syncHostState, 100L)
        }

        private fun dispatchMusicMediaKey(keyCode: Int, appName: String) {
            val audioManager = activity.getSystemService(android.media.AudioManager::class.java)
                ?: return
            val eventTime = android.os.SystemClock.uptimeMillis()
            runCatching {
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
                )
                audioManager.dispatchMediaKeyEvent(
                    KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
                )
            }.onFailure { error ->
                module.log(Log.WARN, TAG, "$appName media key $keyCode failed", error)
            }
            activity.window.decorView.postDelayed(::syncHostState, 150L)
        }

        private fun syncAppleMusicMiniPlayer(content: ViewGroup) {
            if (config !== appleMusic) return
            val player = findResourceView(content, config.miniPlayerResourceName ?: return)
            if (player == null) {
                if (appleMiniPlayerState.value.visible) {
                    appleMiniPlayerState.value = MusicMiniPlayerState()
                }
                return
            }
            if (miniPlayer !== player) {
                miniPlayer?.alpha = originalMiniPlayerAlpha
                miniPlayer = player
                originalMiniPlayerTranslationY = player.translationY
                originalMiniPlayerAlpha = player.alpha
                updateMiniPlayerTranslationTarget(player)
            }

            val title = (findResourceView(player, "mini_player_title") as? TextView)
                ?.text?.toString()?.trim().orEmpty()
            val visible = player.isAttachedToWindow && player.visibility == View.VISIBLE &&
                title.isNotEmpty()
            if (!visible) {
                player.alpha = originalMiniPlayerAlpha
                if (appleMiniPlayerState.value.visible) {
                    appleMiniPlayerState.value = MusicMiniPlayerState()
                }
                return
            }

            val previous = appleMiniPlayerState.value
            val now = android.os.SystemClock.uptimeMillis()
            val titleChanged = previous.title != title
            if (titleChanged) {
                appleArtworkRetryTitle = title
                appleArtworkRetryUntil = now + 2_000L
                lastAppleArtworkCaptureAt = 0L
                sequenceOf(100L, 300L, 700L, 1_300L).forEach { delay ->
                    content.postDelayed(::syncHostState, delay)
                }
            }
            var artwork = previous.artwork
            val retryingArtwork = appleArtworkRetryTitle == title && now <= appleArtworkRetryUntil
            if ((previous.artwork == null || titleChanged || retryingArtwork) &&
                now - lastAppleArtworkCaptureAt >= 80L
            ) {
                lastAppleArtworkCaptureAt = now
                captureAppleMusicArtwork(player)?.let { bitmap ->
                    val signature = artworkSignature(bitmap)
                    if (previous.artwork == null || appleArtworkSignature == null ||
                        signature != appleArtworkSignature
                    ) {
                        artwork = bitmap.asImageBitmap()
                        appleArtworkSignature = signature
                        appleArtworkRetryTitle = ""
                    }
                }
            }
            val playButton = findResourceView(player, "mini_player_play_btn")
            val playDescription = playButton?.contentDescription?.toString().orEmpty()
            val isPlaying = playDescription.contains("pause", ignoreCase = true) ||
                playDescription.contains("\u6682\u505c")
            val updated = MusicMiniPlayerState(
                visible = true,
                title = title,
                artwork = artwork,
                isPlaying = isPlaying
            )
            if (updated != previous) appleMiniPlayerState.value = updated
            if (player.alpha != 0f) player.alpha = 0f
        }

        private fun captureAppleMusicArtwork(player: View): Bitmap? {
            val artworkView = findResourceView(player, "video_surface")
                ?: findResourceView(player, "video_surface_container")
                ?: return null
            if (artworkView.width <= 0 || artworkView.height <= 0) return null
            return extractArtworkBitmap(artworkView)
        }

        private fun artworkSignature(bitmap: Bitmap): Long {
            var signature = 31L * bitmap.width + bitmap.height
            for (yStep in 1..4) {
                val y = (bitmap.height * yStep / 5).coerceIn(0, bitmap.height - 1)
                for (xStep in 1..4) {
                    val x = (bitmap.width * xStep / 5).coerceIn(0, bitmap.width - 1)
                    signature = signature * 31L + bitmap.getPixel(x, y)
                }
            }
            return signature
        }

        private fun extractArtworkBitmap(view: View): Bitmap? {
            if (view is ImageView) {
                drawableToBitmap(
                    view.drawable,
                    view.width.takeIf { it > 1 } ?: 256,
                    view.height.takeIf { it > 1 } ?: 256
                )?.let { return it }
            }
            if (view is ViewGroup) {
                // NowPlayingContentView adds an idle TextureView before its artwork ImageView.
                // Prefer image descendants so a transparent video frame cannot mask the cover.
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    if (child is ImageView) extractArtworkBitmap(child)?.let { return it }
                }
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    if (child !is TextureView) extractArtworkBitmap(child)?.let { return it }
                }
            }
            generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .takeWhile { it != View::class.java }
                .forEach { type ->
                    type.declaredFields.forEach { field ->
                        val value = runCatching {
                            field.isAccessible = true
                            field.get(view)
                        }.getOrNull()
                        when (value) {
                            is Bitmap -> if (!value.isRecycled && value.width > 1 && value.height > 1) {
                                return value.copy(Bitmap.Config.ARGB_8888, false)
                            }
                            is BitmapDrawable -> drawableToBitmap(value)?.let { return it }
                            is Drawable -> if (value.intrinsicWidth > 24 && value.intrinsicHeight > 24) {
                                drawableToBitmap(value)?.let { return it }
                            }
                        }
                    }
                }
            if (view is TextureView && view.isAvailable) {
                view.bitmap?.takeIf { !it.isRecycled }?.let {
                    return it.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            return null
        }

        private fun drawableToBitmap(
            drawable: Drawable?,
            fallbackWidth: Int = 0,
            fallbackHeight: Int = 0
        ): Bitmap? {
            drawable ?: return null
            if (drawable is BitmapDrawable && !drawable.bitmap.isRecycled) {
                return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            val width = drawable.intrinsicWidth.takeIf { it > 1 } ?: fallbackWidth
            val height = drawable.intrinsicHeight.takeIf { it > 1 } ?: fallbackHeight
            if (width <= 1 || height <= 1) return null
            return runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val oldBounds = android.graphics.Rect(drawable.bounds)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(Canvas(bitmap))
                    drawable.bounds = oldBounds
                }
            }.getOrNull()
        }

        private fun clickAppleMusicPlayerControl(content: ViewGroup, resourceName: String) {
            if (resourceName == "mini_player_touch_panel") {
                applePlayerExpansionRequested = true
                // Do not mark the transition complete until the sheet has physically
                // left its collapsed position. Apple Music reports expanded state early.
                applePlayerExpansionObserved = false
                nav?.visibility = View.GONE
                updateAppleMusicBottomChrome(content, showPlayer = true)
            }
            val target = (findResourceView(content, resourceName)
                ?: if (resourceName == "mini_player_touch_panel") miniPlayer else null)
                ?: return
            // Apple Music opens the full player from its touch listener. performClick()
            // reports success first but bypasses that listener, leaving the sheet collapsed.
            val handled = if (resourceName == "mini_player_touch_panel") {
                dispatchSyntheticTap(target)
            } else {
                target.callOnClick() || target.performClick() || dispatchSyntheticTap(target)
            }
            if (!handled) {
                module.log(Log.WARN, TAG, "Apple Music rejected $resourceName")
            }
            content.postDelayed(::syncHostState, 100L)
            if (resourceName == "mini_player_touch_panel") {
                content.postDelayed({
                    if (applePlayerExpansionRequested &&
                        !isAppleMusicPlayerPhysicallyExpanded(content)
                    ) {
                        applePlayerExpansionRequested = false
                        applePlayerExpansionObserved = false
                        updateAppleMusicBottomChrome(content, showPlayer = false)
                        syncHostState()
                        module.log(Log.WARN, TAG, "Apple Music player expansion timed out")
                    }
                }, 900L)
            }
        }

        private fun isAppleMusicPlayerPhysicallyExpanded(content: ViewGroup): Boolean {
            val sheet = findResourceView(content, "player_sheet_container") ?: return false
            if (!sheet.isAttachedToWindow || sheet.height <= 0) return false
            val collapsedTop = applePlayerCollapsedTop ?: (content.height - sheet.height)
            val movementThreshold = (2f * content.resources.displayMetrics.density).toInt()
            val playerRoot = findResourceView(content, "player_root")
            val playerRootWindowY = playerRoot?.let { root ->
                IntArray(2).also(root::getLocationInWindow)[1]
            }
            return sheet.top < collapsedTop - movementThreshold ||
                (playerRootWindowY != null && playerRootWindowY < content.height / 3)
        }

        private fun isAppleMusicPlayerExpandedOrMoving(content: ViewGroup): Boolean {
            if (config !== appleMusic) return false
            val sheet = findResourceView(content, "player_sheet_container") ?: return false
            if (!sheet.isAttachedToWindow || sheet.height <= 0 || content.height <= 0) return false
            val state = applePlayerSheetState()
            val movementThreshold = (2f * content.resources.displayMetrics.density).toInt()
            val collapsedTop = applePlayerCollapsedTop ?: (content.height - sheet.height).also {
                applePlayerCollapsedTop = it
            }
            if (applePlayerExpansionRequested) {
                val playerRoot = findResourceView(content, "player_root")
                val playerRootWindowY = playerRoot?.let { root ->
                    IntArray(2).also(root::getLocationInWindow)[1]
                }
                val fullPlayerAtTop = playerRootWindowY != null &&
                    playerRootWindowY < content.height / 3
                if (fullPlayerAtTop) {
                    applePlayerExpansionObserved = true
                    return true
                }
                // This is the reliable close signal on Apple Music 6.5.1: the full
                // player root has first reached the top, then returns to the sheet.
                if (applePlayerExpansionObserved && playerRoot != null) {
                    applePlayerExpansionRequested = false
                    applePlayerExpansionObserved = false
                    return false
                }
                // The behavior enters its expanded state before its view moves. Keep the
                // custom layer hidden, but do not treat that transient state as a close.
                if (state == 1 || state == 2 || state == 3 || state == 6) {
                    return true
                }
                // The click handler may post its state transition.  Keep the overlay hidden
                // until that transition is observed or the request timeout releases it.
                return true
            }
            if (sheet.top >= collapsedTop - movementThreshold) {
                applePlayerExpansionRequested = false
                applePlayerExpansionObserved = false
                return false
            }
            if (state != null) return state == 1 || state == 2 || state == 3 || state == 6
            val sheetTop = sheet.top
            return sheetTop < collapsedTop - movementThreshold
        }

        private fun updateAppleMusicPlayerSheet(content: ViewGroup, conceal: Boolean) {
            if (config !== appleMusic) return
            val sheet = findResourceView(content, "player_sheet_container") ?: return
            if (applePlayerSheet !== sheet) {
                // Apple Music replaces this view while promoting the mini player into
                // the full player.  This is not a session teardown: preserving the
                // expansion flags prevents the next guard pass from hiding the new sheet.
                applePlayerSheet = sheet
                applePlayerSheetBehavior = null
                originalApplePlayerSheetState = null
                applePlayerSheetForcedHidden = false
                val params = sheet.layoutParams
                val behaviorFromGetter = runCatching {
                    params.javaClass.methods.firstOrNull {
                        it.name == "getBehavior" && it.parameterCount == 0
                    }?.apply { isAccessible = true }?.invoke(params)
                }.getOrNull()
                val behaviorFromField = runCatching {
                    generateSequence(params.javaClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredFields.asSequence() }
                        .firstOrNull { field ->
                            field.type.name == "androidx.coordinatorlayout.widget.CoordinatorLayout\$c"
                        }?.let { field ->
                            field.isAccessible = true
                            field.get(params)
                        }
                }.getOrNull()
                val behavior = behaviorFromGetter ?: behaviorFromField ?: return
                applePlayerSheetBehavior = behavior
                originalApplePlayerSheetState = applePlayerSheetState()
                applePlayerCollapsedTop = content.height - sheet.height
            }
            // Keep the native behavior in its normal collapsed state.  Setting it to hidden
            // interrupts Apple Music's own click/animation path and can leave a black sheet.
        }

        private fun applePlayerSheetState(): Int? {
            val behavior = applePlayerSheetBehavior ?: return null
            return runCatching {
                generateSequence(behavior.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .first { it.name == "f45337G" }
                    .apply { isAccessible = true }
                    .getInt(behavior)
            }.getOrNull()
        }

        private fun setAppleMusicPlayerState(state: Int) {
            val behavior = applePlayerSheetBehavior ?: return
            runCatching {
                generateSequence(behavior.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .first { it.name == "G" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
                    .apply { isAccessible = true }
                    .invoke(behavior, state)
            }
        }

        private fun restoreAppleMusicPlayerSheet() {
            if (applePlayerSheetForcedHidden) {
                originalApplePlayerSheetState?.let(::setAppleMusicPlayerState)
            }
            applePlayerSheet = null
            applePlayerSheetBehavior = null
            originalApplePlayerSheetState = null
            applePlayerSheetForcedHidden = false
            applePlayerExpansionRequested = false
            applePlayerExpansionObserved = false
            applePlayerCollapsedTop = null
            appleArtworkSignature = null
            appleArtworkRetryTitle = ""
            appleArtworkRetryUntil = 0L
            lastAppleArtworkCaptureAt = 0L
        }

        private fun navigationOverlayHeight(): Int {
            val density = activity.resources.displayMetrics.density
            val baseHeightDp = when (NavigationStyle.fromPreference(navigationStyle)) {
                NavigationStyle.LIQUID_GLASS -> 78
                NavigationStyle.HYPER_OS -> 64
                // MIUIX 0.9.2 uses a 52dp minimum plus 26dp bottom spacing.
                NavigationStyle.HYPER_OS_FLOATING -> 78
            }
            val insets = activity.window.decorView.rootWindowInsets
            val navigationInset = when {
                insets == null -> 0
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                else -> @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            val miniPlayerVisible = when (config) {
                cloudMusic -> cloudMiniPlayerState.value.visible
                appleMusic -> appleMiniPlayerState.value.visible
                qqMusic -> qqMiniPlayerState.value.visible
                else -> false
            }
            val miniPlayerHeightDp = if (miniPlayerVisible) 72 else 0
            return ((baseHeightDp + miniPlayerHeightDp) * density).toInt() + navigationInset
        }

        private fun updateNavigationOverlayHeight() {
            val overlay = nav ?: return
            val targetHeight = navigationOverlayHeight()
            val params = overlay.layoutParams ?: return
            if (targetHeight > 0 && params.height != targetHeight) {
                params.height = targetHeight
                overlay.layoutParams = params
            }
        }

        private fun adaptMiniPlayer() {
            if (config.miniPlayerResourceName == null) return
            if (config === cloudMusic || config === appleMusic) return
            val player = miniPlayer ?: return
            val translationTarget = miniPlayerTranslationTarget ?: player
            val overlay = nav ?: return
            if (!player.isAttachedToWindow || !overlay.isAttachedToWindow ||
                player.height <= 0 || overlay.height <= 0
            ) return

            val playerLocation = IntArray(2).also(player::getLocationInWindow)
            val overlayLocation = IntArray(2).also(overlay::getLocationInWindow)
            val baseBottom = playerLocation[1] + player.height - appliedMiniPlayerOffset
            val density = activity.resources.displayMetrics.density
            val visualTopAdjustment = when (NavigationStyle.fromPreference(navigationStyle)) {
                NavigationStyle.LIQUID_GLASS,
                NavigationStyle.HYPER_OS_FLOATING -> -12f * density
                NavigationStyle.HYPER_OS -> -8f * density
            }
            val targetOffset = (overlayLocation[1] + visualTopAdjustment - baseBottom)
                .coerceAtMost(0f)
            val targetTranslation = originalMiniPlayerTargetTranslationY + targetOffset
            if (kotlin.math.abs(translationTarget.translationY - targetTranslation) >= 0.5f) {
                translationTarget.translationY = targetTranslation
            }
            appliedMiniPlayerOffset = targetOffset
        }

        private fun updateMiniPlayerTranslationTarget(player: View) {
            val target = if (config === qqMusic) {
                generateSequence(player.parent as? View) { it.parent as? View }
                    .firstOrNull { view ->
                        view.id != View.NO_ID && runCatching {
                            view.resources.getResourceEntryName(view.id) == "fya"
                        }.getOrDefault(false)
                    }
            } else {
                null
            } ?: player
            miniPlayerTranslationTarget = target
            originalMiniPlayerTargetTranslationY = target.translationY
        }

        private fun restoreMiniPlayer() {
            resetMiniPlayerPosition()
            miniPlayer?.alpha = originalMiniPlayerAlpha
            miniPlayer = null
            miniPlayerTranslationTarget = null
            cloudMiniPlayerState.value = MusicMiniPlayerState()
            appleMiniPlayerState.value = MusicMiniPlayerState()
            qqMiniPlayerState.value = MusicMiniPlayerState()
        }

        private fun resetMiniPlayerPosition() {
            miniPlayer?.translationY = originalMiniPlayerTranslationY
            miniPlayerTranslationTarget?.translationY = originalMiniPlayerTargetTranslationY
            appliedMiniPlayerOffset = 0f
        }

        private fun detectBottomBar(content: ViewGroup): DetectedBar? {
            if (config === xiaomiWallet) return detectWalletBottomBar(content)
            resolveStableBar(content)?.let { candidate ->
                if (config.coordinateTabSurface) {
                    val order = config.fallbackTabOrder ?: return@let
                    return DetectedBar(
                        candidate,
                        order.map { key -> DetectedTab(key, displayLabel(key), candidate, candidate) }
                    )
                }
                parseTabs(candidate)?.let { return DetectedBar(candidate, it) }
            }

            val density = content.resources.displayMetrics.density
            val contentLocation = IntArray(2).also(content::getLocationInWindow)
            return descendants(content)
                .filterIsInstance<ViewGroup>()
                .mapNotNull { candidate ->
                    if (!candidate.isShown || candidate.width < content.width * 3 / 4 ||
                        candidate.height !in (36 * density).toInt()..(120 * density).toInt()
                    ) return@mapNotNull null
                    val location = IntArray(2).also(candidate::getLocationInWindow)
                    val distanceFromBottom = contentLocation[1] + content.height - location[1] - candidate.height
                    if (distanceFromBottom !in 0..(80 * density).toInt()) return@mapNotNull null
                    parseTabs(candidate)?.let { DetectedBar(candidate, it) }
                }
                .firstOrNull()
        }

        private fun detectWalletBottomBar(content: ViewGroup): DetectedBar? {
            val container = findResourceView(content, "flutter_container") as? ViewGroup ?: return null
            if (!isWalletMainSurfaceReady(container)) return null
            val order = config.fallbackTabOrder ?: return null
            val tabs = order.map { key ->
                DetectedTab(key, displayLabel(key), container, container)
            }
            return DetectedBar(container, tabs)
        }

        private fun isWalletMainSurfaceReady(container: View): Boolean {
            if (!container.isShown || container.width <= 0 || container.height <= 0) return false
            val splash = activity.findViewById<ViewGroup>(
                activity.resources.getIdentifier("splash_container", "id", config.packageName)
            )
            if (splash?.isShown == true) return false
            return descendants(container).any {
                it.javaClass.name == "io.flutter.embedding.android.FlutterView"
            }
        }

        private fun refreshDetectedBar(content: ViewGroup): DetectedBar? {
            val detected = detectBottomBar(content) ?: return null
            if (detected.container !== originalBar) {
                originalBar = detected.container
                rememberOriginalNavigationLayers(content, detected.container)
                config.navigationLayerResourceNames.forEach { rememberResourceView(content, it) }
            }
            detectedTabs = detected.tabs
            return detected
        }

        private fun resolveStableBar(content: ViewGroup): ViewGroup? {
            config.barResourceName?.let { name ->
                val id = content.resources.getIdentifier(name, "id", config.packageName)
                if (id != 0) return content.findViewById(id)
            }
            config.anchorResourceName?.let { name ->
                val id = content.resources.getIdentifier(name, "id", config.packageName)
                val anchor = if (id == 0) null else content.findViewById<View>(id)
                return anchor?.parent as? ViewGroup
            }
            return null
        }

        private fun parseTabs(candidate: ViewGroup): List<DetectedTab>? {
            parseDirectTabs(candidate)?.let { return it }
            return descendants(candidate)
                .filterIsInstance<ViewGroup>()
                .filter { it !== candidate }
                .mapNotNull(::parseDirectTabs)
                .firstOrNull()
        }

        private fun parseDirectTabs(candidate: ViewGroup): List<DetectedTab>? {
            val tabs = (0 until candidate.childCount).mapNotNull { index ->
                val child = candidate.getChildAt(index)
                val key = canonicalTab(semanticText(child)) ?: return@mapNotNull null
                val clickTarget = findClickable(child) ?: return@mapNotNull null
                DetectedTab(key, displayLabel(key, semanticText(child)), child, clickTarget)
            }.distinctBy { it.key }
            if (tabs.mapTo(mutableSetOf()) { it.key } == config.requiredTabs) {
                return tabs.sortedBy { IntArray(2).also(it.view::getLocationInWindow)[0] }
            }

            val fallbackOrder = config.fallbackTabOrder ?: return null
            if (candidate.childCount != fallbackOrder.size) return null
            return fallbackOrder.mapIndexed { index, key ->
                val child = candidate.getChildAt(index)
                val clickTarget = findClickable(child) ?: return null
                DetectedTab(key, displayLabel(key, semanticText(child)), child, clickTarget)
            }
        }

        private fun semanticText(view: View): String {
            val values = mutableListOf<String>()
            view.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(values::add)
            if (view.id != View.NO_ID) {
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()?.let(values::add)
            }
            if (view is TextView) {
                view.text?.toString()?.trim()?.takeIf {
                    it.isNotEmpty() && !it.matches(Regex("(?:[1-9]\\d?|99\\+)"))
                }?.let(values::add)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) values += semanticText(view.getChildAt(index))
            }
            return values.joinToString(" ")
        }

        private fun canonicalTab(value: String): TabKey? = when {
            value.contains("\u9996\u9875") || value.contains("home", ignoreCase = true) -> TabKey.HOME
            value.contains("\u5206\u7c7b") || value.contains("category", ignoreCase = true) -> TabKey.CATEGORY
            value.contains("\u5e02\u96c6") || value.contains("store", ignoreCase = true) -> TabKey.MARKET
            value.contains("\u53d1\u5e03") || value.contains("publish", ignoreCase = true) -> TabKey.PUBLISH
            value.contains("\u6d88\u606f") || value.contains("message", ignoreCase = true) -> TabKey.MESSAGES
            value.contains("\u8054\u7cfb\u4eba") || value.contains("contact", ignoreCase = true) -> TabKey.CONTACTS
            value.contains("\u5173\u6ce8") || value.contains("follow", ignoreCase = true) -> TabKey.FOLLOWING
            value.contains("\u7acb\u5373\u8046\u542c") || value.contains("listen_now", ignoreCase = true) ||
                value.contains("listen now", ignoreCase = true) -> TabKey.LISTEN_NOW
            value.contains("\u6d4f\u89c8") || value.contains("browse", ignoreCase = true) -> TabKey.BROWSE
            value.contains("\u5e7f\u64ad") || value.contains("radio", ignoreCase = true) -> TabKey.RADIO
            value.contains("\u8d44\u6599\u5e93") || value.contains("library", ignoreCase = true) -> TabKey.LIBRARY
            value.contains("\u793e\u533a") || value.contains("communities", ignoreCase = true) -> TabKey.COMMUNITIES
            value.contains("\u804a\u5929") || value.contains("chat", ignoreCase = true) -> TabKey.CHAT
            value.contains("\u6536\u4ef6\u7bb1") || value.contains("inbox", ignoreCase = true) -> TabKey.INBOX
            value.contains("\u89c6\u9891") || value.contains("video", ignoreCase = true) -> TabKey.VIDEO
            config === appleMusic && (value.contains("\u641c\u7d22") ||
                value.contains("search", ignoreCase = true)) -> TabKey.MUSIC_SEARCH
            value.contains("\u96f7\u8fbe") || value.contains("\u641c\u7d22") ||
                value.contains("radar", ignoreCase = true) ||
                value.contains("search", ignoreCase = true) -> TabKey.MUSIC_DISCOVER
            value.contains("\u661f\u5149") || value.contains("starlight", ignoreCase = true) -> TabKey.STARLIGHT
            value.contains("\u53d1\u73b0") || value.contains("discover", ignoreCase = true) -> TabKey.DISCOVER
            value.contains("\u52a8\u6001") || value.contains("dynamic", ignoreCase = true) -> TabKey.DYNAMIC
            value.contains("\u670d\u52a1") || value.contains("service", ignoreCase = true) -> TabKey.SERVICE
            value.contains("\u8d2d\u7269\u8f66") || value.contains("cart", ignoreCase = true) -> TabKey.CART
            value.contains("\u7701\u94b1") || value.contains("savings", ignoreCase = true) -> TabKey.SAVINGS
            value.contains("\u77ed\u5267") || value.contains("short drama", ignoreCase = true) -> TabKey.SHORT_DRAMA
            value.contains("\u501f\u94b1") || value.contains("loan", ignoreCase = true) -> TabKey.LOAN
            value.contains("\u6211\u7684") || containsToken(value, "\u6211") ||
                value.contains("profile", ignoreCase = true) || value.contains("mine", ignoreCase = true) -> TabKey.PROFILE
            else -> null
        }

        private fun containsToken(value: String, candidate: String): Boolean =
            value.splitToSequence(' ', '\uFF0C', ',').any { it == candidate }

        private fun displayLabel(key: TabKey, semanticValue: String = ""): String = when (key) {
            TabKey.HOME -> "\u9996\u9875"
            TabKey.CATEGORY -> "\u5206\u7c7b"
            TabKey.MARKET -> "\u5e02\u96c6"
            TabKey.PUBLISH -> "+"
            TabKey.MESSAGES -> "\u6d88\u606f"
            TabKey.CONTACTS -> "\u8054\u7cfb\u4eba"
            TabKey.FOLLOWING -> "\u5173\u6ce8"
            TabKey.LISTEN_NOW -> "\u7acb\u5373\u8046\u542c"
            TabKey.BROWSE -> "\u6d4f\u89c8"
            TabKey.RADIO -> "\u5e7f\u64ad"
            TabKey.LIBRARY -> "\u8d44\u6599\u5e93"
            TabKey.MUSIC_SEARCH -> "\u641c\u7d22"
            TabKey.COMMUNITIES -> "\u793e\u533a"
            TabKey.CHAT -> "\u804a\u5929"
            TabKey.INBOX -> "\u6536\u4ef6\u7bb1"
            TabKey.VIDEO -> "\u89c6\u9891"
            TabKey.MUSIC_DISCOVER -> if (semanticValue.contains("\u96f7\u8fbe")) {
                "\u96f7\u8fbe"
            } else {
                "\u641c\u7d22"
            }
            TabKey.STARLIGHT -> "\u661f\u5149"
            TabKey.DISCOVER -> "\u53d1\u73b0"
            TabKey.DYNAMIC -> "\u52a8\u6001"
            TabKey.SERVICE -> "\u670d\u52a1"
            TabKey.CART -> "\u8d2d\u7269\u8f66"
            TabKey.SAVINGS -> "\u7701\u94b1"
            TabKey.SHORT_DRAMA -> "\u77ed\u5267"
            TabKey.LOAN -> "\u501f\u94b1"
            TabKey.PROFILE -> "\u6211\u7684"
        }

        private fun extractedTabIcon(key: TabKey) = when (config) {
            xiaohongshu -> ExtractedNavIcons.forTab("xiaohongshu", key.name)
            qqMusic -> ExtractedNavIcons.forTab("qqmusic", key.name)
            weibo -> ExtractedNavIcons.forTab("weibo", key.name)
            reddit -> ExtractedNavIcons.forTab("reddit", key.name)
            else -> null
        }

        private fun findClickable(view: View): View? {
            if (view.isClickable) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findClickable(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }

        private fun isSelectedRecursively(view: View): Boolean {
            if (view.isSelected || view.isActivated || (view is Checkable && view.isChecked)) return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (isSelectedRecursively(view.getChildAt(index))) return true
                }
            }
            return false
        }

        private fun descendants(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) {
                for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
            }
        }

        private fun configureSystemNavigationBar() {
            if (originalNavigationBarColor != null) return
            val window = activity.window
            originalNavigationBarColor = window.navigationBarColor
            originalSystemUiVisibility = window.decorView.systemUiVisibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                originalNavigationBarContrastEnforced = window.isNavigationBarContrastEnforced
            }
            originallyDrawsSystemBarBackgrounds =
                window.attributes.flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS != 0
            ImmersiveNavigationHooks.activate(activity)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }

            val originalFlags = originalSystemUiVisibility ?: 0
            val edgeToEdgeFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val isLightTheme = activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.decorView.systemUiVisibility = if (isLightTheme) {
                originalFlags or edgeToEdgeFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                (originalFlags or edgeToEdgeFlags) and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }

        private fun restoreSystemNavigationBar() {
            val originalColor = originalNavigationBarColor ?: return
            val window = activity.window
            window.navigationBarColor = originalColor
            originalNavigationBarContrastEnforced?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = it
            }
            originalSystemUiVisibility?.let { window.decorView.systemUiVisibility = it }
            val originallyEdgeToEdge = originalSystemUiVisibility?.let { flags ->
                flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0 ||
                    flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0
            } == true
            WindowCompat.setDecorFitsSystemWindows(window, !originallyEdgeToEdge)
            if (!originallyDrawsSystemBarBackgrounds) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
            originalNavigationBarColor = null
            originalNavigationBarContrastEnforced = null
            originalSystemUiVisibility = null
            originallyDrawsSystemBarBackgrounds = false
        }
    }

    private enum class TabKey {
        HOME, CATEGORY, MARKET, PUBLISH, MESSAGES, CONTACTS, FOLLOWING, VIDEO, MUSIC_DISCOVER, STARLIGHT,
        DISCOVER, DYNAMIC, SERVICE, CART, SAVINGS, SHORT_DRAMA, LOAN, PROFILE, LISTEN_NOW, BROWSE, RADIO,
        LIBRARY, MUSIC_SEARCH, COMMUNITIES, CHAT, INBOX
    }

    private data class AppConfig(
        val displayName: String,
        val packageName: String,
        val activityName: String,
        val componentPrefix: String? = null,
        val barResourceName: String?,
        val anchorResourceName: String?,
        val requiredTabs: Set<TabKey>,
        val targetVersion: String,
        val navigationLayerResourceNames: Set<String> = emptySet(),
        val miniPlayerResourceName: String? = null,
        val fallbackTabOrder: List<TabKey>? = null,
        val coordinateTabSurface: Boolean = false,
        val concealHostBottomBar: Boolean = false
    )

    private data class DetectedBar(val container: ViewGroup, val tabs: List<DetectedTab>)

    private data class DetectedTab(
        val key: TabKey,
        val displayLabel: String,
        val view: View,
        val clickTarget: View
    )

    private data class OriginalViewState(
        val alpha: Float,
        val willNotDraw: Boolean,
        val layoutHeight: Int
    )

    private data class RelativeBottomRules(
        val above: Int,
        val alignParentBottom: Int
    )

    private data class ClipState(
        val clipChildren: Boolean,
        val clipToPadding: Boolean
    )
}
