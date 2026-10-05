package io.legado.app.ui.widget.components.image.cover

import android.graphics.Paint
import java.io.File
import splitties.init.appCtx
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.legado.app.core.ui.morph.BookCoverMorphAnchors
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.FileUtils
import io.legado.app.ui.theme.LocalAppUiConfiguration
import org.koin.compose.koinInject
import io.legado.app.model.BookCover as BookCoverModel
import androidx.compose.ui.graphics.BlendMode

private const val SharedCoverRadiusCacheMaxSize = 256

/** 用户在换封面页选的“默认封面”，与空地址一样表示这本书没有真实封面。 */
internal const val DefaultCoverPath = "use_default_cover"
private val sharedCoverRadiusCache = mutableStateMapOf<String, Dp>()

/**
 * 这本书是否有真实封面地址可加载。
 *
 * 与 [usesDefaultBookCover] 的区别：这里只看地址本身，不读 Compose 配置，
 * 因此预热这类非组合场景也能复用同一判定。
 */
internal fun isDefaultCoverPath(path: String?): Boolean =
    path.isNullOrBlank() || path == DefaultCoverPath

/**
 * 封面在源页面的圆角缓存读取入口：封面离开源页面（Visible→Visible 定格）时写入，
 * 阅读端 sharedBounds 的起始圆角由它提供，保证转场两端圆角衔接连续。
 */
internal fun sharedCoverSourceRadius(sharedCoverKey: String?): Dp? =
    sharedCoverKey?.let { sharedCoverRadiusCache[it] }

@Composable
internal fun usesDefaultBookCover(path: String?): Boolean {
    return LocalAppUiConfiguration.current.cover.useDefaultCover || isDefaultCoverPath(path)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BookCoverImage(
    name: String?,
    author: String?,
    path: String?,
    modifier: Modifier = Modifier,
    sourceOrigin: String? = null,
    memoryCacheKey: String? = null,
    // 本书 bookUrl（别名缓存键）与书架本地优先标志，透传给 buildCoverImageRequest。
    bookUrl: String? = null,
    preferCache: Boolean = false,
    ignoreUseDefaultCover: Boolean = false,
    showLoadingPlaceholder: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    onLoadFinish: (() -> Unit)? = null,
    onSuccess: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
    sharedCoverKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    requestBuilder: ImageRequest.Builder.() -> Unit = {},
) {
    val context = LocalContext.current
    val isNight = LegadoTheme.isDark
    val coverSettings = LocalAppUiConfiguration.current.cover

    val useDefault = (!ignoreUseDefaultCover && coverSettings.useDefaultCover) ||
            path.isNullOrBlank() ||
            path == DefaultCoverPath
    val finalPath = if (useDefault) null else path
    val defaultCoverPaths =
        if (isNight) coverSettings.defaultCoverDark else coverSettings.defaultCover

    val randomPath = remember(name, author, path, isNight, defaultCoverPaths) {
        BookCoverModel.getRandomDefaultPath(
            seed = name ?: author ?: path ?: "",
            isNight = isNight
        )
    }

    val hasCustomDefault = !randomPath.isNullOrBlank()
    val customDefaultMemoryCacheKey =
        if (finalPath == null && sharedCoverKey != null) {
            "$sharedCoverKey:default:$randomPath"
        } else {
            randomPath
        }
    var isOnlineCoverLoaded by remember(finalPath) { mutableStateOf(false) }
    var onlineCoverLoadFailed by remember(finalPath) { mutableStateOf(false) }

    LaunchedEffect(finalPath) {
        if (finalPath == null) {
            isOnlineCoverLoaded = false
            onlineCoverLoadFailed = false
        }
    }

    val isUsingDefaultCover = finalPath == null || onlineCoverLoadFailed
    val showLoadingDefault = sharedCoverKey == null && !isOnlineCoverLoaded
    val showCustomDefault = hasCustomDefault &&
        !isOnlineCoverLoaded &&
        (isUsingDefaultCover || showLoadingDefault)
    val showDefaultIcon = !hasCustomDefault &&
        (
            isUsingDefaultCover ||
                (showLoadingPlaceholder && showLoadingDefault)
        )
    Box(
        modifier = modifier.then(
            with(sharedTransitionScope) {
                if (this != null && animatedVisibilityScope != null && sharedCoverKey != null) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(sharedCoverKey),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                } else {
                    Modifier
                }
            }
        )
    ) {
        if (showCustomDefault) {
            AsyncImage(
                model = buildCoverImageRequest(
                    context = context,
                    data = randomPath,
                    sourceOrigin = null,
                    loadOnlyWifi = false,
                    crossfade = showLoadingPlaceholder,
                    memoryCacheKey = customDefaultMemoryCacheKey,
                ),
                contentDescription = null,
                imageLoader = koinInject(),
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 有书名时不再画通用书本图标：两者抢焦点（书封设计原则：只能有一个阅读顺序）
        val titleWillBeDrawn = (if (isNight) coverSettings.showNameDark else coverSettings.showName) &&
            !name.isNullOrBlank()
        if (showDefaultIcon && !titleWillBeDrawn) {
            Icon(
                Icons.Default.Book,
                contentDescription = null,
                tint = LegadoTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxSize(0.35f)
                    .align(Alignment.Center)
            )
        }

        if (finalPath != null) {
            AsyncImage(
                model = buildCoverImageRequest(
                    context = context,
                    data = finalPath,
                    sourceOrigin = sourceOrigin,
                    loadOnlyWifi = coverSettings.loadOnlyOnWifi,
                    crossfade = showLoadingPlaceholder,
                    memoryCacheKey = coverMemoryCacheKey(
                        sharedCoverKey = sharedCoverKey,
                        explicitKey = memoryCacheKey,
                        path = finalPath,
                    ),
                    bookUrl = bookUrl,
                    preferCache = preferCache,
                    configure = requestBuilder,
                ),
                contentDescription = null,
                imageLoader = koinInject(),
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = {
                    isOnlineCoverLoaded = true
                    onlineCoverLoadFailed = false
                    onSuccess?.invoke()
                    onLoadFinish?.invoke()
                },
                onError = {
                    isOnlineCoverLoaded = false
                    onlineCoverLoadFailed = true
                    onError?.invoke()
                    onLoadFinish?.invoke()
                }
            )
        } else {
            LaunchedEffect(Unit) {
                onLoadFinish?.invoke()
            }
        }
    }
}

// 改成BookCover
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CoilBookCover(
    name: String?,
    author: String?,
    path: String?,
    radius: Dp = 4.dp,
    modifier: Modifier = Modifier.width(64.dp),
    sourceOrigin: String? = null,
    // 本书 bookUrl + 书架本地优先标志，透传给 BookCoverImage
    bookUrl: String? = null,
    preferCache: Boolean = false,
    onLoadFinish: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
    ignoreUseDefaultCover: Boolean = false,
    showLoadingPlaceholder: Boolean = true,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedCoverKey: String? = null,
    /**
     * 内容模糊半径，作用于封面图与占位文字这些**共享元素内部的子节点**。
     *
     * 之所以要传进来而不是让调用方在外面套 `Modifier.blur`：共享元素转场时，
     * overlay 只会搬运 sharedBounds 节点自己的内容，加在祖先上的模糊会被落下，
     * 表现就是"动画一开始模糊突然没了"。
     */
    contentBlur: Dp = 0.dp,
    /**
     * 盖在封面之上的叠加层（遮罩、点阵、锁标…），渲染在共享节点**内部**。
     *
     * 放成兄弟节点的话转场时不会被 overlay 带走，会出现"装饰停在原地、只有封面在飞"。
     */
    overlayContent: (@Composable BoxScope.() -> Unit)? = null,
    badgeText: String? = null,
    showBadgeDot: Boolean = false,
    leftBottomText: String? = null,
) {
    val coverSettings = LocalAppUiConfiguration.current.cover
    val isNight = LegadoTheme.isDark

    val useDefault = (!ignoreUseDefaultCover && coverSettings.useDefaultCover) ||
            path.isNullOrBlank() ||
            path == DefaultCoverPath
    val finalPath = if (useDefault) null else path
    val defaultCoverPaths =
        if (isNight) coverSettings.defaultCoverDark else coverSettings.defaultCover

    val randomPath = remember(name, author, path, isNight, defaultCoverPaths) {
        BookCoverModel.getRandomDefaultPath(
            seed = name ?: author ?: path ?: "",
            isNight = isNight
        )
    }

    val hasCustomDefault = !randomPath.isNullOrBlank()
    var isOnlineCoverLoaded by remember(finalPath) { mutableStateOf(false) }
    var onlineCoverLoadFailed by remember(finalPath) { mutableStateOf(false) }

    LaunchedEffect(finalPath) {
        if (finalPath == null) {
            isOnlineCoverLoaded = false
            onlineCoverLoadFailed = false
        }
    }

    val transitionRadius = rememberSharedCoverTransitionRadius(
        sharedCoverKey = sharedCoverKey,
        radius = radius,
        animatedVisibilityScope = animatedVisibilityScope
    )
    val shape = remember(transitionRadius) { RoundedCornerShape(transitionRadius) }
    val contentBlurModifier = if (contentBlur > 0.dp) {
        Modifier.blur(contentBlur, BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }

    val coilDensity = LocalDensity.current
    Box(
        modifier = modifier
            .aspectRatio(5f / 7f)
            .graphicsLayer {
                alpha = if (BookCoverMorphAnchors.isOriginCoverHidden(sharedCoverKey)) 0f else 1f
            }
            .onGloballyPositioned { coordinates ->
                if (sharedCoverKey != null) {
                    BookCoverMorphAnchors.report(
                        key = sharedCoverKey,
                        bounds = coordinates.boundsInRoot(),
                        cornerRadiusPx = with(coilDensity) { transitionRadius.toPx() },
                        bookName = name,
                        author = author,
                        coverPath = finalPath ?: path,
                        sourceOrigin = sourceOrigin,
                        bookUrl = bookUrl,
                        badgeText = badgeText,
                        showBadgeDot = showBadgeDot,
                        leftBottomText = leftBottomText,
                    )
                }
            }
            .then(
                with(sharedTransitionScope) {
                    if (this != null && animatedVisibilityScope != null && sharedCoverKey != null) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(sharedCoverKey),
                            animatedVisibilityScope = animatedVisibilityScope,
                            clipInOverlayDuringTransition = OverlayClip(shape)
                        )
                    } else Modifier
                }
            )
            .then(
                if (coverSettings.showShadow) {
                    Modifier.shadow(4.dp, shape)
                } else Modifier
            )
            .background(
                if (!hasCustomDefault && !isOnlineCoverLoaded) {
                    LegadoTheme.colorScheme.surfaceContainerLow
                } else Color.Transparent,
                shape
            )
            .clip(shape)
    ) {
        BookCoverImage(
            name = name,
            author = author,
            path = path,
            modifier = Modifier
                .fillMaxSize()
                .then(contentBlurModifier),
            sourceOrigin = sourceOrigin,
            bookUrl = bookUrl,
            preferCache = preferCache,
            ignoreUseDefaultCover = ignoreUseDefaultCover,
            showLoadingPlaceholder = showLoadingPlaceholder,
            onSuccess = {
                isOnlineCoverLoaded = true
                onlineCoverLoadFailed = false
                onLoadFinish?.invoke()
            },
            onError = {
                isOnlineCoverLoaded = false
                onlineCoverLoadFailed = true
                onError?.invoke()
                onLoadFinish?.invoke()
            },
            sharedCoverKey = sharedCoverKey
        )

        if (
            finalPath == null ||
            onlineCoverLoadFailed ||
            (
                sharedCoverKey == null &&
                    showLoadingPlaceholder &&
                    !isOnlineCoverLoaded
                )
        ) {
            // 占位文字（默认封面上的书名/作者）也一起模糊：
            // 它露的是真实字符串，锁定态不能比正常态更清晰
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(contentBlurModifier)
            ) {
                CoverTextOverlay(
                    name = name,
                    author = author,
                    isNight = isNight
                )
            }
        }

        // 遮罩/点阵/锁标等叠加层渲染在共享节点内部，转场时会随封面一起移动
        overlayContent?.invoke(this)
    }
}


/**
 * 转场两端的圆角：起点用源页面缓存下来的圆角，终点用本节点的 [radius]，
 * 期间随转场进度插值，避免两端圆角不一致时跳变。脱敏封面复用同一实现。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun rememberSharedCoverTransitionRadius(
    sharedCoverKey: String?,
    radius: Dp,
    animatedVisibilityScope: AnimatedVisibilityScope?
): Dp {
    if (sharedCoverKey == null || animatedVisibilityScope == null) {
        return radius
    }

    val transition = animatedVisibilityScope.transition
    val startRadius = sharedCoverRadiusCache[sharedCoverKey] ?: radius
    val animatedRadiusValue by transition.animateFloat(
        label = "book-cover-corner-radius"
    ) { state ->
        if (state == EnterExitState.Visible) radius.value else startRadius.value
    }

    LaunchedEffect(
        sharedCoverKey,
        radius,
        transition.currentState,
        transition.targetState
    ) {
        if (
            transition.currentState == EnterExitState.Visible &&
            transition.targetState == EnterExitState.Visible
        ) {
            sharedCoverRadiusCache[sharedCoverKey] = radius
            if (sharedCoverRadiusCache.size > SharedCoverRadiusCacheMaxSize) {
                sharedCoverRadiusCache.keys
                    .firstOrNull { it != sharedCoverKey }
                    ?.let(sharedCoverRadiusCache::remove)
            }
        }
    }

    return animatedRadiusValue.dp
}

/**
 * Determine if text is primarily Latin-script.
 * Returns true if more than 30% of characters are Latin letters.
 */
private fun isLatinBasedText(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val latinRatio = text.count { it in 'A'..'Z' || it in 'a'..'z' }.toFloat() / text.length
    return latinRatio > 0.3f
}

/** 这些字符不能出现在行首（中文排版的「避头尾」）。 */
private const val LINE_START_FORBIDDEN = "，。、；：！？）》」』】”’…·"

/**
 * 书名的断行：按实测宽度把文本分成 1~[maxLines] 行，行宽尽量均衡，收尾标点不带行首，
 * 西文优先在空格处断。
 *
 * 书封设计原则里「断行要按视觉重量平衡、别留孤字」——StaticLayout 只会傻填满一行，
 * 长中文书名会甩出「对)」这种孤字，所以这里自己做。
 */
private fun balancedTitleLines(
    text: String,
    paint: Paint,
    maxWidth: Float,
    maxLines: Int = 3,
): List<String> {
    if (text.isBlank()) return emptyList()
    val widths = FloatArray(text.length) { paint.measureText(text, it, it + 1) }
    val total = widths.sum()
    if (total <= maxWidth) return listOf(text)
    val lines = ((total / maxWidth).toInt() + 1).coerceAtMost(maxLines)
    val target = total / lines
    val out = mutableListOf<String>()
    var start = 0
    for (li in 0 until lines) {
        if (li == lines - 1) {
            out += text.substring(start)
            break
        }
        var acc = 0f
        var end = start
        while (end < text.length) {
            acc += widths[end]
            end++
            val restChars = text.length - end
            val restLines = lines - li - 1
            if (acc >= target && restChars >= restLines) break
        }
        // 西文别把单词劈两半：能回退到空格就回退
        val space = text.lastIndexOf(' ', (end - 1).coerceAtLeast(start))
        if (space > start + (end - start) / 2) end = space
        // 避头尾：行首是收尾标点就把前一个字拉过来
        if (end < text.length && text[end] in LINE_START_FORBIDDEN) end++
        out += text.substring(start, end).trim()
        start = end
    }
    return out.filter { it.isNotEmpty() }.take(maxLines)
}

/** 默认封面的纸色 / 墨色 / 朱砂（用户口径：米粉底 + 山水 + 手写楷体书名 + 朱红印章）。 */
private const val COVER_PAPER_DAY = 0xFFF7EEE6.toInt()
private const val COVER_PAPER_NIGHT = 0xFF221D19.toInt()
private const val COVER_INK_DAY = 0xFF3A322B.toInt()
private const val COVER_INK_NIGHT = 0xFFE8DFD3.toInt()
private const val COVER_SEAL = 0xFFB03A2A.toInt()

/** 封面底纹（山水），随 APK 走，按封面尺寸拉伸铺满。 */
private val coverArtBitmap: ImageBitmap? by lazy {
    runCatching {
        appCtx.assets.open("coverArt/kazusa-shanshui.png").use {
            BitmapFactory.decodeStream(it).asImageBitmap()
        }
    }.getOrNull()
}

/**
 * 封面书名的字体：优先毛笔楷书（用户口径：笔锋明显、墨迹重、粗细对比大、轮廓强）。
 *
 * 候选来源都是可自由分发的开源字体：志莽行书（用户选定，飘逸）→ 马善政毛笔楷书 → 霞鹜文楷 →
 * 系统衬线。
 * 从外挂字体目录读（B3 约定），读不到就往后回退，不能因为缺字体把封面画崩。
 */
/**
 * 启动后在后台预热封面字体。
 *
 * 4MB 的毛笔字体在主线程解析要几百毫秒，而它第一次被用到正是在书架首帧画封面那一刻 ——
 * 直接从启动卡顿里扣时间。这里提前在 IO 线程把它烘好，首帧只做取用。
 */
fun warmUpCoverTitleTypeface() {
    runCatching { coverTitleTypeface }
}

private val coverTitleTypeface: Typeface by lazy {
    val t0 = System.currentTimeMillis()
    io.legado.app.utils.StartupTrace.mark("封面字体开始加载")
    val dir = File(FileUtils.getSdCardPath(), "legado/fonts")
    listOf("ZhiMangXing-Regular.ttf", "MaShanZheng-Regular.ttf", "LXGWWenKaiScreen.ttf")
        .firstNotNullOfOrNull { name ->
            runCatching {
                File(dir, name).takeIf(File::isFile)?.let { Typeface.createFromFile(it) }
            }.getOrNull()
        }
        ?: Typeface.create(Typeface.SERIF, Typeface.NORMAL).also {
            io.legado.app.utils.StartupTrace.mark("封面字体加载完成 +${System.currentTimeMillis() - t0}ms")
        }
}

/** 印章里的作者名：1~2 字竖排，3~4 字排成两行（最多刻 4 个字）。 */
private fun sealRows(author: String): List<String> {
    val t = author.trim().take(4)
    return when {
        t.isEmpty() -> emptyList()
        t.length <= 2 -> t.map { it.toString() }
        else -> listOf(t.substring(0, 2), t.substring(2))
    }
}

@Composable
private fun CoverTextOverlay(
    name: String?,
    author: String?,
    isNight: Boolean
) {
    val coverSettings = LocalAppUiConfiguration.current.cover
    val showName = (if (isNight) coverSettings.showNameDark else coverSettings.showName) &&
        !name.isNullOrBlank()
    // 没有书名就交给外层画占位图标（封面不能一个字都没有）
    if (!showName) return
    val showAuthor = (if (isNight) coverSettings.showAuthorDark else coverSettings.showAuthor) &&
        !author.isNullOrBlank()

    val titleText = name!!
    val authorText = if (showAuthor) author!!.trim() else null
    val paperColor = Color(if (isNight) COVER_PAPER_NIGHT else COVER_PAPER_DAY)
    val inkColor = if (isNight) COVER_INK_NIGHT else COVER_INK_DAY
    // 夜里山水要反相成浅墨，否则深墨压在深底上等于没有
    val artFilter = if (isNight) {
        ColorFilter.tint(Color(0xFF6F665C), BlendMode.SrcIn)
    } else {
        null
    }

    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val viewWidth = size.width
                val viewHeight = size.height
                if (viewWidth <= 0f || viewHeight <= 0f) {
                    return@drawWithCache onDrawBehind { }
                }

                // 书名：手写楷体，字号随长度自适应，断行自己按视觉重量均衡（书封原则：别留孤字）
                val titleSize = when {
                    titleText.length <= 4 -> viewWidth / 4.4f
                    titleText.length <= 7 -> viewWidth / 5.4f
                    titleText.length <= 11 -> viewWidth / 6.6f
                    else -> viewWidth / 7.8f
                }
                val titlePaint = Paint().apply {
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                    typeface = coverTitleTypeface
                    textSize = titleSize
                    color = inkColor
                }
                val titleLines = balancedTitleLines(titleText, titlePaint, viewWidth * 0.80f)
                val lineHeight = titleSize * 1.22f

                // 朱红印章：只在有作者时出现（用户：没作者两个都不要）
                val rows = authorText?.let { sealRows(it) }.orEmpty()
                val sealSize = if (rows.isEmpty()) 0f else viewWidth * 0.21f
                val sealGap = if (rows.isEmpty()) 0f else titleSize * 0.40f
                // 印章里的字：一行最多两个字，所以字号按 1~2 字分档
                val sealTextSize = sealSize * (if (rows.any { it.length > 1 }) 0.30f else 0.34f)
                val sealTextPaint = Paint().apply {
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                    typeface = coverTitleTypeface
                    color = Color.White.toArgb()
                    textSize = sealTextSize
                }
                val sealPaint = Paint().apply {
                    isAntiAlias = true
                    color = COVER_SEAL
                }

                val blockHeight = titleLines.size * lineHeight + sealGap + sealSize
                // 用户口径：书名压在山上（山已整体调淡，字仍是墨色所以读得清），
                // 红日留在天上 —— 天与山各放一个元素，互不抢
                val blockTop = (viewHeight * 0.54f - blockHeight / 2f)
                    .coerceIn(viewHeight * 0.12f, (viewHeight * 0.95f - blockHeight))
                val firstBaseline = blockTop + titleSize * 0.94f
                val sealTop = blockTop + titleLines.size * lineHeight + sealGap
                val sealLeft = (viewWidth - sealSize) / 2f
                val rowStep = sealTextSize * 1.08f
                val sealTextTop = sealTop + (sealSize - rows.size * rowStep) / 2f

                onDrawBehind {
                    drawRect(paperColor)
                    coverArtBitmap?.let { art ->
                        drawImage(
                            image = art,
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(viewWidth.toInt(), viewHeight.toInt()),
                            colorFilter = artFilter,
                        )
                    }
                    drawIntoCanvas { canvas ->
                        val nativeCanvas = canvas.nativeCanvas
                        var baseline = firstBaseline
                        titleLines.forEach { line ->
                            nativeCanvas.drawText(line, viewWidth / 2f, baseline, titlePaint)
                            baseline += lineHeight
                        }
                        if (rows.isNotEmpty()) {
                            nativeCanvas.drawRoundRect(
                                sealLeft,
                                sealTop,
                                sealLeft + sealSize,
                                sealTop + sealSize,
                                sealSize * 0.14f,
                                sealSize * 0.14f,
                                sealPaint,
                            )
                            var y = sealTextTop + sealTextSize * 0.94f
                            rows.forEach { row ->
                                nativeCanvas.drawText(row, viewWidth / 2f, y, sealTextPaint)
                                y += rowStep
                            }
                        }
                    }
                }
            }
    )
}
