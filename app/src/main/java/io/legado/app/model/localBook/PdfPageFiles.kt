package io.legado.app.model.localBook

import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF 页图地址（`pdf-page://<书 md5>/<页号>`）到磁盘图片的桥。
 *
 * 漫画阅读器只认图片地址，而 PDF 页必须先光栅化。整本 PDF 现在是一章、页项几十上百个，
 * 不可能在章加载时一次渲染完，所以地址先给一个惰性 scheme，由 Coil 的 Mapper 在真正取图
 * 时（含预取）渲染：预取因此天然带上了"提前把后面几页渲染好"。
 */
object PdfPageFiles {

    private const val SCHEME = "pdf-page-image://"

    /** 渲染宽度：给足像素，双指放大时才不糊 */
    private const val PAGE_WIDTH = 1600

    /** 后台预渲染的前瞻页数：翻到时这一页已经落在磁盘上 */
    private const val PRERENDER_AHEAD = 8

    private val books = ConcurrentHashMap<String, Book>()
    private val cacheRoots = ConcurrentHashMap<String, File>()

    /** 预渲染比任何一次取图都长命，但不该被会话结束牵连失效，所以只取消 job、不取消 scope */
    private val prerenderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prerenderJobs = ConcurrentHashMap<String, Job>()

    fun url(book: Book, pageIndex: Int): String = "$SCHEME${key(book)}/$pageIndex"

    /** 载入某本书的 PDF 时登记一次，之后 [resolve] 才知道去哪个文件、用哪本书渲染 */
    fun register(book: Book, cacheRoot: File) {
        books[key(book)] = book
        cacheRoots[key(book)] = cacheRoot
    }

    /** 会话结束：停掉这本的预渲染。缓存目录由调用方自己清 */
    fun forget(bookUrl: String) {
        val key = key(bookUrl)
        prerenderJobs.remove(key)?.cancel()
        books.remove(key)
        cacheRoots.remove(key)
    }

    /**
     * 解析惰性地址；不是 PDF 页地址返回 null（Coil 会继续走它自己的环节）。
     *
     * 缺图就地渲染：Coil 在后台线程调用，阻塞在这里是正常的。
     */
    fun resolve(data: String): File? {
        if (!data.startsWith(SCHEME)) return null
        val rest = data.removePrefix(SCHEME)
        val key = rest.substringBefore('/')
        val pageIndex = rest.substringAfter('/', "").toIntOrNull() ?: return null
        val book = books[key] ?: return null
        val cacheRoot = cacheRoots[key] ?: return null
        val out = File(cacheRoot, "pdf-v2/$key/$pageIndex.jpg")
        if (out.exists() && out.length() > 0) return out
        val rendered = PdfFile.renderPageFile(book, pageIndex, PAGE_WIDTH, out) ?: return null
        prerenderAhead(book, cacheRoot, key, pageIndex + 1)
        return rendered
    }

    /**
     * 按页号顺序把后面若干页光栅化到磁盘。
     *
     * 取图会按需渲染当前页，但不保证用户下一眼要的页已经就绪——翻得稍快就会当场渲染。
     * 这里从紧邻的下一页开始往前铺，每次换页都重排，已经渲染好的页立刻跳过。
     */
    private fun prerenderAhead(book: Book, cacheRoot: File, key: String, fromIndex: Int) {
        prerenderJobs.remove(key)?.cancel()
        prerenderJobs[key] = prerenderScope.launch {
            val pageCount = PdfFile.getPageCount(book)
            val end = (fromIndex + PRERENDER_AHEAD).coerceAtMost(pageCount)
            for (index in fromIndex until end) {
                if (!isActive) return@launch
                val out = File(cacheRoot, "pdf-v2/$key/$index.jpg")
                if (out.exists() && out.length() > 0) continue
                runCatching { PdfFile.renderPageFile(book, index, PAGE_WIDTH, out) }
                yield()
            }
        }
    }

    private fun key(book: Book): String = key(book.bookUrl)

    private fun key(bookUrl: String): String = MD5Utils.md5Encode16(bookUrl)
}
