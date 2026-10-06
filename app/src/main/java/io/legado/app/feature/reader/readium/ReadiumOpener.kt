package io.legado.app.feature.reader.readium

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * 用 Readium 打开本地 epub。失败返回 null（返回的是 Readium 的 Publication，后续交给
 * EpubNavigatorFragment 渲染 —— 它内部就是 WebView，也就是 Android 上唯一的"原版排版"路径）。
 */
object ReadiumOpener {

    suspend fun open(context: Context, file: File): Publication? {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val opener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                // 不做 PDF 渲染（本地 pdf 走 legado 自己的漫画/PDF 阅读器），给 null 即可
                pdfFactory = null,
            ),
        )
        val asset = runCatching { assetRetriever.retrieve(file).getOrNull() }.getOrNull() ?: return null
        return runCatching { opener.open(asset, allowUserInteraction = true).getOrNull() }.getOrNull()
    }
}
