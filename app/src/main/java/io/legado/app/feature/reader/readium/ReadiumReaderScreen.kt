@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package io.legado.app.feature.reader.readium

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import io.legado.app.data.entities.Book
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.reader.DefaultReaderMenuBottomSurface
import io.legado.app.ui.widget.components.reader.DefaultReaderMenuTopSurface
import io.legado.app.ui.widget.components.reader.ReaderMenuActionSquare
import io.legado.app.ui.widget.components.reader.ReaderMenuAnimatedBottom
import io.legado.app.ui.widget.components.reader.ReaderMenuAnimatedTop
import io.legado.app.ui.widget.components.reader.ReaderMenuDismissLayer
import io.legado.app.ui.widget.components.reader.ReaderMenuSlider
import io.legado.app.ui.widget.components.text.AppText
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File
import org.readium.r2.navigator.preferences.Color as ReadiumColor

/** Readium 的 fontSize 是"相对出版方基准"的倍率（1.0 = 100%），而 legado 的 textSize 是 sp。 */
private const val REFERENCE_FONT_SIZE_SP = 16.0

/** 章内进度回写的去抖阈值（占整章的比例），避免滚动时每帧都写回。 */
private const val PROGRESS_EPSILON = 0.002

/** 菜单里可展开的小面板。 */
private enum class ReaderMenuPanel { None, Font, Theme }

/**
 * 本地 epub 的原版排版阅读页：整屏交给 Readium 的 `EpubNavigatorFragment`
 * （内部是 WebView，分页/滚动/缩放/手势都由它负责）。
 *
 * 关键：**独立页面，不叠加自绘阅读器的画布** —— 叠加会毁掉布局基准与手势归属（v1 的教训）。
 *
 * 交互一律走 Readium 官方的 `InputListener`（WebView 自己把正文上的点击/滑动回调上来），
 * 不自己铺触摸层抢事件；菜单浮层用与 txt 阅读菜单同一套视觉组件
 * （`surfaceContainerHigh` 顶栏 + 32dp 圆角底栏动作方块 + dismiss layer）。
 *
 * 菜单里只放 epub 真能生效的项：目录 / 字号 / 主题(日·夜) / 滚动·翻页。
 */
@Composable
fun ReadiumReaderScreen(
    book: Book,
    /** 续读用：上次读到的章节下标（来自库里这本书的进度）。 */
    initialChapterIndex: Int = 0,
    /** 续读用：上次读到的章内进度 0..1。 */
    initialProgression: Double = 0.0,
    /** 正文字号（sp），来自阅读设置。 */
    fontSizeSp: Int = 20,
    /** 正文背景色（ARGB）。null = 用 Readium 主题默认色。 */
    backgroundColorArgb: Int? = null,
    /** 正文文字色（ARGB）。null = 用 Readium 主题默认色。 */
    textColorArgb: Int? = null,
    /** 是否夜间主题（决定 Readium 的 LIGHT/DARK 外观）。 */
    isNight: Boolean = false,
    onBack: () -> Unit = {},
    /** 字号拖动 → 写回阅读设置（走 app 自己的配置命令）。 */
    onTextSizeChange: (Int) -> Unit = {},
    /** 菜单里切日/夜 → 走 app 自己的日/夜开关。 */
    onToggleDayNight: () -> Unit = {},
    /** 章节/章内进度变化（章节下标, 章内进度 0..1）。 */
    onProgress: (Int, Double) -> Unit = { _, _ -> },
    /** morph 收起时的内容变换（null = 不额外变换）。 */
    contentTransform: ReadiumContentTransform? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var publication by remember(book.bookUrl) { mutableStateOf<Publication?>(null) }
    var failed by remember(book.bookUrl) { mutableStateOf(false) }
    var navigator by remember(book.bookUrl) { mutableStateOf<EpubNavigatorFragment?>(null) }
    val menuVisible = remember(book.bookUrl) { mutableStateOf(false) }
    var tocVisible by remember(book.bookUrl) { mutableStateOf(false) }
    var toc by remember(book.bookUrl) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var panel by remember(book.bookUrl) { mutableStateOf(ReaderMenuPanel.None) }
    val scrollMode = remember(book.bookUrl) { mutableStateOf(true) }
    var progressPercent by remember(book.bookUrl) { mutableIntStateOf(0) }

    LaunchedEffect(book.bookUrl) {
        failed = false
        val opened = withContext(Dispatchers.IO) { ReadiumOpener.open(context, File(book.bookUrl)) }
        publication = opened
        failed = opened == null
        if (opened != null) {
            toc = flattenToc(opened)
        }
    }

    // 当前排版偏好：字号/主题/滚动模式都由这里驱动，改动走 submitPreferences 实时生效，不重建 Fragment
    val preferences = remember(
        fontSizeSp,
        backgroundColorArgb,
        textColorArgb,
        isNight,
        scrollMode.value,
    ) {
        EpubPreferences(
            scroll = scrollMode.value,
            // Readium 的 fontSize 是倍率：1.0 = 出版方基准字号
            fontSize = (fontSizeSp / REFERENCE_FONT_SIZE_SP).coerceIn(0.6, 3.0),
            theme = if (isNight) Theme.DARK else Theme.LIGHT,
            backgroundColor = backgroundColorArgb?.let { ReadiumColor(it) },
            textColor = textColorArgb?.let { ReadiumColor(it) },
        )
    }
    // 首次创建 Fragment 用的快照；之后的变化都通过 submitPreferences 送出
    val initialPreferences = remember(book.bookUrl) { preferences }

    LaunchedEffect(navigator, preferences) {
        navigator?.submitPreferences(preferences)
    }

    val inputListener = remember(publication, navigator) {
        ReaderInputListener(
            publication = publication,
            navigator = navigator,
            isScrollMode = { scrollMode.value },
            onToggleMenu = { menuVisible.value = !menuVisible.value },
        )
    }
    DisposableEffect(inputListener, navigator) {
        navigator?.addInputListener(inputListener)
        onDispose { navigator?.removeInputListener(inputListener) }
    }

    // 当前页的 WebView：章末"继续上滑换章"要用它判断页面有没有真的滚动
    Box(modifier.fillMaxSize()) {
        val current = publication
        when {
            failed -> Text(
                text = "这本 epub 解析失败（文件可能损坏或加密）",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                style = LegadoTheme.typography.bodyMedium,
            )

            current != null -> {
                ReadiumNavigatorHost(
                    publication = current,
                    initialChapterIndex = initialChapterIndex,
                    initialProgression = initialProgression,
                    initialPreferences = initialPreferences,
                    onNavigatorReady = { navigator = it },
                    contentTransform = contentTransform,
                )
                LaunchedEffect(navigator, current) {
                    val nav = navigator ?: return@LaunchedEffect
                    val order = current.readingOrder.map { it.href.toString().substringBefore('#') }
                    var lastIndex = -1
                    var lastProgression = -1.0
                    nav.currentLocator.collect { locator ->
                        val href = locator.href.toString().substringBefore('#')
                        val index = order.indexOfFirst { it == href }
                        if (index < 0) return@collect
                        val progression = locator.locations.progression?.coerceIn(0.0, 1.0) ?: 0.0
                        // 滚动时 Readium 会高频回报位置：只在真的变了才写回/刷新百分比
                        if (index == lastIndex && abs(progression - lastProgression) < PROGRESS_EPSILON) {
                            return@collect
                        }
                        lastIndex = index
                        lastProgression = progression
                        progressPercent = ((index + progression) / order.size * 100)
                            .roundToInt()
                            .coerceIn(0, 100)
                        onProgress(index, progression)
                    }
                }
            }
        }

        ReaderMenuDismissLayer(
            visible = menuVisible.value,
            onDismiss = {
                menuVisible.value = false
                panel = ReaderMenuPanel.None
            },
        )

        ReaderMenuAnimatedTop(visible = menuVisible.value) {
            ReaderTopBar(
                title = book.name,
                percent = progressPercent,
                onBack = onBack,
            )
        }

        ReaderMenuAnimatedBottom(visible = menuVisible.value) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (panel) {
                    ReaderMenuPanel.Font -> FontPanel(
                        fontSizeSp = fontSizeSp,
                        onTextSizeChange = onTextSizeChange,
                    )

                    ReaderMenuPanel.Theme -> ThemePanel(
                        isNight = isNight,
                        onToggleDayNight = onToggleDayNight,
                    )

                    ReaderMenuPanel.None -> Unit
                }
                DefaultReaderMenuBottomSurface {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ReaderMenuActionSquare(
                            icon = Icons.AutoMirrored.Filled.List,
                            text = "目录",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                menuVisible.value = false
                                tocVisible = true
                            },
                        )
                        ReaderMenuActionSquare(
                            icon = Icons.Default.FormatSize,
                            text = "字号",
                            modifier = Modifier.weight(1f),
                            selected = panel == ReaderMenuPanel.Font,
                            onClick = {
                                panel = if (panel == ReaderMenuPanel.Font) {
                                    ReaderMenuPanel.None
                                } else {
                                    ReaderMenuPanel.Font
                                }
                            },
                        )
                        ReaderMenuActionSquare(
                            icon = Icons.Default.Palette,
                            text = "主题",
                            modifier = Modifier.weight(1f),
                            selected = panel == ReaderMenuPanel.Theme,
                            onClick = {
                                panel = if (panel == ReaderMenuPanel.Theme) {
                                    ReaderMenuPanel.None
                                } else {
                                    ReaderMenuPanel.Theme
                                }
                            },
                        )
                        ReaderMenuActionSquare(
                            icon = Icons.Default.SwapVert,
                            text = if (scrollMode.value) "滚动" else "翻页",
                            modifier = Modifier.weight(1f),
                            selected = true,
                            onClick = {
                                scrollMode.value = !scrollMode.value
                            },
                        )
                    }
                }
            }
        }
    }

    TocSheet(
        show = tocVisible,
        toc = toc,
        onPick = { index ->
            tocVisible = false
            val current = publication ?: return@TocSheet
            val link = current.readingOrder.getOrNull(index) ?: return@TocSheet
            current.locatorFromLink(link)?.let { navigator?.go(it, true) }
        },
        onDismiss = { tocVisible = false },
    )
}

@Composable
private fun ReadiumNavigatorHost(
    publication: Publication,
    initialChapterIndex: Int,
    initialProgression: Double,
    initialPreferences: EpubPreferences,
    contentTransform: ReadiumContentTransform? = null,
    onNavigatorReady: (EpubNavigatorFragment) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val containerId = remember { View.generateViewId() }
    val fragmentFactory = remember(publication, initialChapterIndex, initialProgression) {
        val initialLocator: Locator? = publication.readingOrder
            .getOrNull(initialChapterIndex)
            ?.let { publication.locatorFromLink(it) }
            ?.let { locator ->
                // 续读：把上次的章内进度塞进 locator（Readium 在 scroll 模式下会用它 scrollToPosition）
                if (initialProgression > 0.0) {
                    locator.copy(
                        locations = locator.locations.copy(
                            progression = initialProgression.coerceIn(0.0, 1.0)
                        )
                    )
                } else {
                    locator
                }
            }
        EpubNavigatorFactory(
            publication = publication,
            configuration = EpubNavigatorFactory.Configuration(),
        ).createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = initialPreferences,
            configuration = EpubNavigatorFragment.Configuration {
                // 关掉 scroll 模式下的左右滑换章：改由"章末继续上滑"接管（见 ReaderInputListener），
                // 免得左右滑误触跳章、与上下滚动的手势归属打架
                disablePageTurnsWhileScrolling = true
                // 字号走 WebSettings.textZoom 而不是 Readium CSS 的 --USER__fontSize：
                // 出版方把 body 字号写成绝对 px 时（这本就是），改根字号是无效的
                useReadiumCssFontSize = false
            },
        )
    }

    // Fragment 事务是异步提交的：AndroidView.update 只在重组时跑，事务执行完往往没有下一次重组，
    // 导航器就永远拿不到（旧实现里 onNavigatorReady 一次都没触发，菜单/进度回写全程是死的）。
    // 这里主动等它挂上：有界轮询，最多 2.5s。
    LaunchedEffect(publication, activity, containerId) {
        val manager = activity?.supportFragmentManager ?: return@LaunchedEffect
        repeat(50) {
            val fragment = manager.findFragmentById(containerId) as? EpubNavigatorFragment
            if (fragment != null) {
                onNavigatorReady(fragment)
                return@LaunchedEffect
            }
            delay(50)
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                activity?.let { host ->
                    val manager = host.supportFragmentManager
                    val existing = manager.findFragmentById(containerId)
                    if (existing == null) {
                        val fragment = fragmentFactory.instantiate(
                            host.classLoader,
                            EpubNavigatorFragment::class.java.name,
                        )
                        manager.beginTransaction().replace(containerId, fragment).commitAllowingStateLoss()
                    }
                }
            }
        },
        update = { view ->
            val fragment = activity?.supportFragmentManager
                ?.findFragmentById(containerId) as? EpubNavigatorFragment
            fragment?.let(onNavigatorReady)
            // WebView 是真实 View：Compose 的图形层裁剪/变换对它不保证生效，
            // 所以 morph 收起时在这里按 View 级属性缩放/位移，和 txt 的内容面板收缩等效
            // WebView 是真实 View：Compose 的图形层裁剪对互操作 View 不生效（宿主那层只缩背景，
            // 正文纹丝不动 = "拖了不缩"），所以在这里按 morph 面板帧换算成 View 级属性
            val transform = contentTransform
                ?.takeIf { it.scaleX.isFinite() && it.scaleY.isFinite() && it.scaleX > 0.05f && it.scaleY > 0.05f }
            view.scaleX = transform?.scaleX ?: 1f
            view.scaleY = transform?.scaleY ?: 1f
            view.translationX = transform?.translationX?.takeIf { it.isFinite() } ?: 0f
            view.translationY = transform?.translationY?.takeIf { it.isFinite() } ?: 0f
            view.alpha = transform?.alpha?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        },
    )
}

/**
 * Readium 官方输入回调：点正文开关菜单，章末继续上滑进下一章。
 *
 * 回调必须返回 false（除了 onTap）：返回 true 在 JS 那侧等于 `preventDefault`，
 * 会把滚动/链接点击吃掉。
 */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
private class ReaderInputListener(
    private val publication: Publication?,
    private val navigator: EpubNavigatorFragment?,
    private val isScrollMode: () -> Boolean,
    private val onToggleMenu: () -> Unit,
) : InputListener {

    /** 一次拖拽开始时的页面滚动位置，用来判断"往上滑了但没有滚动"。 */
    private var scrollYAtDragStart = 0

    override fun onTap(event: TapEvent): Boolean {
        onToggleMenu()
        return true
    }

    override fun onDrag(event: DragEvent): Boolean {
        if (event.type == DragEvent.Type.Start) {
            scrollYAtDragStart = navigator?.visibleWebView()?.scrollY ?: 0
            return false
        }
        if (event.type != DragEvent.Type.End) return false
        if (!isScrollMode()) return false
        // 只有"明显往上滑"才考虑换章：offset 是相对起点的累计位移（设备像素）
        if (event.offset.y > -ADVANCE_DRAG_PX) return false
        val nav = navigator ?: return false
        val pub = publication ?: return false
        val webView = nav.visibleWebView() ?: return false
        // 手指往上滑了，但页面一点没跟着走 → 已经在本章底部。
        // 不能用 canScrollVertically(1)：Readium 滚动模式下章末仍返回 true（实测），
        // 只能比"这次拖拽有没有真的让页面滚动"。
        if (webView.scrollY > scrollYAtDragStart + SCROLL_EPSILON_PX) return false
        return nav.goToAdjacentChapter(pub, 1)
    }

    private companion object {
        /** 触发换章所需的上滑距离（设备像素）：太小会被滚动抖动误触。 */
        const val ADVANCE_DRAG_PX = 80f

        /** 判定"没滚动"的容差（像素）。 */
        const val SCROLL_EPSILON_PX = 4
    }
}

/** 跳到相邻章节（+1 下一章 / -1 上一章），越界返回 false。 */
private fun EpubNavigatorFragment.goToAdjacentChapter(
    publication: Publication,
    delta: Int,
): Boolean {
    val currentHref = currentLocator.value.href.toString().substringBefore('#')
    val order = publication.readingOrder
    val index = order.indexOfFirst { it.href.toString().substringBefore('#') == currentHref }
    if (index < 0) return false
    val link = order.getOrNull(index + delta) ?: return false
    val locator = publication.locatorFromLink(link) ?: return false
    return go(locator, true)
}

/**
 * 当前页的 WebView：ViewPager 只把当前页摆在视口内（相邻页被偏移到 ±屏宽之外）。
 */
private fun EpubNavigatorFragment.visibleWebView(): WebView? {
    val root = runCatching { publicationView }.getOrNull() as? ViewGroup ?: return null
    for (i in 0 until root.childCount) {
        val child = root.getChildAt(i)
        if (child.right <= 0 || child.left >= root.width) continue
        child.findWebView()?.let { return it }
    }
    return null
}

private fun View.findWebView(): WebView? {
    if (this is WebView) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).findWebView()?.let { return it }
        }
    }
    return null
}

/** 目录：Publication 的 toc 是树，这里拍平（最多两层，带缩进）。 */
private fun flattenToc(publication: Publication): List<Pair<Int, String>> {
    val result = mutableListOf<Pair<Int, String>>()
    fun walk(links: List<org.readium.r2.shared.publication.Link>, level: Int) {
        links.forEach { link ->
            val title = link.title?.takeIf { it.isNotBlank() } ?: link.href.toString()
            result += level to title
            walk(link.children, level + 1)
        }
    }
    walk(publication.tableOfContents, 0)
    return result
}

/** 顶栏：与 txt 阅读菜单同一个 surface 与同样的 insets 处理。 */
@Composable
private fun BoxScope.ReaderTopBar(
    title: String,
    percent: Int,
    onBack: () -> Unit,
) {
    DefaultReaderMenuTopSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            AppText(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                style = LegadoTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = "$percent%",
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** 菜单里展开的小面板：跟底栏同款圆角/描边，浮在底栏之上。 */
@Composable
private fun ReaderPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = LegadoTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, LegadoTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

@Composable
private fun FontPanel(
    fontSizeSp: Int,
    onTextSizeChange: (Int) -> Unit,
) {
    ReaderPanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = "字号",
                style = LegadoTheme.typography.labelLarge,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            ReaderMenuSlider(
                value = fontSizeSp.toFloat(),
                onValueChange = { onTextSizeChange(it.roundToInt()) },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                valueRange = 12f..36f,
                steps = 23,
                accessibilityLabel = "字号",
                accessibilityValue = "$fontSizeSp",
            )
            AppText(
                text = "$fontSizeSp",
                modifier = Modifier.padding(start = 12.dp),
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemePanel(
    isNight: Boolean,
    onToggleDayNight: () -> Unit,
) {
    ReaderPanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = "主题",
                style = LegadoTheme.typography.labelLarge,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            SmallTonalButton(
                onClick = { if (isNight) onToggleDayNight() },
                selected = !isNight,
                text = "日间",
            )
            SmallTonalButton(
                onClick = { if (!isNight) onToggleDayNight() },
                selected = isNight,
                text = "夜间",
            )
        }
    }
}

@Composable
private fun TocSheet(
    show: Boolean,
    toc: List<Pair<Int, String>>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = if (toc.isEmpty()) "目录" else "目录（${toc.size}）",
        // LazyColumn 已在内部尺寸内滚动，套一层尺寸动画只会抖
        animateContentSize = false,
    ) {
        if (toc.isEmpty()) {
            AppText(
                text = "这本 epub 没有目录",
                modifier = Modifier.padding(16.dp),
                style = LegadoTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(toc.size) { i ->
                    val (level, title) = toc[i]
                    AppText(
                        text = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(
                                start = (level * 16).dp,
                                top = 12.dp,
                                bottom = 12.dp,
                                end = 16.dp,
                            ),
                        style = LegadoTheme.typography.bodyLarge,
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Compose 里拿到的 context 可能是包装过的，这里剥到 FragmentActivity（承载 Fragment 必需）。 */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * morph 收起/展开时给 epub 内容用的 View 级变换。
 *
 * WebView 是真实 View：Compose 的图形层裁剪/变换对互操作 View 不保证生效，
 * 所以由调用方（阅读页）把 morph 的"内容面板"换算成缩放/位移/透明度，交给 AndroidView 去设。
 */
data class ReadiumContentTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val alpha: Float,
)
