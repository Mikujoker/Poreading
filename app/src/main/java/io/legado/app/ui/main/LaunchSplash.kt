package io.legado.app.ui.main

import android.animation.ValueAnimator
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.image.cover.coverTitleTypeface
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 冷启动开屏动效：错落多边形聚拢 → 水墨吞没 → 阴阳旋出 → 品牌字浮现 → 墨点飞散。
 *
 * 三条约定的实现方式（改之前先读）：
 * - **只在真冷启动播**：进程内第一次 + 桌面图标入口 + 非配置重建才播；通知深链、返回前台、
 *   旋转重建都不播（见 [LaunchSplashGate]）。
 * - **一个 Canvas 画完**：几何图形在单位空间里 `remember` 一次，每帧只改变换与 alpha；
 *   逐帧读进度用 draw/graphicsLayer 阶段的状态读取，不触发重组（启动期正跑着 App 初始化）。
 * - **可跳过**：点一下或按返回都算跳过，动画立刻进入淡出，不等它播完。
 */
private const val SPLASH_DURATION_MILLIS = 2200
private const val SPLASH_FADE_MILLIS = 420
private const val SPLASH_SHARD_COUNT = 8

/** 单位半径在屏幕上的比例：所有元素尺寸都由它派生，因此小屏/大屏/平板比例一致。 */
private const val SPLASH_RAIL_RATIO = 0.20f

/** 构图中心的纵向位置（画面高度的比例）：画布与品牌字共用，否则字会落在屏幕下半部。 */
private const val SPLASH_CENTER_RATIO = 0.44f

/** 时间轴切点（总进度 0..1）。切点之间刻意留重叠：元素要互相“让位”，不能硬切。 */
private const val T_SHARD_OUT = 0.40f
private const val T_INK_IN = 0.20f
private const val T_INK_OUT = 0.52f
private const val T_TAIJI_IN = 0.42f
private const val T_TAIJI_OUT = 0.90f
private const val T_TEXT_IN = 0.60f
private const val T_PARTICLE_IN = 0.70f

/**
 * 开屏只在进程内第一次尝试时决定要不要播。
 *
 * `consumed` 是进程级状态：配置重建、热启动拿到的是同一个对象，因此不会重播；进程被回收后重建
 * 才是真冷启动，会重新播。
 */
private object LaunchSplashGate {
    private var consumed = false

    fun consume(savedStatePresent: Boolean, launchAction: String?): Boolean {
        if (consumed) return false
        consumed = true
        return !savedStatePresent && launchAction == Intent.ACTION_MAIN
    }
}

/**
 * 开屏宿主：挂在应用根布局最上层，自己决定要不要播、播完自己摘掉。
 */
@Composable
fun LaunchSplashHost(savedStatePresent: Boolean, launchAction: String?) {
    var visible by remember {
        mutableStateOf(LaunchSplashGate.consume(savedStatePresent, launchAction))
    }
    if (visible) {
        LaunchSplashOverlay(onFinished = { visible = false })
    }
}

@Composable
private fun LaunchSplashOverlay(onFinished: () -> Unit) {
    // 系统关掉了动画（开发者选项 / 省电模式）时不要用一张静止画面挡住启动。
    if (!remember { ValueAnimator.areAnimatorsEnabled() }) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val appName = stringResource(R.string.splash_brand).trim()
    val geometry = rememberLaunchSplashGeometry()
    // 与 SyncWindowBackground 的窗口底色同源：淡出时不能和界面之间出现色缝。
    val paper = if (ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)) {
        MiuixTheme.colorScheme.surface
    } else {
        LegadoTheme.colorScheme.background
    }
    val ink = LegadoTheme.colorScheme.onSurface
    val accent = LegadoTheme.colorScheme.primary
    // 品牌字用书封那套笔体（ZhiMangXing 等），与默认书封同一查找链；找不到时回退 serif。
    val brandStyle = LegadoTheme.typography.titleLarge.copy(
        fontFamily = remember { FontFamily(coverTitleTypeface) },
        fontSize = 78.sp,
        letterSpacing = 6.sp,
        // 墨韵：字背后一层极淡的洇开（不是位移），让笔体不像“贴上去的数字字形”
        shadow = Shadow(
            color = ink.copy(alpha = 0.32f),
            offset = Offset.Zero,
            blurRadius = 12f,
        ),
    )

    val progress = remember { Animatable(0f) }
    val splashProgress = progress.asState()
    var finished by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(
        targetValue = if (finished) 0f else 1f,
        animationSpec = tween(SPLASH_FADE_MILLIS),
        label = "launchSplashFade",
    )

    LaunchedEffect(Unit) {
        // 冷启动头几帧是 App 初始化最重的时候（实测 99th 帧 200-300ms），
        // 直接开时间轴就会“卡一下”；先让过首帧再起，起步就不砸在解刷上。
        withFrameNanos { }
        withFrameNanos { }
        delay(120)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(SPLASH_DURATION_MILLIS, easing = LinearEasing),
        )
        finished = true
    }
    LaunchedEffect(finished) {
        if (finished) {
            delay(SPLASH_FADE_MILLIS.toLong() + 40L)
            onFinished()
        }
    }

    val skip = { finished = true }
    BackHandler(enabled = !finished, onBack = skip)

    // 整屏 offscreen 层只在淡出时需要；主段动画期间挂 alpha 层 = 每帧多一次全屏离屏渲染
    // （gfxinfo 实测：去掉后 UI 线程的慢帧明显下降）。
    val fadeModifier = if (finished) {
        Modifier.graphicsLayer { alpha = fade }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(fadeModifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = skip,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(paper),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val rail = minOf(maxWidth, maxHeight) * SPLASH_RAIL_RATIO
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawLaunchSplash(
                    progress = splashProgress.value,
                    geometry = geometry,
                    rail = rail.toPx(),
                    ink = ink,
                    paper = paper,
                    accent = accent,
                )
            }
            Text(
                text = appName,
                color = ink,
                style = brandStyle,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = maxHeight * (SPLASH_CENTER_RATIO - 0.5f) + rail * 1.5f)
                    .graphicsLayer {
                        // 落笔 = 淡入 + 微涨开 + 微斜；落定后**不动**（用户要的是字“飘逸”，不是位移）
                        val appear = smoothPhase(
                            progress = splashProgress.value,
                            delay = T_TEXT_IN,
                            span = 0.26f,
                        )
                        alpha = appear
                        // 横着拉长（用户：“最好横着拉长，现在竖着挤在一起”）——纵向不再拉伸
                        val settle = 0.94f + 0.06f * appear
                        scaleX = settle * 1.18f
                        scaleY = settle
                        rotationZ = (1f - appear) * -2f
                    },
            )
        }
    }
}

/**
 * 把总进度映射成单个元素的局部进度，clamp + easeOutCubic。
 */
private fun phase(progress: Float, delay: Float, span: Float): Float {
    val t = ((progress - delay) / span).coerceIn(0f, 1f)
    val inverse = 1f - t
    return 1f - inverse * inverse * inverse
}

/**
 * 起停都平滑的进度（smoothstep）。
 * 旋转这类连续运动用 easeOutCubic 会“冲一下再硬停”，在真机上读起来就是不顺。
 */
private fun smoothPhase(progress: Float, delay: Float, span: Float): Float {
    val t = ((progress - delay) / span).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private class SplashShard(
    /** 单位空间（半径 1，原点为中心）里的碎片轮廓。 */
    val path: Path,
    val centroid: Offset,
    /** 入场起点：向外偏出的距离（单位半径）。 */
    val launch: Offset,
    /** 入场旋转角度（度）。 */
    val spin: Float,
)

private class SplashSpeck(
    val angle: Float,
    val distance: Float,
    val radius: Float,
    val accent: Boolean,
    val delay: Float = 0f,
)

private class LaunchSplashGeometry(
    val shards: List<SplashShard>,
    val inkBlob: Path,
    val inkBlobOuter: Path,
    val inkFlecks: List<SplashSpeck>,
    val particles: List<SplashSpeck>,
    val taijiLight: Path,
)

@Composable
private fun rememberLaunchSplashGeometry(): LaunchSplashGeometry = remember {
    // 固定种子：每次开屏的碎片/墨点分布一致，动画才像「设计过」而不是随机噪点。
    val random = Random(20261012)
    LaunchSplashGeometry(
        shards = buildShards(random),
        inkBlob = buildBlob(random, roughness = 1f),
        inkBlobOuter = buildBlob(random, roughness = 0.55f),
        inkFlecks = buildSpecks(random, count = 16, maxDistance = 1.15f, accentRatio = 0.15f),
        particles = buildSpecks(
            random = random,
            count = 14,
            maxDistance = 2.3f,
            accentRatio = 0.25f,
            maxDelay = 0.10f,
        ),
        taijiLight = buildTaijiLightPath(),
    )
}

private fun buildShards(random: Random): List<SplashShard> {
    val step = (2f * PI / SPLASH_SHARD_COUNT).toFloat()
    return List(SPLASH_SHARD_COUNT) { index ->
        val start = index * step
        val end = start + step
        // 内外半径交替：合拢后是一个错落的八角环，不是规则圆环。
        val outer = if (index % 2 == 0) 1f else 0.78f
        val inner = 0.5f
        val path = Path().apply {
            moveToUnit(inner, start)
            lineToUnit(outer, start)
            lineToUnit(outer, end)
            lineToUnit(inner, end)
            close()
        }
        val mid = (start + end) / 2f
        SplashShard(
            path = path,
            centroid = unitPoint((inner + outer) / 2f, mid),
            launch = unitPoint(
                radius = 0.40f + random.nextFloat() * 0.55f,
                angle = mid + (random.nextFloat() - 0.5f) * 0.5f,
            ),
            spin = (if (random.nextBoolean()) 1f else -1f) * (18f + random.nextFloat() * 30f),
        )
    }
}

/**
 * 不规则墨斑轮廓：半径叠三个正弦谐波，得到「毛边但不规则」的墨迹，而不是一个圆。
 */
private fun buildBlob(random: Random, roughness: Float): Path {
    val phase1 = random.nextFloat() * (2f * PI).toFloat()
    val phase2 = random.nextFloat() * (2f * PI).toFloat()
    val phase3 = random.nextFloat() * (2f * PI).toFloat()
    val points = 96
    return Path().apply {
        for (index in 0 until points) {
            val angle = index * (2f * PI / points).toFloat()
            val wobble = 1f +
                roughness * 0.10f * sin(3f * angle + phase1) +
                roughness * 0.06f * sin(7f * angle + phase2) +
                roughness * 0.035f * sin(11f * angle + phase3)
            val point = unitPoint(wobble, angle)
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
}

private fun buildSpecks(
    random: Random,
    count: Int,
    maxDistance: Float,
    accentRatio: Float,
    maxDelay: Float = 0f,
): List<SplashSpeck> = List(count) {
    SplashSpeck(
        angle = random.nextFloat() * (2f * PI).toFloat(),
        distance = 0.25f + random.nextFloat() * maxDistance,
        radius = 0.008f + random.nextFloat() * 0.016f,
        accent = random.nextFloat() < accentRatio,
        delay = if (maxDelay > 0f) random.nextFloat() * maxDelay else 0f,
    )
}

/**
 * 阴阳双鱼：单位半径 1，先生成「阳面」（右半圆 + 下方小圆左半边 − 上方小圆右半边）。
 *
 * 弧的方向按 Android 约定：角度以 +x 为 0、y 向下为正方向顺时针增长。
 * 起始点 (0,-1) → 大圆右半到 (0,1) → 下小圆左半逆时针回到圆心 → 上小圆右半顺时针回到 (0,-1)。
 */
private fun buildTaijiLightPath(): Path = Path().apply {
    arcTo(
        rect = Rect(-1f, -1f, 1f, 1f),
        startAngleDegrees = -90f,
        sweepAngleDegrees = 180f,
        forceMoveTo = true,
    )
    arcTo(
        rect = Rect(-0.5f, 0f, 0.5f, 1f),
        startAngleDegrees = 90f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false,
    )
    arcTo(
        rect = Rect(-0.5f, -1f, 0.5f, 0f),
        startAngleDegrees = 90f,
        sweepAngleDegrees = -180f,
        forceMoveTo = false,
    )
    close()
}

private fun Path.moveToUnit(radius: Float, angle: Float) {
    val point = unitPoint(radius, angle)
    moveTo(point.x, point.y)
}

private fun Path.lineToUnit(radius: Float, angle: Float) {
    val point = unitPoint(radius, angle)
    lineTo(point.x, point.y)
}

private fun unitPoint(radius: Float, angle: Float) = Offset(
    x = cos(angle) * radius,
    y = sin(angle) * radius,
)

private fun DrawScope.drawLaunchSplash(
    progress: Float,
    geometry: LaunchSplashGeometry,
    rail: Float,
    ink: Color,
    paper: Color,
    accent: Color,
) {
    val center = Offset(size.width / 2f, size.height * SPLASH_CENTER_RATIO)

    drawShards(progress, geometry.shards, center, rail, ink)
    drawInk(progress, geometry, center, rail, ink, accent)
    drawTaiji(progress, geometry.taijiLight, center, rail, ink, paper)
    drawParticles(progress, geometry.particles, center, rail, ink, accent)
}

/** 错落碎片：每片错开 22ms 飞入；墨色铺开时整体退场，像是被墨吞掉。 */
private fun DrawScope.drawShards(
    progress: Float,
    shards: List<SplashShard>,
    center: Offset,
    rail: Float,
    ink: Color,
) {
    val exit = 1f - smoothPhase(progress, delay = T_SHARD_OUT, span = 0.28f)
    if (exit <= 0f) return
    shards.forEachIndexed { index, shard ->
        // 用 smoothstep（慢起慢停）：easeOutCubic 起步太急，真机上看就是“顿一下”
        val appear = smoothPhase(progress, delay = index * 0.030f, span = 0.18f)
        val alpha = appear * exit
        if (alpha <= 0.01f) return@forEachIndexed
        withTransform({
            translate(
                left = shard.launch.x * rail * (1f - appear),
                top = shard.launch.y * rail * (1f - appear),
            )
            rotate(degrees = shard.spin * (1f - appear), pivot = center)
            val scaleFactor = 0.45f + 0.55f * appear
            scale(
                scaleX = scaleFactor,
                scaleY = scaleFactor,
                pivot = center + shard.centroid * rail,
            )
        }) {
            drawUnitPath(shard.path, center, rail, ink.copy(alpha = alpha))
        }
    }
}

/** 水墨：两层不规则墨斑从中心扩散，配一圈墨点；峰值 alpha 压在 0.35 以内，
 * 半径也只铺到 1.6 个单位——铺太满会在阴阳背后留一片棕色糊影。 */
private fun DrawScope.drawInk(
    progress: Float,
    geometry: LaunchSplashGeometry,
    center: Offset,
    rail: Float,
    ink: Color,
    accent: Color,
) {
    val grow = smoothPhase(progress, delay = T_INK_IN, span = 0.32f)
    val fade = 1f - smoothPhase(progress, delay = T_INK_OUT, span = 0.26f)
    if (grow <= 0f || fade <= 0f) return
    val radius = rail * (0.35f + 1.25f * grow)
    val alpha = 0.34f * grow * fade
    withTransform({
        translate(center.x, center.y)
        scale(radius, radius, pivot = Offset.Zero)
        // 加一点与总进度挂的持续旋转：墨色寿命内一直有运动，不会看着“定住几帧没动”
        rotate(degrees = 16f * grow + 10f * progress, pivot = Offset.Zero)
    }) {
        drawPath(
            path = geometry.inkBlob,
            brush = Brush.radialGradient(
                0f to ink.copy(alpha = alpha),
                0.55f to ink.copy(alpha = alpha * 0.7f),
                1f to ink.copy(alpha = 0f),
                center = Offset.Zero,
                radius = 1.06f,
            ),
        )
        drawPath(
            path = geometry.inkBlobOuter,
            brush = Brush.radialGradient(
                0f to ink.copy(alpha = alpha * 0.5f),
                1f to ink.copy(alpha = 0f),
                center = Offset.Zero,
                radius = 1.12f,
            ),
        )
    }
    geometry.inkFlecks.forEach { fleck ->
        val distance = fleck.distance * radius
        drawCircle(
            color = (if (fleck.accent) accent else ink).copy(alpha = alpha * 1.5f),
            radius = fleck.radius * rail,
            center = Offset(
                x = center.x + cos(fleck.angle) * distance,
                y = center.y + sin(fleck.angle) * distance,
            ),
        )
    }
}

/** 阴阳：墨盘 + 阳面 + 两个鱼眼，整体在时间窗内顺时针转约 245° 并轻微放大。 */
private fun DrawScope.drawTaiji(
    progress: Float,
    taijiLight: Path,
    center: Offset,
    rail: Float,
    ink: Color,
    paper: Color,
) {
    val appear = smoothPhase(progress, delay = T_TAIJI_IN, span = 0.26f)
    val fade = 1f - smoothPhase(progress, delay = T_TAIJI_OUT, span = 0.10f)
    val alpha = appear * fade
    if (alpha <= 0.01f) return
    val radius = rail * (0.62f + 0.38f * appear)
    val spin = -35f + TAIJI_SPIN_DEGREES * smoothPhase(progress, T_TAIJI_IN, 0.56f)
    withTransform({ rotate(degrees = spin, pivot = center) }) {
        drawCircle(color = ink.copy(alpha = 0.92f * alpha), radius = radius, center = center)
        withTransform({
            translate(center.x, center.y)
            scale(radius, radius, pivot = Offset.Zero)
        }) {
            drawPath(taijiLight, paper.copy(alpha = 0.95f * alpha))
            drawCircle(
                color = ink.copy(alpha = 0.92f * alpha),
                radius = 1f / 6f,
                center = Offset(0f, 0.5f),
            )
            drawCircle(
                color = paper.copy(alpha = 0.95f * alpha),
                radius = 1f / 6f,
                center = Offset(0f, -0.5f),
            )
        }
    }
}

/** 阴阳旋转总角度。
 * 245° / 0.5s ≈ 490°/s，在 60-90fps 下细部（S 曲线）会频闪，读起来就是“帧率不够”；
 * 140° 已经足够表达“旋出来”，但每帧位移小得多。
 */
private const val TAIJI_SPIN_DEGREES = 140f

/** 收尾墨点：从中心散开，先亮后灭。 */
private fun DrawScope.drawParticles(
    progress: Float,
    particles: List<SplashSpeck>,
    center: Offset,
    rail: Float,
    ink: Color,
    accent: Color,
) {
    particles.forEach { speck ->
        val local = phase(progress, delay = T_PARTICLE_IN + speck.delay, span = 0.28f - speck.delay)
        if (local <= 0f) return@forEach
        val distance = speck.distance * rail * (0.25f + 1.9f * local)
        val alpha = (local * 3f).coerceAtMost(1f) * (1f - local)
        drawCircle(
            color = (if (speck.accent) accent else ink).copy(alpha = alpha),
            radius = speck.radius * rail,
            center = Offset(
                x = center.x + cos(speck.angle) * distance,
                y = center.y + sin(speck.angle) * distance,
            ),
        )
    }
}

/** 单位空间的路径按 rail 放大、平移到屏幕中心。 */
private fun DrawScope.drawUnitPath(path: Path, center: Offset, rail: Float, color: Color) {
    withTransform({
        translate(center.x, center.y)
        scale(rail, rail, pivot = Offset.Zero)
    }) {
        drawPath(path, color)
    }
}
