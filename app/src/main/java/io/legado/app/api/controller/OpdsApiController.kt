package io.legado.app.api.controller

import android.net.Uri
import io.legado.app.api.ReturnData
import io.legado.app.data.entities.OpdsSource
import io.legado.app.data.repository.OpdsRepository
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.opds.OpdsFeed
import io.legado.app.utils.GSON
import org.koin.core.context.GlobalContext
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OPDS 目录源的 Web 服务侧接口。目的是**可断言**：浏览/搜索/下载都能在端上用 JSON 验证，
 * 不必截图。（UI 走同一套 Repository，两边行为一致。）
 *
 * - `GET  /opdsSources`：列出已保存的源
 * - `POST /opdsSaveSource {url,name,username,password}`：新增/覆盖
 * - `POST /opdsDeleteSource {url}`：删除
 * - `POST /opdsBrowse {url,username,password,href,query}`：浏览/搜索一页
 * - `POST /opdsDownload {url,username,password,href,title,type,import}`：下载（import=true 顺带进书架）
 */
object OpdsApiController {

    private val repository get() = GlobalContext.get().get<OpdsRepository>()

    suspend fun listSources(): ReturnData {
        val list = repository.sources().map {
            linkedMapOf<String, Any?>(
                "url" to it.url,
                "name" to it.name,
                "username" to it.username,
                "hasPassword" to it.password.isNotBlank(),
            )
        }
        return ReturnData().setData(list)
    }

    suspend fun saveSource(postData: String?): ReturnData {
        val body = body(postData)
        val url = body["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) return ReturnData().setErrorMsg("url 不能为空")
        val source = OpdsSource(
            url = url,
            name = body["name"]?.toString()?.takeIf { it.isNotBlank() } ?: url,
            username = body["username"]?.toString().orEmpty(),
            password = body["password"]?.toString().orEmpty(),
            sortNumber = (body["sortNumber"] as? Number)?.toInt() ?: 0,
        )
        repository.save(source)
        return ReturnData().setData(mapOf("url" to source.url, "saved" to true))
    }

    suspend fun deleteSource(postData: String?): ReturnData {
        val url = body(postData)["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) return ReturnData().setErrorMsg("url 不能为空")
        val source = repository.sources().firstOrNull { it.url == url }
            ?: return ReturnData().setErrorMsg("没有这个源：$url")
        repository.delete(source)
        return ReturnData().setData(mapOf("url" to url, "deleted" to true))
    }

    suspend fun browse(postData: String?): ReturnData {
        val body = body(postData)
        val source = sourceOf(body) ?: return ReturnData().setErrorMsg("url 不能为空")
        val page = runCatching {
            repository.browse(
                source = source,
                href = body["href"]?.toString()?.takeIf { it.isNotBlank() },
                query = body["query"]?.toString()?.takeIf { it.isNotBlank() },
            )
        }.getOrElse {
            return ReturnData().setErrorMsg("抓取/解析失败：${it.localizedMessage}")
        }
        return ReturnData().setData(pageToMap(page))
    }

    suspend fun download(postData: String?): ReturnData {
        val body = body(postData)
        val source = sourceOf(body) ?: return ReturnData().setErrorMsg("url 不能为空")
        val href = body["href"]?.toString()?.trim().orEmpty()
        val bookFeedHref = body["bookFeedHref"]?.toString()?.trim().orEmpty()
        if (href.isBlank() && bookFeedHref.isBlank()) {
            return ReturnData().setErrorMsg("href / bookFeedHref 至少要有一个")
        }
        val entry = OpdsFeed.Entry(
            title = body["title"]?.toString()?.takeIf { it.isNotBlank() } ?: "opds_book",
            acquisition = body["href"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                OpdsFeed.Link(href = it, type = body["type"]?.toString()?.takeIf { t -> t.isNotBlank() })
            },
            bookFeedHref = bookFeedHref.takeIf { it.isNotBlank() },
        )
        val file = runCatching { repository.download(source, entry) }.getOrElse {
            return ReturnData().setErrorMsg("下载失败：${it.localizedMessage}")
        } ?: return ReturnData().setErrorMsg("下载失败：没有拿到文件（检查地址/账号）")

        var importedName: String? = null
        if (body["import"] as? Boolean != false) {
            importedName = withContext(Dispatchers.IO) {
                runCatching { LocalBook.importFile(Uri.fromFile(file)).name }.getOrNull()
            }
        }
        return ReturnData().setData(
            mapOf(
                "file" to file.absolutePath,
                "size" to file.length(),
                "importedBookName" to importedName,
            )
        )
    }

    private fun sourceOf(body: Map<String, Any?>): OpdsSource? {
        val url = body["url"]?.toString()?.trim().orEmpty()
        if (url.isBlank()) return null
        return OpdsSource(
            url = url,
            name = body["name"]?.toString()?.takeIf { it.isNotBlank() } ?: url,
            username = body["username"]?.toString().orEmpty(),
            password = body["password"]?.toString().orEmpty(),
        )
    }

    private fun pageToMap(page: OpdsFeed.Page): Map<String, Any?> = linkedMapOf(
        "title" to page.title,
        "nextHref" to page.nextHref,
        "navCount" to page.navEntries.size,
        "bookCount" to page.bookEntries.size,
        "entries" to page.entries.map { entry ->
            linkedMapOf<String, Any?>(
                "title" to entry.title,
                "author" to entry.author,
                "summary" to entry.summary,
                "coverUrl" to entry.coverUrl,
                "isNav" to entry.isNav,
                "navHref" to entry.navHref,
                "bookFeedHref" to entry.bookFeedHref,
                "downloadable" to entry.isDownloadable,
                "downloadHref" to entry.acquisition?.href,
                "downloadType" to entry.acquisition?.type,
            )
        },
    )

    private fun body(postData: String?): Map<String, Any?> =
        GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
}
