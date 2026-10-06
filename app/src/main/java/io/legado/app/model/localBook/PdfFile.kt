package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Pixmap
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.SystemUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * PDF 解析与渲染（MuPDF 内核，替代原来的 android.graphics.pdf.PdfRenderer）。
 *
 * 换内核的直接原因（旧实现的三个病，都真机复现过）：
 *  1. `PdfRenderer` **不允许并发 openPage**，而阅读页会同时请求一"章"里的多张图 →
 *     抛异常被 `catch (_: Exception) { null }` 静默吞掉 → **灰页**；
 *  2. 旧实现有 `protected fun finalize() { closePdf() }`：GC 时把渲染器关掉，之后**连已经
 *     加载成功的页也渲染失败** → 「载入三页，一翻又没了」；
 *  3. 零缓存：每次进入可视区都重新读盘 + 重新光栅化。
 *
 * MuPDF 的 [Document]/[Page] 同样**不是线程安全的**，所以这里用 [renderLock] 把光栅化串行化，
 * 并且**不再依赖 finalize**：只在换书时显式 [closeDocument]。
 *
 * 原生库 `libmupdf_java.so` 取自官方 MuPDF viewer（版本 1.28.5），对应绑定 Java 来自
 * `platform/java/src/com/artifex/mupdf/fitz`（AGPL-3.0，个人自用）。
 */
class PdfFile(var book: Book) {
    companion object : BaseLocalBookParse {
        private var pFile: PdfFile? = null

        /**
         * 一页 = 一章。
         *
         * 旧实现是 10 页一章，于是打开一段就要一次性渲染 10 张全屏位图——那是"卡死/灰页"的主因。
         * 一页一章后每次只需要一张，且目录可以直接跳到任意页。
         */
        const val PAGE_SIZE = 1

        /** 渲染宽度上限：再大只是喂给屏幕，白白吃内存 */
        private const val MAX_RENDER_WIDTH = 1600

        @Synchronized
        private fun getPFile(book: Book): PdfFile {
            if (pFile == null || pFile?.book?.bookUrl != book.bookUrl) {
                // 换书时显式关，替代旧的 finalize（GC 关渲染器会让已加载的页也变灰）
                pFile?.closeDocument()
                pFile = PdfFile(book)
                return pFile!!
            }
            pFile?.book = book
            return pFile!!
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            getPFile(book).upBookInfo()
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getPFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getPFile(book).getContent(chapter)
        }

        @Synchronized
        override fun getImage(book: Book, href: String): InputStream? {
            return getPFile(book).getImage(href)
        }

        /** 页数。漫画阅读器要先知道有多少页才能建目录。 */
        @Synchronized
        fun getPageCount(book: Book): Int = getPFile(book).pageCount()

        /**
         * 把某一页渲染成图片文件。
         *
         * 漫画阅读器只认「图片地址」，所以 PDF 页得先落到磁盘再喂给它。渲染宽度给足
         * （1600），双指放大时不会糊成一团。
         */
        @Synchronized
        fun renderPageFile(book: Book, index: Int, targetWidth: Int, out: File): File? =
            getPFile(book).renderPageFile(index, targetWidth, out)
    }

    private var document: Document? = null

    /** MuPDF 的 Document/Page 不是线程安全的：所有光栅化走这把锁 */
    private val renderLock = Any()

    /** 少见路径（content:// 授权）才用得到：MuPDF 需要可随机访问的输入 */
    private var fileDescriptor: ParcelFileDescriptor? = null

    /** 页号@宽度 → 位图。够一屏再多一点，避免反复重光栅化又不至于吃爆内存 */
    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)
    private val cacheMax = 12

    init {
        upBookCover(true)
    }

    private fun openDocument(): Document? {
        if (document != null) return document
        return try {
            val uri = book.getLocalUri()
            document = if (uri.isContentScheme()) {
                // MuPDF 需要可 seek 的输入，content:// 先落到临时文件
                val tmp = File(appCtx.cacheDir, "pdf_${book.bookUrl.hashCode()}.pdf")
                if (!tmp.exists()) {
                    appCtx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    }
                }
                fileDescriptor = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                Document.openDocument(tmp.absolutePath)
            } else {
                Document.openDocument(uri.path!!)
            }
            document
        } catch (e: Throwable) {
            // 必须抓 Throwable：UnsatisfiedLinkError / ExceptionInInitializerError 都是 Error，
            // 用 catch (e: Exception) 会让它们静默穿透到图片加载器，只留一个错误占位
            AppLog.put("PDF 打开失败：${book.name}\n$e", e)
            e.printOnDebug()
            null
        }
    }

    /**
     * 按**目标像素宽度**光栅化第 [index] 页。
     *
     * 传宽度而不是"固定屏幕宽度"，是为了让放大后的重渲染有地方可去（缩放场景下按需要更清晰的位图）。
     */
    private fun renderPage(index: Int, targetWidth: Int): Bitmap? = synchronized(renderLock) {
        val doc = openDocument()
        if (doc == null) {
            AppLog.put("PDF 无法打开，跳过渲染第 ${index + 1} 页：${book.name}")
            return null
        }
        val pageCount = doc.countPages()
        if (pageCount <= 0) {
            AppLog.put("PDF 页数为 0：${book.name}")
            return null
        }
        if (index < 0 || index >= pageCount) {
            AppLog.put("PDF 页码越界：$index / $pageCount（${book.name}）")
            return null
        }
        val width = targetWidth.coerceIn(64, MAX_RENDER_WIDTH)
        val key = "$index@$width"
        cache[key]?.let { return it }

        val page = doc.loadPage(index) ?: return null
        try {
            val bounds = page.getBounds()
            if (bounds.x1 - bounds.x0 <= 0f || bounds.y1 - bounds.y0 <= 0f) return null
            // MuPDF 的页面坐标以 pt 为单位：目标像素宽 / pt 宽 = 缩放系数
            val scale = width / (bounds.x1 - bounds.x0)
            // MuPDF 的 getPixels() **只接受带 alpha 的 RGB/BGR 位图**，否则运行时抛
            // "invalid colorspace for getPixels (must be RGB/BGR with alpha)"——旧的那版
            // 按 alpha=false 渲染，于是每一页都在取像素这一步挂掉。
            // DeviceBGR + alpha=true 的字节序就是 Android ARGB_8888 的 BGRA 小端布局，
            // 而且两边都是预乘，所以可以直接拷贝，不必逐像素转换。
            // 自己建位图，而不是 page.toPixmap() —— 因为要先铺一层**不透明白底**。
            // PDF 页通常没有背景填充（白底是阅读器给的）：只画内容的话背景是透明的，
            // 到黑底界面上就变成了“白底黑字变黑底”。官方 viewer 也是先 clear(255) 再画页。
            // 位图必须带 alpha：getPixels() 只接受带 alpha 的 RGB/BGR 位图；
            // DeviceBGR + alpha 的字节序正好是 Android ARGB_8888（BGRA 小端、预乘）。
            val pageW = width
            val pageH = ((bounds.y1 - bounds.y0) * scale).roundToInt().coerceAtLeast(1)
            val pixmap = Pixmap(ColorSpace.DeviceBGR, pageW, pageH, true)
            try {
                pixmap.clear(255)
                val device = DrawDevice(pixmap)
                try {
                    page.run(device, Matrix(scale, scale), null)
                } finally {
                    device.close()
                    device.destroy()
                }
                // 本版本的 getPixels() 返回的是已按 ARGB 打包好的 int[]，直接 setPixels 即可
                val bitmap = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixmap.getPixels(), 0, pageW, 0, 0, pageW, pageH)
                cache[key] = bitmap
                if (cache.size > cacheMax) {
                    val oldest = cache.entries.firstOrNull()
                    if (oldest != null) {
                        cache.remove(oldest.key)
                        oldest.value.recycle()
                    }
                }
                bitmap
            } finally {
                pixmap.destroy()
            }
        } catch (e: Throwable) {
            AppLog.put("PDF 渲染失败：${book.name} 第 ${index + 1} 页\n$e", e)
            null
        } finally {
            page.destroy()
        }
    }

    private fun pageCount(): Int = openDocument()?.countPages() ?: 0

    /** 渲染成文件（漫画阅读器用这份；已存在且非空就直接复用，避免重复光栅化） */
    private fun renderPageFile(index: Int, targetWidth: Int, out: File): File? {
        if (out.exists() && out.length() > 0) return out
        val bitmap = renderPage(index, targetWidth) ?: return null
        return try {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            out
        } catch (e: Throwable) {
            AppLog.put("PDF 页面写出失败：${book.name} 第 ${index + 1} 页\n$e", e)
            null
        }
    }

    private fun getContent(chapter: BookChapter): String? {
        val doc = openDocument() ?: return null
        val start = chapter.index * PAGE_SIZE
        val end = ((chapter.index + 1) * PAGE_SIZE).coerceAtMost(doc.countPages())
        if (start >= end) return null
        return buildString {
            (start until end).forEach {
                append("<img src=").append('"').append(it).append('"').append(" >").append('\n')
            }
        }
    }

    private fun getImage(href: String): InputStream? {
        val index = href.toIntOrNull()
        if (index == null) {
            AppLog.put("PDF 图片地址不是页码：$href（${book.name}）")
            return null
        }
        val bitmap = renderPage(index, SystemUtils.screenWidthPx) ?: return null
        return BitmapUtils.toInputStream(bitmap)
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        val doc = openDocument() ?: return chapterList
        val pageCount = doc.countPages()
        val chapterCount = (pageCount + PAGE_SIZE - 1) / PAGE_SIZE
        for (i in 0 until chapterCount) {
            chapterList.add(
                BookChapter().apply {
                    index = i
                    bookUrl = book.bookUrl
                    // 一页一章，标题直接用页码，方便从目录跳到任意页
                    title = "第${i * PAGE_SIZE + 1}页"
                    url = "pdf_$i"
                }
            )
        }
        return chapterList
    }

    /**
     * 用首页当封面。
     *
     * 用户口径：文件自带封面的就用它，别一律套内置的默认书封；PDF 的"自带封面"就是第一页。
     * 旧实现也在做这件事，但因为渲染管线是坏的，写出来的封面只有 3.4 KB（等于空图）。
     */
    private fun upBookCover(fastCheck: Boolean = false) {
        try {
            if (book.coverUrl.isNullOrEmpty()) {
                book.coverUrl = LocalBook.getCoverPath(book)
            }
            val coverPath = book.coverUrl ?: return
            if (fastCheck && File(coverPath).let { it.exists() && it.length() > 8 * 1024 }) return
            val bitmap = renderPage(0, 900) ?: return
            FileOutputStream(FileUtils.createFileIfNotExist(coverPath)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.flush()
            }
        } catch (e: Exception) {
            AppLog.put("生成 PDF 封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
        }
    }

    private fun upBookInfo() {
        val doc = openDocument()
        if (doc == null) {
            book.intro = "书籍导入异常"
            return
        }
        upBookCover()
        if (book.name.isEmpty()) {
            book.name = book.originName.substringBeforeLast(".")
        }
        book.intro = "${doc.countPages()} 页"
    }

    /** 显式关闭。**不要**放进 finalize：GC 关渲染器会让已经显示的页也变灰。 */
    fun closeDocument() {
        synchronized(renderLock) {
            document?.destroy()
            document = null
            fileDescriptor?.close()
            fileDescriptor = null
            cache.values.forEach { if (!it.isRecycled) it.recycle() }
            cache.clear()
        }
    }
}
