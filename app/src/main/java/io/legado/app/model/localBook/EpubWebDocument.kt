package io.legado.app.model.localBook

import android.content.Context
import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.FileUtils
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * EPUB 无损渲染的取数层：把压缩包里的原始 XHTML 与资源原样交给 WebView。
 *
 * [EpubFile.getContent] 会把每章抽成 HTML 文本（并主动删掉 `<style>`），epub 自带的 CSS、
 * 分栏与复杂排版因此全丢。这里改为直接加载原始 XHTML，只在 `<head>` 注入主题覆盖样式，
 * 其余样式交给 epub 自己的 CSS。
 */
object EpubWebDocument {

    /** epub 资源在 WebView 里的虚拟域：所有请求都拦到这里，不会真的走网络。 */
    const val HOST = "epub.local"

    /** 用户外挂字体走这个路径前缀。 */
    const val FONT_PATH_PREFIX = "_font/"

    private val charsetRegex =
        Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE)

    /** 章节对应的资源 href（去掉 #fragment：同一个 xhtml 可能承载多章，靠 fragment 切）。 */
    fun resourceHref(chapter: BookChapter): String = chapter.url.substringBeforeLast("#")

    /** 章节 fragment：优先实体字段，其次取 url 里的 #fragment（同一个 xhtml 放多章时靠它切）。 */
    fun fragmentOf(chapter: BookChapter): String? = chapter.startFragmentId
        ?: chapter.url.substringAfterLast('#')
            .takeIf { chapter.url.contains('#') && it.isNotEmpty() }

    /** 压缩包内任意资源（xhtml / css / 图片 / 字体）的流。 */
    fun resourceStream(book: Book, href: String): InputStream? =
        runCatching { EpubFile.getImage(book, href) }.getOrNull()

    /** 章节原始 XHTML，已注入 viewport、主题 CSS 与滚动上报钩子。 */
    fun chapterHtml(
        book: Book,
        chapter: BookChapter,
        css: String,
        restoreFraction: Float,
    ): String? {
        val bytes = resourceStream(book, resourceHref(chapter))?.use { it.readBytes() } ?: return null
        return inject(String(bytes, charsetOf(bytes)), css, fragmentOf(chapter), restoreFraction)
    }

    /** 外挂字体文件（@font-face 用）。外挂目录优先，其次 app 私有字体目录。 */
    fun fontFile(context: Context, name: String): File? {
        val safe = name.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return fontDirs(context).asSequence()
            .mapNotNull { dir -> dir.listFiles()?.firstOrNull { it.isFile && it.name == safe } }
            .firstOrNull()
    }

    private fun fontDirs(context: Context): List<File> = listOfNotNull(
        File(FileUtils.getSdCardPath(), "legado/fonts"),
        context.getExternalFilesDir(null)?.let { File(it, "font") },
    )

    /** 响应 MIME：字体与图片都要显式给，否则 WebView 会当二进制拒绝。 */
    fun mimeOf(href: String): String = when (href.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css"
        "js" -> "application/javascript"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "xhtml", "html", "htm" -> "application/xhtml+xml"
        "xml", "ncx", "opf" -> "application/xml"
        "ttc" -> "font/collection"
        else -> "application/octet-stream"
    }

    /**
     * 主题覆盖样式。只覆盖"用户设置能控制"的那几项（背景/字色/字体/字号/行距/段距），
     * 其余（缩进、对齐、表格、边框…）留给 epub 自己的 CSS —— 这是"无损"和"可调"的分界。
     */
    @Suppress("LongParameterList")
    fun themeCss(
        backgroundHex: String,
        foregroundHex: String,
        accentHex: String,
        fontFamily: String,
        fontFace: String,
        fontSizePx: Float,
        lineHeightPx: Float,
        paragraphSpacingPx: Float,
        horizontalPaddingPx: Float,
    ): String = buildString {
        if (fontFace.isNotBlank()) appendLine(fontFace)
        append(
            """
            html { -webkit-text-size-adjust: none !important; }
            body {
              background: $backgroundHex !important;
              color: $foregroundHex !important;
              font-family: $fontFamily !important;
              font-size: ${fontSizePx}px !important;
              line-height: ${lineHeightPx}px !important;
              margin: 0 !important;
              padding: 0 ${horizontalPaddingPx}px !important;
              word-wrap: break-word !important;
              overflow-wrap: break-word !important;
            }
            body p, body div, body span, body li, body td, body dd,
            body h1, body h2, body h3, body h4, body h5, body h6 {
              font-family: $fontFamily !important;
            }
            body p { margin: 0 0 ${paragraphSpacingPx}px !important; }
            body h1, body h2, body h3, body h4, body h5, body h6 { color: $foregroundHex !important; }
            img, image, svg, video, table { max-width: 100% !important; }
            img, image, video { height: auto !important; }
            a { color: $accentHex !important; }
            """.trimIndent()
        )
    }

    /** 外挂字体的 @font-face 片段；没有可用字体文件时返回空串。 */
    fun fontFaceCss(fontName: String?): String {
        if (fontName.isNullOrBlank()) return ""
        val lower = fontName.lowercase()
        if (lower in GENERIC_FAMILIES) return ""
        return """
            @font-face {
              font-family: 'LegadoUserFont';
              src: url('https://$HOST/$FONT_PATH_PREFIX${Uri.encode(fontName.substringAfterLast('/'))}');
            }
        """.trimIndent()
    }

    private val GENERIC_FAMILIES =
        setOf("serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui")

    /** 字体族声明：外挂字体优先，否则用系统族名兜底。 */
    fun fontFamilyCss(fontName: String?): String = when {
        fontName.isNullOrBlank() -> "serif, sans-serif"
        fontName.lowercase() in GENERIC_FAMILIES -> "$fontName, serif"
        else -> "'LegadoUserFont', '$fontName', serif"
    }

    private fun charsetOf(bytes: ByteArray): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val name = charsetRegex.find(head)?.groupValues?.get(1) ?: "utf-8"
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    /**
     * 把 viewport / 主题 CSS / 钩子脚本塞进原始 XHTML。
     * 钩子只做两件事：上报滚动比例（存进度）、到底时上报（续读下一章）。
     */
    private fun inject(
        html: String,
        css: String,
        fragment: String?,
        restoreFraction: Float,
    ): String {
        val headBlock = buildString {
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">")
            append("<style>").append(css).append("</style>")
        }
        val fragmentLiteral = fragment?.let { "'" + it.replace("'", "") + "'" } ?: "null"
        val script = buildString {
            append("<script>(function(){")
            append("var bottomReported=false;")
            append("function report(){")
            append("var d=document.documentElement;")
            append("var max=Math.max(1,d.scrollHeight-window.innerHeight);")
            append("var y=Math.max(0,window.scrollY);")
            append("if(window.EpubHook){EpubHook.onScroll(y/max);}}")
            // 到底只在"用户真的滚动/缩放"后判定：章节短于一屏时不会一加载就自动跳下一章
            append("function checkBottom(){var d=document.documentElement;")
            append("var max=Math.max(1,d.scrollHeight-window.innerHeight);")
            append("if(window.EpubHook&&!bottomReported&&Math.max(0,window.scrollY)>=max-2){")
            append("bottomReported=true;EpubHook.onReachBottom();}}")
            append("var timer=null;")
            append("function schedule(){if(timer)clearTimeout(timer);timer=setTimeout(function(){report();checkBottom();},120);report();}")
            append("window.addEventListener('scroll',schedule,{passive:true});")
            append("window.addEventListener('resize',schedule,{passive:true});")
            append("function jump(){")
            append("var f=$fragmentLiteral;")
            append("if(f){var e=document.getElementById(f);")
            append("if(e){e.scrollIntoView();report();return;}}")
            append("var r=$restoreFraction;")
            append("if(r>0.001){var d=document.documentElement;")
            append("window.scrollTo(0,r*Math.max(1,d.scrollHeight-window.innerHeight));}")
            append("report();}")
            append("if(document.readyState==='complete'||document.readyState==='interactive'){jump();}")
            append("window.addEventListener('load',function(){jump();setTimeout(jump,400);});")
            append("})()</script>")
        }
        val withHead = if (html.contains("</head>", true)) {
            html.replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$headBlock</head>")
        } else {
            headBlock + html
        }
        return if (withHead.contains("</body>", true)) {
            withHead.replaceFirst(Regex("</body>", RegexOption.IGNORE_CASE), "$script</body>")
        } else {
            withHead + script
        }
    }
}
