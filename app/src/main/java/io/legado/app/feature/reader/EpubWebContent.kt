package io.legado.app.feature.reader

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.EpubWebDocument
import java.io.FileInputStream
import android.net.Uri

/**
 * 本地 EPUB 的无损正文渲染：WebView 直接加载压缩包里的原始 XHTML（含 epub 自带 CSS），
 * 主题覆盖样式、进度上报、「到底续读下一章」都由注入的 CSS/JS 钩子驱动。
 *
 * 生命周期由调用方控制（章节换了就重新加载），这里只负责渲染与上报。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubWebContent(
    book: Book,
    chapter: BookChapter?,
    /** 变了就重新加载：章节下标 + href + 排版配置。 */
    chapterKey: Any?,
    css: String,
    backgroundColor: Color,
    restoreFraction: Float,
    onScrollFraction: (Float) -> Unit,
    onReachBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currentBook = rememberUpdatedState(book)
    val currentCss = rememberUpdatedState(css)
    val currentScroll = rememberUpdatedState(onScrollFraction)
    val currentBottom = rememberUpdatedState(onReachBottom)
    val restore = rememberUpdatedState(restoreFraction)
    val webViewRef = remember { arrayOfNulls<WebView>(1) }

    // 虚拟域下的请求全部由这里应答：epub 条目的流 + 外挂字体文件。WebView 不会真的联网。
    fun intercept(host: String?, path: String?): WebResourceResponse? {
        if (host == null || !host.equals(EpubWebDocument.HOST, true)) return null
        val raw = path?.trimStart('/')?.takeIf { it.isNotEmpty() } ?: return null
        val href = Uri.decode(raw)
        if (href.startsWith(EpubWebDocument.FONT_PATH_PREFIX)) {
            val name = href.removePrefix(EpubWebDocument.FONT_PATH_PREFIX)
            val file = EpubWebDocument.fontFile(context, name) ?: return null
            return WebResourceResponse(EpubWebDocument.mimeOf(name), null, FileInputStream(file))
        }
        val stream = EpubWebDocument.resourceStream(currentBook.value, href) ?: return null
        return WebResourceResponse(EpubWebDocument.mimeOf(href), null, stream)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(backgroundColor.toArgb())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onScroll(fraction: Double) {
                            mainHandler.post { currentScroll.value(fraction.toFloat()) }
                        }

                        @JavascriptInterface
                        fun onReachBottom() {
                            mainHandler.post { currentBottom.value() }
                        }
                    },
                    JS_BRIDGE,
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = intercept(request.url?.host, request.url?.path)
                }
                webViewRef[0] = this
            }
        },
        update = { it.setBackgroundColor(backgroundColor.toArgb()) },
    )

    LaunchedEffect(chapterKey) {
        val web = webViewRef[0] ?: return@LaunchedEffect
        val target = chapter ?: return@LaunchedEffect
        val html = EpubWebDocument.chapterHtml(
            book = currentBook.value,
            chapter = target,
            css = currentCss.value,
            restoreFraction = restore.value,
        ) ?: return@LaunchedEffect
        web.loadDataWithBaseURL(
            "https://${EpubWebDocument.HOST}/${EpubWebDocument.resourceHref(target)}",
            html,
            "text/html",
            "utf-8",
            null,
        )
    }
}

private const val JS_BRIDGE = "EpubHook"

