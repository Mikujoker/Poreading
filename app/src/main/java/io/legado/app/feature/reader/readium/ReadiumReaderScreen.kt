package io.legado.app.feature.reader.readium

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import io.legado.app.data.entities.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

/**
 * 本地 epub 的原版排版阅读页：整屏交给 Readium 的 `EpubNavigatorFragment`
 * （内部是 WebView，分页/滚动/缩放/手势都由它负责）。
 *
 * 关键：**独立页面，不叠加自绘阅读器的画布** —— 叠加会毁掉布局基准与手势归属（v1 的教训）。
 *
 * 本页提供：连续滚动（默认）、菜单（顶部点一下就出）、目录跳转、章节级进度回写与续读。
 */
@Composable
fun ReadiumReaderScreen(
    book: Book,
    /** 续读用：上次读到的章节下标（来自库里这本书的进度）。 */
    initialChapterIndex: Int = 0,
    /** 正文字号（sp），来自阅读设置。 */
    fontSizeSp: Double? = null,
    /** 章节变化时回写进度（参数 = 章节下标）。 */
    onProgress: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var publication by remember(book.bookUrl) { mutableStateOf<Publication?>(null) }
    var failed by remember(book.bookUrl) { mutableStateOf(false) }
    var navigator by remember(book.bookUrl) { mutableStateOf<EpubNavigatorFragment?>(null) }
    var menuVisible by remember { mutableStateOf(false) }
    var tocVisible by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    LaunchedEffect(book.bookUrl) {
        failed = false
        val opened = withContext(Dispatchers.IO) { ReadiumOpener.open(context, File(book.bookUrl)) }
        publication = opened
        failed = opened == null
        if (opened != null) {
            toc = flattenToc(opened)
        }
    }

    Box(modifier.fillMaxSize()) {
        val current = publication
        when {
            failed -> Text(
                text = "这本 epub 解析失败（文件可能损坏或加密）",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            current != null -> {
                ReadiumNavigatorHost(
                    publication = current,
                    initialChapterIndex = initialChapterIndex,
                    fontSizeSp = fontSizeSp,
                    onNavigatorReady = { navigator = it },
                )
                // 章节变化 → 回写进度
                val nav = navigator
                LaunchedEffect(nav, current) {
                    if (nav == null) return@LaunchedEffect
                    val order = current.readingOrder.map { it.href.toString().substringBefore('#') }
                    nav.currentLocator.collect { locator ->
                        val href = locator.href.toString().substringBefore('#')
                        val index = order.indexOfFirst { it == href }
                        if (index >= 0) onProgress(index)
                    }
                }
            }
        }

        // 顶部触摸条：点一下出菜单（避开 Readium 的左右翻页手势区，只占顶部一条）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(56.dp)
                .clickable { menuVisible = true },
        )

        if (menuVisible) {
            ReaderMenu(
                onBack = { menuVisible = false; (context.findFragmentActivity())?.onBackPressedDispatcher?.onBackPressed() },
                onToc = { menuVisible = false; tocVisible = true },
                onDismiss = { menuVisible = false },
            )
        }

        if (tocVisible) {
            TocSheet(
                toc = toc,
                onPick = { index ->
                    tocVisible = false
                    val current0 = publication ?: return@TocSheet
                    val link = current0.readingOrder.getOrNull(index) ?: return@TocSheet
                    current0.locatorFromLink(link)?.let { navigator?.go(it, true) }
                },
                onDismiss = { tocVisible = false },
            )
        }
    }
}

@Composable
private fun ReadiumNavigatorHost(
    publication: Publication,
    initialChapterIndex: Int,
    fontSizeSp: Double?,
    onNavigatorReady: (EpubNavigatorFragment) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val containerId = remember { View.generateViewId() }
    val fragmentFactory = remember(publication, initialChapterIndex, fontSizeSp) {
        val initialLocator: Locator? = publication.readingOrder
            .getOrNull(initialChapterIndex)
            ?.let { publication.locatorFromLink(it) }
        EpubNavigatorFactory(
            publication = publication,
            configuration = EpubNavigatorFactory.Configuration(),
        ).createFragmentFactory(
            initialLocator = initialLocator,
            // 连续滚动（用户要的），字号跟随阅读设置
            initialPreferences = EpubPreferences(
                scroll = true,
                fontSize = fontSizeSp,
            ),
        )
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
        update = {
            (activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment)
                ?.let(onNavigatorReady)
        },
    )
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

@Composable
private fun ReaderMenu(onBack: () -> Unit, onToc: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            TextButton(onClick = onToc) { Text("目录") }
            Box(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("收起") }
        }
    }
}

@Composable
private fun TocSheet(toc: List<Pair<Int, String>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Menu, contentDescription = "收起目录")
                }
                Text("目录（${toc.size} 章）", style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider()
            if (toc.isEmpty()) {
                Text("这本 epub 没有目录", modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(toc.indices.toList()) { i ->
                        val (level, title) = toc[i]
                        Text(
                            text = title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(i) }
                                .padding(start = (16 + level * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        HorizontalDivider()
                    }
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
