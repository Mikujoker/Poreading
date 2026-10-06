package io.legado.app.data.repository

import io.legado.app.data.dao.OpdsSourceDao
import io.legado.app.data.entities.OpdsSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.opds.OpdsFeed
import io.legado.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.io.File

/**
 * OPDS 目录源：源的读写 + 抓页解析 + 下载。
 *
 * 只依赖 DAO 与网络，不碰 UI（架构护栏要求 UI/ViewModel 不得直连 DAO）。
 * 浏览结果不缓存：OPDS 目录页通常要实时刷新，缓存意义不大。
 */
class OpdsRepository(private val opdsSourceDao: OpdsSourceDao) {

    fun flowSources(): Flow<List<OpdsSource>> = opdsSourceDao.flowAll()

    suspend fun sources(): List<OpdsSource> = withContext(Dispatchers.IO) { opdsSourceDao.all }

    suspend fun save(source: OpdsSource) = withContext(Dispatchers.IO) {
        opdsSourceDao.insert(source)
    }

    suspend fun delete(source: OpdsSource) = withContext(Dispatchers.IO) {
        opdsSourceDao.delete(source)
    }

    /**
     * 抓一页。[href] 为空用源自身地址；[query] 非空时：
     * 源地址带 `{searchTerms}` 走服务端搜索，否则退化为**对本页条目按标题过滤**。
     */
    suspend fun browse(
        source: OpdsSource,
        href: String? = null,
        query: String? = null,
    ): OpdsFeed.Page = withContext(Dispatchers.IO) {
        val keyword = query?.trim()?.takeIf { it.isNotEmpty() }
        val useServerSearch = keyword != null && source.url.contains(SEARCH_TERMS)
        val url = when {
            useServerSearch -> OpdsFeed.searchUrl(source.url, keyword!!)
            href.isNullOrBlank() -> source.url
            else -> href
        }
        val page = OpdsFeed.parse(fetchText(source, url), url)
        if (keyword != null && !useServerSearch) {
            page.copy(
                entries = page.entries.filter {
                    it.title.contains(keyword, true) ||
                        it.author?.contains(keyword, true) == true
                },
            )
        } else {
            page
        }
    }

    /** 把条目下载到本地书目录，返回落地文件（调用方负责导入书架）。 */
    suspend fun download(source: OpdsSource, entry: OpdsFeed.Entry): File? =
        withContext(Dispatchers.IO) {
            val link = entry.acquisition ?: return@withContext null
            val target = File(FileUtils.getSdCardPath(), LOCAL_BOOK_DIR).apply { mkdirs() }
            val file = File(target, buildFileName(entry, link))
            okHttpClient.newCall(request(source, link.href).build()).execute().use { response ->
                val body = response.body ?: return@withContext null
                if (!response.isSuccessful) return@withContext null
                body.byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            file.takeIf { it.length() > 0 }
        }

    private fun fetchText(source: OpdsSource, url: String): String =
        okHttpClient.newCall(request(source, url).build()).execute().use { response ->
            response.body?.string().orEmpty()
        }

    private fun request(source: OpdsSource, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
            .apply {
                if (source.username.isNotBlank()) {
                    header("Authorization", Credentials.basic(source.username, source.password))
                }
            }

    /** 文件名取书名 + 按类型补扩展名（同名就加序号，不覆盖已下载的书）。 */
    private fun buildFileName(entry: OpdsFeed.Entry, link: OpdsFeed.Link): String {
        val ext = extensionOf(link.type, link.href)
        val base = entry.title.replace(Regex("[/\\\\:*?\"<>|\\r\\n]"), "_").trim().take(80)
            .ifBlank { "opds_book" }
        var file = File(base + ext)
        var index = 1
        while (true) {
            val candidate = File(FileUtils.getSdCardPath(), "$LOCAL_BOOK_DIR/${file.name}")
            if (!candidate.exists()) return file.name
            file = File("$base(${++index})$ext")
        }
    }

    private fun extensionOf(type: String?, href: String): String {
        val fromType = when {
            type == null -> null
            type.contains("epub") -> ".epub"
            type.contains("pdf") -> ".pdf"
            type.contains("mobipocket") || type.contains("mobi") -> ".mobi"
            type.contains("amazon") -> ".azw3"
            type.startsWith("text/plain") -> ".txt"
            type.contains("zip") -> ".zip"
            else -> null
        }
        if (fromType != null) return fromType
        val name = href.substringBefore('?').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "")
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ".$ext" else ".epub"
    }

    companion object {
        const val SEARCH_TERMS = "{searchTerms}"
        private const val LOCAL_BOOK_DIR = "Download/legado/novel"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
