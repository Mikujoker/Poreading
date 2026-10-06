package io.legado.app.model.opds

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * OPDS(Atom) 目录解析：只做纯文本→结构，不碰网络/数据库，方便单测。
 *
 * 支持的元素（Calibre-Web / Kavita / Komga / Standard Ebooks 都在用）：
 *  - `<entry>`：`title` / `author > name` / `content`(或 `summary`) / `link`
 *  - 下载：`rel="http://opds-spec.org/acquisition"` 或 type 为 epub/pdf/mobi 的链接
 *  - 封面：`rel="http://opds-spec.org/image"`（退化到 `image/thumbnail`、`x-stanza-cover-image`）
 *  - 子目录：`type` 含 `application/atom+xml`（OPDS 目录 feed）
 *  - 分页：feed 级 `link rel="next"`
 */
object OpdsFeed {

    private const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
    private const val REL_IMAGE = "http://opds-spec.org/image"
    private const val REL_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private const val TYPE_ATOM = "application/atom+xml"
    private val BOOK_TYPES = listOf(
        "application/epub+zip", "application/pdf", "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook", "text/plain", "application/x-mobi8-ebook",
        "application/zip",
    )

    data class Link(val href: String, val type: String? = null, val rel: String? = null)

    data class Entry(
        val title: String,
        val author: String? = null,
        val summary: String? = null,
        val coverUrl: String? = null,
        /** 可下载的资源（点它 = 下载并导入本地书）。 */
        val acquisition: Link? = null,
        /** 子目录 feed（点它 = 进下一层）。 */
        val navHref: String? = null,
    ) {
        val isNav get() = navHref != null && acquisition == null
    }

    data class Page(
        val title: String = "",
        val entries: List<Entry> = emptyList(),
        val nextHref: String? = null,
        val searchTemplate: String? = null,
    ) {
        val navEntries get() = entries.filter { it.isNav }
        val bookEntries get() = entries.filter { !it.isNav }
    }

    /** 解析一页 OPDS。[baseUrl] 用于把相对 href 补成绝对地址。 */
    fun parse(xml: String, baseUrl: String): Page {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        // 目录 feed 可能是 <feed>，也可能被包一层（部分服务器给 application/atom+xml 时带 XML 声明）
        val feed = doc.selectFirst("feed") ?: doc
        val title = feed.children().firstOrNull { it.tagName() == "title" }?.text().orEmpty()
        val entries = feed.children()
            .filter { it.tagName() == "entry" }
            .map { parseEntry(it, baseUrl) }
            .filter { it.title.isNotBlank() }
        val next = feed.children()
            .firstOrNull { it.tagName() == "link" && it.attr("rel") == "next" }
            ?.let { abs(baseUrl, it.attr("href")) }
        return Page(title = title, entries = entries, nextHref = next)
    }

    private fun parseEntry(entry: Element, baseUrl: String): Entry {
        val links = entry.children()
            .filter { it.tagName() == "link" && it.hasAttr("href") }
            .map {
                Link(
                    href = abs(baseUrl, it.attr("href")),
                    type = it.attr("type").takeIf { t -> t.isNotBlank() },
                    rel = it.attr("rel").takeIf { r -> r.isNotBlank() },
                )
            }
        val acquisition = links.firstOrNull { it.rel?.contains(REL_ACQUISITION) == true }
            ?: links.firstOrNull { it.type != null && BOOK_TYPES.any { t -> it.type.contains(t) } }
        val nav = links.firstOrNull {
            it.type?.contains(TYPE_ATOM) == true && it.rel != "http://opds-spec.org/acquisition"
        }
        val cover = links.firstOrNull { it.rel?.contains(REL_IMAGE) == true }
            ?: links.firstOrNull { it.rel?.contains(REL_THUMBNAIL) == true }
            ?: links.firstOrNull { it.rel?.contains("x-stanza-cover-image") == true }
        return Entry(
            title = entry.children().firstOrNull { it.tagName() == "title" }?.text().orEmpty(),
            author = entry.selectFirst("author > name")?.text()?.takeIf { it.isNotBlank() },
            summary = (entry.selectFirst("content") ?: entry.selectFirst("summary"))
                ?.text()?.takeIf { it.isNotBlank() },
            coverUrl = cover?.href,
            acquisition = acquisition,
            navHref = nav?.href,
        )
    }

    /** 相对地址补全（OPDS 服务器普遍给相对 href）。 */
    fun abs(baseUrl: String, href: String): String {
        if (href.isBlank()) return href
        return runCatching { URI(baseUrl).resolve(href).toString() }.getOrDefault(href)
    }

    /** 搜索模板：`{searchTerms}` 是 OPDS 约定占位符。 */
    fun searchUrl(template: String, keyword: String): String =
        template.replace("{searchTerms}", java.net.URLEncoder.encode(keyword, "UTF-8"))
}
