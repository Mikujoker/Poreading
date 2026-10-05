package io.legado.app.data.repository.manga

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.model.manga.MangaChapterContent
import io.legado.app.domain.model.manga.MangaPageContent
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.isPdf
import io.legado.app.model.localBook.PdfFile
import io.legado.app.model.localBook.PdfPageFiles
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.MD5Utils
import splitties.init.appCtx
import java.io.File

/** Dedicated local comic metadata loader for image directories and comic archives. */
internal class LocalMangaLoader(private val cacheRoot: File) : AutoCloseable {
    private val extractedRoots = linkedSetOf<File>()
    private val extractedBooks = mutableMapOf<String, File>()
    /** 登记过惰性页地址的书：会话结束时通知 [PdfPageFiles] 停掉它们的预渲染 */
    private val pdfBookUrls = linkedSetOf<String>()

    // PDF 也归这里：它是「每页一张图」的顺序阅读，和漫画同构，交给漫画阅读器就能白拿
    // 双指缩放 + 缩放后按锁定尺寸平移（telephoto）。文本阅读器那条路做不到缩放。
    fun supports(book: Book): Boolean = book.isPdf || book.originName.endsWith(".cbz", true) ||
            book.originName.endsWith(".zip", true) || localDirectory(book) != null

    fun chapters(book: Book): List<BookChapter> = if (book.isPdf) {
        val pageCount = PdfFile.getPageCount(book)
        if (pageCount <= 0) throw NoStackTraceException("PDF 无法读取")
        (0 until pageCount).map { index ->
            BookChapter(
                url = "$PDF_PAGE_SCHEME$index",
                title = "第${index + 1}页",
                bookUrl = book.bookUrl,
                index = index,
            )
        }
    } else {
        imageGroups(book).keys.mapIndexed { index, title ->
            BookChapter(
                url = "local-manga://chapter/$index",
                title = title,
                bookUrl = book.bookUrl,
                index = index,
            )
        }
    }

    fun load(book: Book, chapter: BookChapter): MangaChapterContent {
        if (book.isPdf) return loadPdf(book, chapter)
        val entry = imageGroups(book).entries.elementAtOrNull(chapter.index)
            ?: throw NoStackTraceException("Local comic chapter is missing")
        val urls = entry.value.map(ImageEntry::url)
        return MangaChapterContent(
            chapter.index,
            chapter.title,
            chapter.url,
            urls.mapIndexed { index, url -> MangaPageContent(url, index, urls.size) },
            false,
        )
    }

    /**
     * 整本 PDF 当作一章：页项几十上百个，页图走惰性地址，由 Coil 取图时按需渲染。
     *
     * 一页一章时列表里永远只有前/当/后三页的内容，滑到当前页底部就是一堵墙，必须再划一次；
     * 整本一章才能一路滑到底，也让图片预取器看得见后面几十页。
     */
    private fun loadPdf(book: Book, chapter: BookChapter): MangaChapterContent {
        val pageCount = PdfFile.getPageCount(book)
        if (pageCount <= 0) throw NoStackTraceException("PDF 无法读取")
        // 路径带版本号：磁盘缓存与 Coil 都是按文件名作键的，改了渲染方式（比如补白底）后
        // 必须换路径，否则永远读到旧图——这个坑真机上踩过一次
        extractedRoots += File(cacheRoot, "pdf-v2/${MD5Utils.md5Encode16(book.bookUrl)}")
        PdfPageFiles.register(book, cacheRoot)
        pdfBookUrls += book.bookUrl
        return MangaChapterContent(
            chapter.index,
            chapter.title,
            chapter.url,
            (0 until pageCount).map { index ->
                MangaPageContent(PdfPageFiles.url(book, index), index, pageCount)
            },
            false,
        )
    }

    private fun imageGroups(book: Book): Map<String, List<ImageEntry>> {
        val images = localDirectory(book)?.let(::directoryImages) ?: archiveImages(book)
        if (images.isEmpty()) throw NoStackTraceException("Local comic contains no images")
        val sorted = images.sortedWith(compareBy(AlphanumComparator) { it.path })
        val grouped = sorted.groupBy { image ->
            image.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() } ?: book.name
        }
        return if (grouped.size == sorted.size) mapOf(book.name to sorted) else grouped
    }

    private fun archiveImages(book: Book): List<ImageEntry> {
        val root = extractedBooks.getOrPut(book.bookUrl) { extract(book) }
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .map { ImageEntry(it.relativeTo(root).invariantSeparatorsPath, it.toURI().toString()) }
            .toList()
    }

    private fun extract(book: Book): File {
        val base = File(cacheRoot, MD5Utils.md5Encode16(book.bookUrl)).apply { mkdirs() }
        extractedRoots += base
        val files = ArchiveUtils.deCompress(book.bookUrl, base.path) { path ->
            path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
        }
        return files.map(File::getParentFile).filterNotNull().reduceOrNull(::commonParent) ?: base
    }

    private fun localDirectory(book: Book): Any? {
        return if (book.bookUrl.startsWith("content://")) {
            val uri = book.bookUrl.toUri()
            DocumentFile.fromTreeUri(appCtx, uri)?.takeIf { it.isDirectory }
        } else {
            val path = if (book.bookUrl.startsWith("file:")) {
                java.net.URI(book.bookUrl).path
            } else book.bookUrl
            File(path).takeIf { it.isDirectory }
        }
    }

    private fun directoryImages(root: Any): List<ImageEntry> = when (root) {
        is File -> root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .map { ImageEntry(it.relativeTo(root).invariantSeparatorsPath, it.toURI().toString()) }
            .toList()

        is DocumentFile -> documentImages(root, "")
        else -> emptyList()
    }

    private fun documentImages(directory: DocumentFile, prefix: String): List<ImageEntry> =
        directory.listFiles().flatMap { child ->
            val path =
                if (prefix.isEmpty()) child.name.orEmpty() else "$prefix/${child.name.orEmpty()}"
            when {
                child.isDirectory -> documentImages(child, path)
                child.isFile && child.name.orEmpty().substringAfterLast('.', "")
                    .lowercase() in IMAGE_EXTENSIONS ->
                    listOf(ImageEntry(path, child.uri.toString()))

                else -> emptyList()
            }
        }

    private fun commonParent(first: File, second: File): File {
        var candidate: File? = first
        while (candidate != null && !second.toPath().startsWith(candidate.toPath())) candidate =
            candidate.parentFile
        return candidate ?: first
    }

    override fun close() {
        pdfBookUrls.forEach(PdfPageFiles::forget)
        pdfBookUrls.clear()
        extractedRoots.forEach { it.deleteRecursively() }
        extractedRoots.clear()
        extractedBooks.clear()
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

        /** PDF 页的地址前缀（只在本类内部当标识用，真正加载的是落盘后的 file:// 地址） */
        const val PDF_PAGE_SCHEME = "pdf-page://"
    }

    private data class ImageEntry(val path: String, val url: String)
}
