package io.legado.app.model.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPDS(Atom) 解析的纯逻辑单测：不碰网络/数据库，跑 JVM 即可。
 * 样本取自 Calibre-Web / Komga 的典型输出形态（相对 href + 目录项 + 下载项 + next 分页）。
 */
class OpdsFeedTest {

    private val base = "http://192.168.1.9:8083/opds"

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <title>书库</title>
          <id>urn:uuid:1</id>
          <link rel="self" href="/opds" type="application/atom+xml"/>
          <link rel="next" href="/opds?page=2" type="application/atom+xml"/>
          <entry>
            <title>作者目录 / 鸣銮</title>
            <id>urn:uuid:nav-1</id>
            <link rel="subsection" href="/opds/author/1" type="application/atom+xml"/>
          </entry>
          <entry>
            <title>嫁姐</title>
            <author><name>鸣銮</name></author>
            <content type="text">甜文 55w</content>
            <link rel="http://opds-spec.org/image" href="/cover/1.jpg" type="image/jpeg"/>
            <link rel="http://opds-spec.org/acquisition/open-access"
                  href="/download/1/epub" type="application/epub+zip"/>
          </entry>
          <entry>
            <title>重生之心动</title>
            <link rel="http://opds-spec.org/image/thumbnail" href="/cover/2.jpg" type="image/jpeg"/>
            <link href="http://other.example.com/b/2.epub" type="application/epub+zip"/>
          </entry>
          <entry>
            <title>没有链接的项</title>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `解析标题与分页`() {
        val page = OpdsFeed.parse(feed, base)
        assertEquals("书库", page.title)
        assertEquals("http://192.168.1.9:8083/opds?page=2", page.nextHref)
    }

    @Test
    fun `目录项与下载项分开`() {
        val page = OpdsFeed.parse(feed, base)
        assertEquals(4, page.entries.size)
        assertEquals(1, page.navEntries.size)
        assertEquals(3, page.bookEntries.size)
        assertTrue(page.navEntries.first().isNav)
        assertEquals("http://192.168.1.9:8083/opds/author/1", page.navEntries.first().navHref)
    }

    @Test
    fun `相对地址补成绝对地址_封面与下载`() {
        val page = OpdsFeed.parse(feed, base)
        val book = page.bookEntries.first()
        assertEquals("嫁姐", book.title)
        assertEquals("鸣銮", book.author)
        assertEquals("甜文 55w", book.summary)
        assertEquals("http://192.168.1.9:8083/cover/1.jpg", book.coverUrl)
        assertEquals("application/epub+zip", book.acquisition?.type)
        assertEquals("http://192.168.1.9:8083/download/1/epub", book.acquisition?.href)
    }

    @Test
    fun `没有 rel 但 type 是 epub 也算下载_绝对地址保持原样`() {
        val page = OpdsFeed.parse(feed, base)
        val book = page.bookEntries[1]
        assertEquals("重生之心动", book.title)
        assertEquals("http://other.example.com/b/2.epub", book.acquisition?.href)
        // 缩略图作为封面兜底
        assertEquals("http://192.168.1.9:8083/cover/2.jpg", book.coverUrl)
    }

    @Test
    fun `只有标题的条目不当作书也不当作目录`() {
        val page = OpdsFeed.parse(feed, base)
        val item = page.entries.first { it.title == "没有链接的项" }
        assertNull(item.acquisition)
        assertNull(item.navHref)
        assertTrue(!item.isNav)
    }

    @Test
    fun `搜索模板替换 searchTerms`() {
        val url = OpdsFeed.searchUrl("http://host/opds/search?q={searchTerms}&page=1", "嫁 姐")
        assertEquals("http://host/opds/search?q=%E5%AB%81+%E5%A7%90&page=1", url)
    }

    @Test
    fun `没有 feed 包裹时也能解析`() {
        val bare = """
            <entry xmlns="http://www.w3.org/2005/Atom">
              <title>裸条目</title>
              <link rel="http://opds-spec.org/acquisition" href="a.epub" type="application/epub+zip"/>
            </entry>
        """.trimIndent()
        val page = OpdsFeed.parse(bare, base)
        assertEquals(1, page.entries.size)
        assertEquals("http://192.168.1.9:8083/a.epub", page.entries.first().acquisition?.href)
    }

    @Test
    fun `Gutenberg 形态_搜索结果给的是书级 feed 而不是直链`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Search Results</title>
              <entry>
                <title>Alice's Adventures in Wonderland</title>
                <link rel="alternate" type="application/atom+xml;profile=opds-catalog;kind=acquisition"
                      href="/ebooks/11.opds"/>
              </entry>
              <entry>
                <title>Authors</title>
                <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation"
                      href="/ebooks/authors/1.opds"/>
              </entry>
            </feed>
        """.trimIndent()
        val page = OpdsFeed.parse(xml, "https://www.gutenberg.org/ebooks/search.opds/?query=alice")
        assertEquals(1, page.bookEntries.size)
        assertEquals(1, page.navEntries.size)
        val book = page.bookEntries.first()
        assertTrue("书级 feed 也算可下载", book.isDownloadable)
        assertNull("搜索页没有直链", book.acquisition)
        assertEquals("https://www.gutenberg.org/ebooks/11.opds", book.bookFeedHref)
    }

    @Test
    fun `Gutenberg 书级 feed_挑直链时 epub 优先于 kindle`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Alice's Adventures in Wonderland</title>
              <entry>
                <title>Alice's Adventures in Wonderland</title>
                <link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition"
                      href="https://www.gutenberg.org/ebooks/11.kindle.noimages"/>
                <link type="application/epub+zip" rel="http://opds-spec.org/acquisition"
                      href="https://www.gutenberg.org/ebooks/11.epub.noimages"/>
              </entry>
            </feed>
        """.trimIndent()
        val page = OpdsFeed.parse(xml, "https://www.gutenberg.org/ebooks/11.opds")
        val picked = OpdsFeed.pickAcquisition(page)
        assertEquals("application/epub+zip", picked?.type)
        assertEquals("https://www.gutenberg.org/ebooks/11.epub.noimages", picked?.href)
    }
}
