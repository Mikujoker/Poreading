package io.legado.app.model.localBook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPUB 无损渲染的关键机制单测（纯 JVM，不需要设备）：
 *  - 主题 CSS 只覆盖"用户设置能管"的几项，且都带 !important（要压过 epub 自带样式）
 *  - 注入时**不动 epub 原有内容**，只在 head/body 前后插自己的东西
 *  - 外挂字体：系统族名不加 @font-face，自定义字体加且 URL 要编码
 *  - fragment 解析：实体字段优先，其次 URL 里的 #fragment
 *  - MIME 推断：字体/图片必须给对，否则 WebView 拒收
 */
class EpubWebDocumentTest {

    private val css = EpubWebDocument.themeCss(
        backgroundHex = "#EEEEEE",
        foregroundHex = "#3E3D3B",
        accentHex = "#7A5C3E",
        fontFamily = "'LegadoUserFont', '霞鹜文楷', serif",
        fontFace = "",
        fontSizePx = 20f,
        lineHeightPx = 32f,
        paragraphSpacingPx = 8f,
        horizontalPaddingPx = 0f,
    )

    @Test
    fun `主题 CSS 覆盖背景字色字体字号行距且带 important`() {
        assertTrue(css.contains("background: #EEEEEE !important"))
        assertTrue(css.contains("color: #3E3D3B !important"))
        assertTrue(css.contains("font-size: 20.0px !important"))
        assertTrue(css.contains("line-height: 32.0px !important"))
        assertTrue(css.contains("font-family: 'LegadoUserFont', '霞鹜文楷', serif !important"))
    }

    @Test
    fun `注入不改 epub 原有内容_只在 head 和 body 处插自己的东西`() {
        val html = """<html><head><link rel="stylesheet" href="style.css"/></head>
            <body><p class="t">正文</p><img src="a.png"/></body></html>""".trimIndent()
        val out = EpubWebDocument.inject(html, css, fragment = null, restoreFraction = 0f)
        // 原有元素一个都不能丢：epub 自带 CSS 链接、正文、图片
        assertTrue(out.contains("""<link rel="stylesheet" href="style.css"/>"""))
        assertTrue(out.contains("""<p class="t">正文</p>"""))
        assertTrue(out.contains("""<img src="a.png"/>"""))
        // 注入物就位
        assertTrue(out.contains("""<meta name="viewport"""))
        assertTrue(out.contains(css))
        assertTrue(out.indexOf("<style>") < out.indexOf("</head>"))
        assertTrue(out.indexOf("EpubHook") < out.indexOf("</body>"))
        // 注入的钩子只上报滚动比例与到底，不做事（避免给渲染线程加活）
        assertTrue(out.contains("EpubHook.onScroll"))
        assertTrue(out.contains("EpubHook.onReachBottom"))
    }

    @Test
    fun `没有 head 和 body 时也能注入`() {
        val out = EpubWebDocument.inject("<p>x</p>", css, fragment = "c2", restoreFraction = 0.5f)
        assertTrue(out.startsWith("<meta name=\"viewport\""))
        assertTrue(out.contains("<p>x</p>"))
        assertTrue(out.contains("'c2'"))
        assertTrue(out.contains("0.5"))
    }

    @Test
    fun `外挂字体_系统族名不加 font-face_自定义字体加且 URL 编码`() {
        assertFalse(EpubWebDocument.fontFaceCss("serif").contains("@font-face"))
        assertFalse(EpubWebDocument.fontFaceCss(null).contains("@font-face"))
        val face = EpubWebDocument.fontFaceCss("霞 鹜.ttf")
        assertTrue(face.contains("@font-face"))
        assertTrue(face.contains("_font/%E9%9C%9E%20%E9%B9%9C.ttf"))
        assertEquals("serif, serif", EpubWebDocument.fontFamilyCss("serif"))
        assertEquals("serif, sans-serif", EpubWebDocument.fontFamilyCss(""))
        assertTrue(EpubWebDocument.fontFamilyCss("霞鹜文楷").contains("LegadoUserFont"))
    }

    @Test
    fun `fragment 解析_实体字段优先其次取 url 里的片段`() {
        val chapter = io.legado.app.data.entities.BookChapter(
            bookUrl = "/x.epub",
            title = "c1",
            url = "text/ch1.xhtml#sec2",
            startFragmentId = "sec1",
        )
        assertEquals("sec1", EpubWebDocument.fragmentOf(chapter))
        chapter.startFragmentId = null
        assertEquals("sec2", EpubWebDocument.fragmentOf(chapter))
        chapter.url = "text/ch1.xhtml"
        assertNull(EpubWebDocument.fragmentOf(chapter))
    }

    @Test
    fun `资源 href 去掉 fragment`() {
        val chapter = io.legado.app.data.entities.BookChapter(
            bookUrl = "/x.epub",
            title = "c1",
            url = "OEBPS/text/ch1.xhtml#f1",
        )
        assertEquals("OEBPS/text/ch1.xhtml", EpubWebDocument.resourceHref(chapter))
    }

    @Test
    fun `MIME 推断_字体图片样式都要给对`() {
        assertEquals("application/epub+zip", EpubWebDocument.mimeOf("book.epub"))
        assertEquals("text/css", EpubWebDocument.mimeOf("styles/main.css"))
        assertEquals("image/jpeg", EpubWebDocument.mimeOf("Images/a.JPG"))
        assertEquals("image/svg+xml", EpubWebDocument.mimeOf("cover.svg"))
        assertEquals("font/woff2", EpubWebDocument.mimeOf("fonts/x.woff2"))
        assertEquals("font/ttf", EpubWebDocument.mimeOf("fonts/x.ttf"))
        assertEquals("application/xhtml+xml", EpubWebDocument.mimeOf("text/ch1.xhtml"))
    }
}
