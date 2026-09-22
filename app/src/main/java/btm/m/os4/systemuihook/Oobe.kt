package btm.m.os4.systemuihook

import android.content.Context
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.state.ToggleableState
import btm.m.liquidglass.hook.DampedDragAnimation
import btm.m.liquidglass.hook.InteractiveHighlight
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private const val BRAND_BLUE = 0xFF0088FF
private const val OOBE_PREDICTIVE_BACK_MAX_PROGRESS = .8f

private enum class OobePage { WELCOME, PREPARATION, AGREEMENTS, COMPLETE }
internal enum class LegalPage { AGREEMENT, DISCLAIMER, PRIVACY }
private data class OobeDestination(val page: OobePage, val legalPage: LegalPage? = null)

@Composable
internal fun OobeFlow(
    serviceConnected: Boolean,
    onLanguageChanged: () -> Unit,
    onFinished: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(OobePage.WELCOME) }
    var completionVisit by remember { mutableIntStateOf(0) }
    var legalPage by remember { mutableStateOf<LegalPage?>(null) }
    var agreementsAccepted by remember { mutableStateOf(false) }
    var captchaVerified by remember { mutableStateOf(false) }
    var showCaptcha by remember { mutableStateOf(false) }
    val captcha = remember { (100000..999999).random().toString() }
    val background = if (MiuixTheme.colorScheme.background.luminance() < .5f) Color.Black else MiuixTheme.colorScheme.surface
    val go: (OobePage) -> Unit = { next ->
        if (next == OobePage.COMPLETE && page != OobePage.COMPLETE) completionVisit++
        page = next
    }
    val canNavigateBack = page != OobePage.WELCOME || legalPage != null
    val navigateBack: () -> Unit = {
        if (legalPage != null) legalPage = null else go(OobePage.entries[page.ordinal - 1])
    }
    val renderDestination: @Composable (OobeDestination) -> Unit = { destination ->
        destination.legalPage?.let { legal ->
            LegalDocumentPage(legal, captcha) { legalPage = null }
        } ?: when (destination.page) {
            OobePage.WELCOME -> WelcomePage { go(OobePage.PREPARATION) }
            OobePage.PREPARATION -> PreparationPage(serviceConnected, { go(OobePage.WELCOME) }, onLanguageChanged) { go(OobePage.AGREEMENTS) }
            OobePage.AGREEMENTS -> AgreementsPage(
                accepted = agreementsAccepted,
                captchaVerified = captchaVerified,
                captcha = captcha,
                showCaptcha = showCaptcha,
                back = { go(OobePage.PREPARATION) },
                open = { legalPage = it },
                requestVerification = { showCaptcha = true },
                dismissVerification = { showCaptcha = false },
                verificationConfirmed = { captchaVerified = true; showCaptcha = false },
                toggleAcceptance = { agreementsAccepted = !agreementsAccepted },
                next = { go(OobePage.COMPLETE) },
            )
            OobePage.COMPLETE -> CompletePage(completionVisit, { go(OobePage.AGREEMENTS) }, onFinished)
        }
    }
    val currentDestination = OobeDestination(page, legalPage)
    val predictiveProgress = remember { Animatable(0f) }
    var predictivePreview by remember { mutableStateOf<OobeDestination?>(null) }
    var predictiveSwipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var predictiveGestureActive by remember { mutableStateOf(false) }
    var predictiveCommit by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = canNavigateBack) { events ->
        predictivePreview = if (legalPage != null) {
            OobeDestination(page)
        } else {
            OobeDestination(OobePage.entries[page.ordinal - 1])
        }
        predictiveGestureActive = true
        var committed = false
        try {
            events.collect { event ->
                predictiveSwipeEdge = event.swipeEdge
                predictiveProgress.snapTo(
                    event.progress.coerceAtMost(OOBE_PREDICTIVE_BACK_MAX_PROGRESS),
                )
            }
            val completionDuration = ((1f - predictiveProgress.value) * 240f).toInt().coerceAtLeast(1)
            predictiveProgress.animateTo(1f, tween(completionDuration, easing = FastOutSlowInEasing))
            predictiveCommit = true
            committed = true
            navigateBack()
            withFrameNanos { }
        } catch (_: CancellationException) {
            predictiveProgress.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
        } finally {
            predictiveGestureActive = false
            predictivePreview = null
            predictiveProgress.snapTo(0f)
            if (committed) {
                withFrameNanos { }
                predictiveCommit = false
            }
        }
    }
    Box(Modifier.fillMaxSize().background(background)) {
        val progress = predictiveProgress.value.coerceIn(0f, 1f)
        val swipeDirection = if (predictiveSwipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
        if (predictiveGestureActive) {
            predictivePreview?.let { preview ->
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationX = -swipeDirection * size.width * (1f - progress) / 7f
                        scaleX = .975f + .025f * progress
                        scaleY = scaleX
                        alpha = progress
                    },
                ) {
                    renderDestination(preview)
                }
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = swipeDirection * size.width * progress / 3f
                scaleX = 1f - .025f * progress
                scaleY = scaleX
                alpha = 1f - progress
            },
        ) {
            AnimatedContent(
                targetState = currentDestination,
                transitionSpec = {
                    if (predictiveCommit) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val forward = when {
                            targetState.legalPage != null && initialState.legalPage == null -> true
                            targetState.legalPage == null && initialState.legalPage != null -> false
                            else -> targetState.page.ordinal >= initialState.page.ordinal
                        }
                        if (forward) {
                            (slideInHorizontally(
                                animationSpec = tween(560, easing = FastOutSlowInEasing),
                                initialOffsetX = { it / 7 },
                            ) + fadeIn(tween(420)) + scaleIn(
                                animationSpec = tween(560, easing = FastOutSlowInEasing),
                                initialScale = .985f,
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(460, easing = FastOutSlowInEasing),
                                targetOffsetX = { -it / 3 },
                            ) + fadeOut(tween(360)) + scaleOut(
                                animationSpec = tween(460, easing = FastOutSlowInEasing),
                                targetScale = .975f,
                            ))
                        } else {
                            (slideInHorizontally(
                                animationSpec = tween(520, easing = FastOutSlowInEasing),
                                initialOffsetX = { -it / 3 },
                            ) + fadeIn(tween(380)) + scaleIn(
                                animationSpec = tween(520, easing = FastOutSlowInEasing),
                                initialScale = .975f,
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                                targetOffsetX = { it / 7 },
                            ) + fadeOut(tween(320)) + scaleOut(
                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                                targetScale = .985f,
                            ))
                        }
                    }
                },
                label = "oobeNavigation",
                modifier = Modifier.fillMaxSize().background(background),
            ) { destination ->
                renderDestination(destination)
            }
        }
    }
}

@Composable
private fun OobeFrame(back: (() -> Unit)? = null, title: String? = null, bottom: (@Composable () -> Unit)? = null, backgroundBackdrop: LayerBackdrop? = null, content: @Composable ColumnScope.() -> Unit) {
    val background = if (MiuixTheme.colorScheme.background.luminance() < .5f) Color.Black else MiuixTheme.colorScheme.surface
    Scaffold(containerColor = background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize().background(background).then(if (backgroundBackdrop != null) Modifier.layerBackdrop(backgroundBackdrop) else Modifier))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 28.dp)) {
                Row(Modifier.fillMaxWidth().height(76.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (back != null) Image(MiuixIcons.Regular.ChevronBackward, tr("返回", "返回"), Modifier.size(38.dp).clickable(onClick = back), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground))
                }
                if (title != null) Text(title, modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 42.dp), textAlign = TextAlign.Center, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                bottom?.let { Box(Modifier.fillMaxWidth().padding(bottom = 18.dp)) { it() } }
            }
        }
    }
}

@Composable
private fun WelcomePage(next: () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount ->
                updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f))
            },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    OobeFrame(backgroundBackdrop = backdrop) {
    Spacer(Modifier.weight(.37f))
    Text(tr("欢迎使用", "欢迎使用"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
    Text("HyperChanger", color = Color(BRAND_BLUE), fontSize = 39.sp, fontWeight = FontWeight.Bold)
    Text(tr("一个开源的\n面向 HyperOS 4 的\nLSPosed 模块", "一个开源的\n面向 HyperOS 4 的\nLSPosed 模块"), modifier = Modifier.padding(top = 25.dp), fontSize = 27.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
    Box(
        Modifier.padding(top = 40.dp).size(65.dp)
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .clip(CircleShape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = {
                    vibrancy()
                    blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                    toolbarGlassLens(drag.pressProgress)
                },
                highlight = { toolbarGlassHighlight(drag.pressProgress) },
                shadow = { Shadow.Default.copy(radius = 5.dp, color = Color.Black, alpha = .24f) },
                innerShadow = null,
                layerBlock = {
                    scaleX = drag.scaleX
                    scaleY = drag.scaleY
                    val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                    scaleX *= 1f + velocity * .12f
                    scaleY /= 1f + velocity * .08f
                    translationX = drag.value * 5.dp.toPx()
                },
                onDrawSurface = { drawCircle(Color(BRAND_BLUE).copy(alpha = .92f)) },
            )
            .then(highlight.modifier)
            .clickable(interactionSource = null, indication = null, onClick = next),
        contentAlignment = Alignment.Center,
    ) {
        Image(MiuixIcons.Regular.ChevronForward, tr("继续", "继续"), Modifier.size(34.dp), colorFilter = ColorFilter.tint(Color.White))
    }
    Spacer(Modifier.weight(.63f))
    }
}

@Composable
private fun PreparationPage(serviceConnected: Boolean, back: () -> Unit, onLanguageChanged: () -> Unit, next: () -> Unit) {
    val context = LocalContext.current
    val os = remember { OsCompatibility.versionCode() }
    val android = remember { Build.VERSION.RELEASE.substringBefore('.').toIntOrNull() }
    var rootGranted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { rootGranted = withContext(Dispatchers.IO) { runCatching { ProcessBuilder("su", "-c", "id").start().waitFor() == 0 }.getOrDefault(false) } }
    val canContinue = os.isNotBlank() && android != null && android > 15
    AppPage(
        title = "",
        onBack = back,
        navigationIcon = { OobeBackButton(back) },
        compactTopBar = true,
        floatingToolbar = { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { OobeButton(tr("继续", "继续"), canContinue, next) } },
    ) { padding, scroll ->
        AppList(padding, scroll, 104) {
            item { OobeSectionTitle(tr("模块预备工作", "模块预备工作")) }
            item { StatusCard(tr("HyperOS 版本", "HyperOS 版本"), os.ifBlank { tr("未检测到", "未检测到") }, when { os.isBlank() -> -1; os == "4" -> 1; else -> 2 }) }
            item { StatusCard(tr("Android 版本", "Android 版本"), android?.let { "$it / API ${Build.VERSION.SDK_INT}" } ?: tr("未检测到", "未检测到"), when { android == null || android <= 15 -> -1; android == 17 -> 1; else -> 2 }) }
            item { StatusCard(tr("在 LSPosed 管理器中激活模块", "在 LSPosed 管理器中激活模块"), null, if (serviceConnected) 1 else 2) }
            item { StatusCard(tr("给予模块超级用户权限", "给予模块超级用户权限"), null, if (rootGranted) 1 else 2) }
            item { LanguageCard(context, onLanguageChanged) }
        }
    }
}

@Composable
private fun StatusCard(title: String, summary: String?, status: Int) {
    Card(
        Modifier.fillMaxWidth().then(if (summary == null) Modifier.height(64.dp) else Modifier),
        cornerRadius = 22.5.dp,
        insideMargin = if (summary == null) PaddingValues(0.dp) else PaddingValues(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (summary == null) Modifier.height(64.dp).padding(horizontal = 18.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                )
                summary?.let {
                    Text(
                        it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            when (status) {
                1 -> Image(MiuixIcons.Regular.Check, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(Color(BRAND_BLUE)))
                0 -> Image(painterResource(R.drawable.ic_oobe_question), null, Modifier.width(16.dp).height(28.dp))
                -1 -> Image(MiuixIcons.Regular.Close, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(Color(0xFFE53935)))
            }
        }
    }
}

@Composable
private fun LanguageCard(context: Context, changed: () -> Unit) {
    val prefs = remember { context.getSharedPreferences("languages", Context.MODE_PRIVATE) }
    val values = listOf("system", "zh", "en", "ja")
    val labels = listOf(tr("跟随系统", "跟随系统"), tr("中文", "中文"), "English", "日本語")
    var selected by remember { mutableIntStateOf(values.indexOf(prefs.getString("selected", "system")).coerceAtLeast(0)) }
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
        OverlayDropdownPreference(
            title = tr("语言", "语言"),
            items = labels,
            selectedIndex = selected,
            showValueOnEnd = true,
            onSelectedIndexChange = { index -> selected = index; prefs.edit().putString("selected", values[index]).apply(); changed() },
        )
    }
}

@Composable
private fun AgreementsPage(
    accepted: Boolean,
    captchaVerified: Boolean,
    captcha: String,
    showCaptcha: Boolean,
    back: () -> Unit,
    open: (LegalPage) -> Unit,
    requestVerification: () -> Unit,
    dismissVerification: () -> Unit,
    verificationConfirmed: () -> Unit,
    toggleAcceptance: () -> Unit,
    next: () -> Unit,
) {
    AppPage(
        title = "",
        onBack = back,
        navigationIcon = { OobeBackButton(back) },
        compactTopBar = true,
        floatingToolbar = { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { OobeButton(tr("继续", "继续"), accepted, next) } },
    ) { padding, scroll ->
        AppList(padding, scroll, 104) {
            item { OobeSectionTitle(tr("协议与声明", "协议与声明")) }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    ArrowPreference(title = tr("用户协议", "用户协议"), onClick = { open(LegalPage.AGREEMENT) })
                    ArrowPreference(title = tr("免责声明", "免责声明"), onClick = { open(LegalPage.DISCLAIMER) })
                    ArrowPreference(title = tr("隐私政策", "隐私政策"), onClick = { open(LegalPage.PRIVACY) })
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    ArrowPreference(
                        title = tr("输入动态验证码", "输入动态验证码"),
                        summary = if (captchaVerified) tr("验证完成", "验证完成") else tr("验证后即可勾选协议", "验证后即可勾选协议"),
                        onClick = requestVerification,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(state = if (accepted) ToggleableState.On else ToggleableState.Off, onClick = if (captchaVerified) toggleAcceptance else null, enabled = captchaVerified)
                    Text(
                        tr("我已阅读并同意《用户协议》《免责声明》及《隐私政策》，知晓本模块仅适用于 HyperOS 4，了解在非目标系统运行引发的崩溃与无法开机等风险，自愿承担相应责任。", "我已阅读并同意《用户协议》《免责声明》及《隐私政策》，知晓本模块仅适用于 HyperOS 4，了解在非目标系统运行引发的崩溃与无法开机等风险，自愿承担相应责任。"),
                        Modifier.padding(start = 12.dp),
                        style = MiuixTheme.textStyles.body2,
                        fontSize = 14.7.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = if (captchaVerified) 1f else .45f),
                    )
                }
            }
        }
        CaptchaDialog(showCaptcha, captcha, dismissVerification, verificationConfirmed)
    }
}

@Composable
private fun OobeSectionTitle(title: String) {
    Text(title, modifier = Modifier.fillMaxWidth().padding(top = 52.dp, bottom = 42.dp), textAlign = TextAlign.Center, fontSize = 32.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CaptchaDialog(show: Boolean, captcha: String, dismiss: () -> Unit, confirmed: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(show) { if (show) input = TextFieldValue("") }
    WindowDialog(
        show = show,
        onDismissRequest = dismiss,
        imeBottomSafetyFraction = .175f,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tr("动态验证码", "动态验证码"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(tr("请输入动态验证码以确认你已阅读并理解协议。", "请输入动态验证码以确认你已阅读并理解协议。"), style = MiuixTheme.textStyles.body1)
            TextField(
                value = input,
                onValueChange = { value ->
                    val digits = value.text.filter(Char::isDigit).take(6)
                    input = TextFieldValue(digits, TextRange(digits.length))
                },
                label = tr("动态验证码", "动态验证码"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = confirmed, modifier = Modifier.weight(1f), enabled = input.text == captcha, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("确认", "确认")) }
                GlassDialogButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            }
        }
    }
}

@Composable
internal fun LegalDocumentPage(
    page: LegalPage,
    captcha: String,
    showVerificationCode: Boolean = true,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val title = when (page) { LegalPage.AGREEMENT -> tr("用户协议", "用户协议"); LegalPage.DISCLAIMER -> tr("免责声明", "免责声明"); LegalPage.PRIVACY -> tr("隐私政策", "隐私政策") }
    val assetName = when (page) { LegalPage.AGREEMENT -> "user_agreement.txt"; LegalPage.DISCLAIMER -> "disclaimer.txt"; LegalPage.PRIVACY -> "privacy.txt" }
    val fallback = remember(assetName) { context.assets.open("legal/zh/$assetName").bufferedReader().use { it.readText() } }
    val content = when (page) { LegalPage.AGREEMENT -> tr("oobe_user_agreement_content", fallback); LegalPage.DISCLAIMER -> tr("oobe_disclaimer_content", fallback); LegalPage.PRIVACY -> tr("oobe_privacy_content", fallback) }
    AppPage(title, back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
                    LegalDocumentText(content)
                    if (showVerificationCode) {
                        Text(tr("本次验证码：", "本次验证码：") + captcha, color = Color(BRAND_BLUE), modifier = Modifier.padding(top = 24.dp), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                        Text(tr("阅读完成后返回上一页输入此验证码。", "阅读完成后返回上一页输入此验证码。"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 4.dp), style = MiuixTheme.textStyles.body2)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegalDocumentText(content: String) {
    val lines = content.lines()
    Column(Modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(10.dp))
                index == 0 -> Text(line, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                line.startsWith("生效日期") || line.startsWith("最近修订") -> Text(line, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Regex("^[一二三四五六七八九十]+、").containsMatchIn(line) -> Text(line, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Regex("^\\d+\\.\\d+").containsMatchIn(line) -> {
                    val prefix = Regex("^\\d+\\.\\d+").find(line)?.value.orEmpty()
                    Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(prefix) }; append(line.removePrefix(prefix)) }, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(bottom = 6.dp))
                }
                else -> Text(line, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun CompletePage(confettiKey: Int, back: () -> Unit, finish: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AppPage(
            title = "",
            onBack = back,
            navigationIcon = { OobeBackButton(back) },
            compactTopBar = true,
            floatingToolbar = {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    OobeButton(tr("开始使用", "开始使用"), true, finish)
                }
            },
        ) { padding, _ ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp)) {
                Spacer(Modifier.weight(.32f))
                Text(tr("设置完成！", "设置完成！"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(54.dp))
                Text(tr("欢迎使用", "欢迎使用"), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("HyperChanger", color = Color(BRAND_BLUE), fontSize = 39.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(.68f))
            }
        }
        CompletionConfetti(confettiKey, Modifier.fillMaxSize())
    }
}

private data class ConfettiPiece(
    val xFraction: Float,
    val delay: Float,
    val drift: Float,
    val frequency: Float,
    val phase: Float,
    val width: Float,
    val height: Float,
    val rotation: Float,
    val rotationTurns: Float,
    val color: Color,
    val round: Boolean,
    val restingProgress: Float?,
)

@Composable
private fun CompletionConfetti(playKey: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val pieces = remember(playKey) {
        val random = Random(System.nanoTime())
        val count = 54
        val restingCount = random.nextInt(1, 4)
        val palette = listOf(
            Color(0xFFFF3B30),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF00A7FF),
            Color(0xFF5856D6),
            Color(0xFFFF2D8D),
        )
        List(count) { index ->
            ConfettiPiece(
                xFraction = random.nextFloat(),
                delay = random.nextFloat() * .3f,
                drift = 18f + random.nextFloat() * 54f,
                frequency = 1.5f + random.nextFloat() * 2.5f,
                phase = random.nextFloat() * 6.28318f,
                width = 5f + random.nextFloat() * 6f,
                height = 9f + random.nextFloat() * 9f,
                rotation = random.nextFloat() * 360f,
                rotationTurns = 1.5f + random.nextFloat() * 3.5f,
                color = palette[random.nextInt(palette.size)],
                round = random.nextInt(5) == 0,
                restingProgress = if (index < restingCount) .52f + random.nextFloat() * .2f else null,
            )
        }
    }
    LaunchedEffect(playKey) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(3_800, easing = LinearEasing))
    }
    Canvas(modifier) {
        val timeline = progress.value
        val overlayAlpha = when {
            timeline < .08f -> timeline / .08f
            timeline > .82f -> ((1f - timeline) / .18f).coerceIn(0f, 1f)
            else -> 1f
        }
        pieces.forEach { piece ->
            val localProgress = ((timeline - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (localProgress <= 0f || overlayAlpha <= 0f) return@forEach
            val fallProgress = piece.restingProgress?.let { localProgress.coerceAtMost(it) } ?: localProgress
            val x = size.width * piece.xFraction +
                sin(fallProgress * piece.frequency * 6.28318f + piece.phase) * piece.drift.dp.toPx()
            val pieceHeight = piece.height.dp.toPx()
            val y = -pieceHeight + (size.height + pieceHeight * 2f) * fallProgress
            val alpha = overlayAlpha * (localProgress * 9f).coerceAtMost(1f)
            rotate(
                degrees = piece.rotation + localProgress * 360f * piece.rotationTurns,
                pivot = Offset(x, y),
            ) {
                if (piece.round) {
                    drawCircle(piece.color.copy(alpha = alpha), radius = piece.width.dp.toPx() * .58f, center = Offset(x, y))
                } else {
                    val width = piece.width.dp.toPx()
                    drawRect(
                        color = piece.color.copy(alpha = alpha),
                        topLeft = Offset(x - width / 2f, y - pieceHeight / 2f),
                        size = Size(width, pieceHeight),
                    )
                }
            }
        }
    }
}

@Composable
private fun OobeBackButton(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(MiuixIcons.Regular.ChevronBackward, tr("返回", "返回"), Modifier.size(27.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface))
    }
}

@Composable
private fun OobeButton(text: String, enabled: Boolean, click: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) = UpdateToolbarButton(click, text, modifier.height(58.dp), primary = true, enabled = enabled)
