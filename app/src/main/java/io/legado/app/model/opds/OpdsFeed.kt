package io.legado.app.model.opds

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * OPDS(Atom) 目录解析：只做纯文本→结构，不碰网络/数据库，方便单测。
 *
 * 三种链接形态都要认（Calibre-Web / Kavita / Komga / Project Gutenberg 各占一种）：
 *  1. 直链下载：`rel="http://opds-spec.org/acquisition..."` 或 type 为 epub/pdf… 的 link
 *  2. **书级 feed**：`type="application/atom+xml;profile=opds-catalog;kind=acquisition"`
 *     （Gutenberg 搜索结果是这种两步结构：先进书级 feed 才有直链）
 *  3. 目录 feed：`type` 含 `application/atom+xml`（`kind=navigation` 或分组目录）
 */
object OpdsFeed {

    private const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
    private const val REL_IMAGE = "http://opds-spec.org/image"
    private const val REL_THUMBNAIL = "http://opds-spec.org/image/thumbnail"
    private const val TYPE_ATOM = "application/atom+xml"
    private const val KIND_ACQUISITION = "kind=acquisition"
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
        /** 直接可下载的资源。 */
        val acquisition: Link? = null,
        /** 书级 feed：还要再抓一层才有直链（Gutenberg 那种）。 */
        val bookFeedHref: String? = null,
        /** 目录 feed：点它进下一层。 */
        val navHref: String? = null,
    ) {
        /** 纯目录项（没有可下载的东西、也不是某本书的 feed）。 */
        val isNav get() = navHref != null && acquisition == null && bookFeedHref == null

        /** 这本书是否还能"点一下就能拿到内容"（直链或有书级 feed 可解析）。 */
        val isDownloadable get() = acquisition != null || bookFeedHref != null
    }

    data class Page(
        val title: String = "",
        val entries: List<Entry> = emptyList(),
        val nextHref: String? = null,
    ) {
        val navEntries get() = entries.filter { it.isNav }
        val bookEntries get() = entries.filter { !it.isNav }
    }

    /** 解析一页 OPDS。[baseUrl] 用于把相对 href 补成绝对地址。 */
    fun parse(xml: String, baseUrl: String): Page {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
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
        val atomLinks = links.filter { it.type?.contains(TYPE_ATOM) == true }
        // 同一本书常有多种格式（epub/kindle/pdf），全部收下再按偏好挑，别被第一个绑死
        val direct = links.filter {
            it.rel?.contains(REL_ACQUISITION) == true ||
                (it.type != null && BOOK_TYPES.any { t -> it.type.contains(t) })
        }
        val acquisition = preferred(direct)
        val bookFeed = atomLinks.firstOrNull { it.type?.contains(KIND_ACQUISITION) == true }
        val nav = atomLinks.firstOrNull {
            it.type?.contains(KIND_ACQUISITION) != true && it.rel != REL_ACQUISITION
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
            bookFeedHref = bookFeed?.href,
            navHref = nav?.href,
        )
    }

    /** 从一页里挑出"最值得下载"的资源：优先 epub，其次 pdf/mobi/txt。 */
    /** 从一页里挑出"最值得下载"的资源（先按同一条目的格式偏好，再看跨条目的）。 */
    fun pickAcquisition(page: Page): Link? =
        preferred(page.entries.mapNotNull { it.acquisition })

    /** 格式偏好：epub > pdf > mobi > txt > 其它。 */
    private fun preferred(candidates: List<Link>): Link? =
        candidates.firstOrNull { it.type?.contains("epub") == true }
            ?: candidates.firstOrNull { it.type?.contains("pdf") == true }
            ?: candidates.firstOrNull { it.type?.contains("mobi") == true }
            ?: candidates.firstOrNull { it.type?.startsWith("text/plain") == true }
            ?: candidates.firstOrNull()

    /** 相对地址补全（OPDS 服务器普遍给相对 href）。 */
    fun abs(baseUrl: String, href: String): String {
        if (href.isBlank()) return href
        return runCatching { URI(baseUrl).resolve(href).toString() }.getOrDefault(href)
    }

    /** 搜索模板：`{searchTerms}` 是 OPDS 约定占位符。 */
    fun searchUrl(template: String, keyword: String): String =
        template.replace("{searchTerms}", java.net.URLEncoder.encode(keyword, "UTF-8"))
}
