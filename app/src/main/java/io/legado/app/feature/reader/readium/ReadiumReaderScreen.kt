package io.legado.app.feature.reader.readium

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import org.readium.r2.shared.publication.Publication
import java.io.File

/**
 * 本地 epub 的原版排版阅读页：整屏交给 Readium 的 `EpubNavigatorFragment`
 * （内部是 WebView，分页/缩放/手势/主题都由它负责）。
 *
 * 关键：**这是独立页面，不叠加在自绘画布之上** —— v1 就死在这个叠加接法上
 * （布局基准与手势归属都乱：放大数倍 / 拖不动 / 菜单打不开）。
 */
@Composable
fun ReadiumReaderScreen(
    book: Book,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var publication by remember(book.bookUrl) { mutableStateOf<Publication?>(null) }
    var failed by remember(book.bookUrl) { mutableStateOf(false) }

    LaunchedEffect(book.bookUrl) {
        failed = false
        val opened = withContext(Dispatchers.IO) {
            ReadiumOpener.open(context, File(book.bookUrl))
        }
        publication = opened
        failed = opened == null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val current = publication
        when {
            failed -> Text(
                text = "这本 epub 解析失败（文件可能损坏或加密）",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            current != null -> ReadiumNavigatorHost(publication = current)
            // 解析中：留底色即可
        }
    }
}

@Composable
private fun ReadiumNavigatorHost(publication: Publication) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val containerId = remember { View.generateViewId() }
    val fragmentFactory = remember(publication) {
        EpubNavigatorFactory(
            publication = publication,
            configuration = EpubNavigatorFactory.Configuration(),
        ).createFragmentFactory(
            initialLocator = null,
            initialPreferences = EpubPreferences(),
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                activity?.let { host ->
                    val manager = host.supportFragmentManager
                    if (manager.findFragmentById(containerId) == null) {
                        val fragment = fragmentFactory.instantiate(
                            host.classLoader,
                            EpubNavigatorFragment::class.java.name,
                        )
                        manager.beginTransaction()
                            .replace(containerId, fragment)
                            .commitAllowingStateLoss()
                    }
                }
            }
        },
    )
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
