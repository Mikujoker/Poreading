package io.legado.app.ui.book.manga

internal fun mangaChapterLoadingItem(
    chapterIndex: Int,
    message: String,
    failed: Boolean,
) = MangaReaderItemUi.ChapterEdge(
    key = "chapter-placeholder:$chapterIndex:${if (failed) "failed" else "loading"}",
    message = message,
    loading = !failed,
    retryChapterIndex = chapterIndex.takeIf { failed },
    fullScreen = true,
)

internal fun mangaClickRegionIndex(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
): Int {
    val column = (x / (width.coerceAtLeast(1) / 3f)).toInt().coerceIn(0, 2)
    val row = (y / (height.coerceAtLeast(1) / 3f)).toInt().coerceIn(0, 2)
    return row * 3 + column
}

internal fun mangaClickActionAt(
    clickActions: List<Int>,
    x: Float,
    y: Float,
    width: Int,
    height: Int,
): Int = clickActions.getOrNull(mangaClickRegionIndex(x, y, width, height)) ?: 0

internal fun nextMangaClickAction(action: Int): Int = when (action) {
    -1 -> 0
    0 -> 1
    1 -> 2
    2 -> 3
    3 -> 4
    else -> -1
}

/**
 * 找 [direction] 方向上的下一个「真实页」：跳过 ChapterTransition/ChapterEdge 等非页项。
 *
 * 直接以相邻下标做 PageStep 时，目标落在过渡页上既无法推进，scrollRequest 也因过渡页非
 * Page 而永远不清除（Pager 卡在章节边界）。保证步进永远落在实际页面。
 */
internal fun nextPageItemIndex(
    items: List<MangaReaderItemUi>,
    currentIndex: Int,
    direction: Int,
): Int? {
    var index = currentIndex + direction
    while (index in items.indices) {
        if (items[index] is MangaReaderItemUi.Page) return index
        index += direction
    }
    return null
}

/** 条漫里一个可见条目在列表坐标系里的位置（像素）。 */
internal data class MangaWebtoonVisibleItem(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * Chooses the page that represents a Webtoon viewport: the page under the viewport's reading
 * line, i.e. the page actually being read.
 *
 * 旧规则是「当前章只要还有任何一页可见就用它」，隐含「一章有很多页、整章总能滚出视口」。
 * PDF 是「一页一章」：前后邻章各只有一页，3 页内容减掉一屏后最多只能滚到当前页底边之下
 * 一点点，当前页必然还露在视口里 → 永远切不到下一章（真机表现：第 1 页翻不到第 2 页）。
 * 改成几何判定后，一页一章与多页章走同一条规则，进度也落在真正正在读的那页上。
 *
 * 阅读线落在非页项（章节过渡卡片）上时退回当前章的可见页：卡片还压在阅读线上，说明相邻章
 * 只是刚补进来，不算用户翻过去了。
 */
internal fun mangaWebtoonFocusedPageIndex(
    items: List<MangaReaderItemUi>,
    visibleItems: List<MangaWebtoonVisibleItem>,
    currentChapterIndex: Int,
    readingLine: Int,
): Int? {
    val visiblePages = visibleItems.mapNotNull { visible ->
        (items.getOrNull(visible.index) as? MangaReaderItemUi.Page)?.let { visible to it }
    }
    if (visiblePages.isEmpty()) return null
    val focusedPage = visiblePages.firstOrNull { (visible, _) ->
        readingLine >= visible.offset && readingLine < visible.offset + visible.size
    } ?: visiblePages.lastOrNull { (_, page) -> page.chapterIndex == currentChapterIndex }
        ?: visiblePages.minByOrNull { (visible, _) ->
            kotlin.math.abs(visible.offset + visible.size / 2 - readingLine)
        }
        ?: return null
    val focusedChapterIndex = focusedPage.second.chapterIndex
    // 相邻章节从 Loading 变 Ready 时，新的首/末页会被追加到仍停在过渡卡片上的视口。
    // 这不是用户继续翻过章节边界，不能因此直接切章；等过渡卡片离开视口后再上报。
    val transitionStillVisible = visibleItems.any { visible ->
        (items.getOrNull(visible.index) as? MangaReaderItemUi.ChapterTransition)
            ?.targetChapterIndex == focusedChapterIndex
    }
    return focusedPage.first.index.takeUnless { transitionStillVisible }
}

/** A programmatic position restore must not be overwritten by the old viewport's first callback. */
internal fun acceptsMangaVisibleItem(
    requestedItemIndex: Int?,
    reportedItemIndex: Int,
): Boolean = requestedItemIndex == null || requestedItemIndex == reportedItemIndex

internal fun shouldExposeMangaPages(currentChapterFinished: Boolean): Boolean =
    currentChapterFinished

/**
 * While an explicit catalog/menu navigation is being handed to the session, emissions from the
 * previous chapter must not reclaim the UI. The session command is ordered asynchronously, so a
 * presentation or page-state update for the old chapter can otherwise replace the target loading
 * placeholder before [MangaSessionCommand.OpenChapter] is reduced.
 */
internal fun acceptsMangaSessionForExplicitNavigation(
    pendingExplicitChapterIndex: Int?,
    sessionChapterIndex: Int,
): Boolean = pendingExplicitChapterIndex == null ||
        pendingExplicitChapterIndex == sessionChapterIndex

enum class MangaChapterSwitch { NONE, NEXT, PREVIOUS }

/**
 * 决定聚焦页是否触发章节切换：只认「用户当前聚焦的那一页」所在章节。
 *
 * 焦点页由阅读器上报（Webtoon 为视口底部页、Pager 为当前页/跨页），因此不依赖
 * 「本章是否仍可见」这类在窗口重建/定位期间会闪断的启发式，避免误切。
 */
internal fun mangaChapterSwitchDecision(
    currentChapterIndex: Int,
    visibleChapterIndex: Int,
    currentChapterVisible: Boolean,
): MangaChapterSwitch = when {
    currentChapterIndex < visibleChapterIndex ->
        if (currentChapterVisible) MangaChapterSwitch.NONE else MangaChapterSwitch.NEXT

    currentChapterIndex > visibleChapterIndex ->
        if (currentChapterVisible) MangaChapterSwitch.NONE else MangaChapterSwitch.PREVIOUS

    else -> MangaChapterSwitch.NONE
}

internal fun shouldForceMangaChapterPosition(
    hasPages: Boolean,
    isLoading: Boolean,
    currentBookUrl: String,
    targetBookUrl: String,
    pendingExplicitChapterIndex: Int?,
    targetChapterIndex: Int,
): Boolean =
    !hasPages || isLoading || currentBookUrl != targetBookUrl ||
        pendingExplicitChapterIndex == targetChapterIndex

/** 条漫预取跟随滑动中的可见页，不提前提交业务阅读进度。 */
internal fun mangaImagePrefetchIndex(scrollMode: Int, current: Int, visible: Int?): Int =
    if (scrollMode == io.legado.app.ui.book.manga.config.MangaScrollMode.WEBTOON ||
        scrollMode == io.legado.app.ui.book.manga.config.MangaScrollMode.WEBTOON_WITH_GAP
    ) visible ?: current
    else current

/** 列表可同时含前后章节；只准备实际当前章，当前页及附近页先执行，剩余页持续排队。 */
internal fun mangaChapterPrefetchPages(
    items: List<MangaReaderItemUi>, current: Int, fallbackChapter: Int, limit: Int = Int.MAX_VALUE,
): List<MangaReaderItemUi.Page> {
    val visible = items.getOrNull(current) as? MangaReaderItemUi.Page
    val chapter = visible?.chapterIndex ?: fallbackChapter
    val pages =
        items.filterIsInstance<MangaReaderItemUi.Page>().filter { it.chapterIndex == chapter }
    val anchor = visible?.pageIndex ?: pages.firstOrNull()?.pageIndex ?: return emptyList()
    return pages.sortedWith(compareBy<MangaReaderItemUi.Page> { kotlin.math.abs(it.pageIndex - anchor) }
        .thenBy { if (it.pageIndex >= anchor) 0 else 1 }).take(limit)
}

/** 缩放后的滚动偏移保留手势下的原图坐标；未知占位和固定高度章节项不按图片缩放。 */
internal fun mangaWebtoonZoomAnchor(
    visible: List<Triple<String, Int, Int>>,
    focalY: Float,
    knownSizes: Set<String>,
    zoomRatio: Float,
    panY: Float = 0f,
): Pair<String, Int>? {
    if (!zoomRatio.isFinite() || zoomRatio <= 0f || !focalY.isFinite() || !panY.isFinite()) return null
    val item = visible.firstOrNull {
        it.first in knownSizes && focalY >= it.second && focalY < it.second.toLong() + it.third
    } ?: return null
    val offset = (focalY - item.second) * zoomRatio - focalY - panY
    return item.first to kotlin.math.round(offset).toInt()
}

/** 高度改变前保持视口中已有图片的位置；未知占位没有可保持的图片内容。 */
internal fun mangaWebtoonResizeAnchor(
    visible: List<Triple<String, Int, Int>>,
    center: Int,
    knownSizes: Set<String>,
): Pair<String, Int>? {
    val candidates = visible.filter { it.first in knownSizes }
    val anchor = candidates.firstOrNull { center >= it.second && center < it.second + it.third }
        ?: candidates.minByOrNull { kotlin.math.abs(it.second + it.third / 2 - center) }
        ?: return null
    return anchor.first to -anchor.second
}

/** 滑动期间冻结可见项高度；离屏项仍可准备尺寸，停手后一次重测并恢复锚点。 */
internal class MangaWebtoonResizeQueue {
    private val pending = mutableMapOf<String, () -> Unit>()

    fun update(
        key: String,
        scrolling: Boolean,
        visible: Boolean,
        resize: () -> Unit,
        apply: (() -> Unit) -> Unit
    ) {
        if (scrolling && visible) {
            pending[key] = resize
        } else {
            pending.remove(key)
            apply(resize)
        }
    }

    fun flush(validKeys: Set<String>, apply: (() -> Unit) -> Unit) {
        val changes = pending.filterKeys { it in validKeys }.values.toList()
        pending.clear()
        if (changes.isNotEmpty()) apply { changes.forEach { it() } }
    }
}

internal enum class MangaBackAction {
    DISMISS_DIALOG,
    DISMISS_SHEET,
    CLOSE_SETTINGS,
    HIDE_MENU,
    CLOSE_READER,
}

internal fun resolveMangaBackAction(
    hasActiveDialog: Boolean,
    hasActiveSheet: Boolean,
    hasSettingsCategory: Boolean,
    menuVisible: Boolean,
): MangaBackAction = when {
    hasActiveDialog -> MangaBackAction.DISMISS_DIALOG
    hasActiveSheet -> MangaBackAction.DISMISS_SHEET
    hasSettingsCategory -> MangaBackAction.CLOSE_SETTINGS
    menuVisible -> MangaBackAction.HIDE_MENU
    else -> MangaBackAction.CLOSE_READER
}
