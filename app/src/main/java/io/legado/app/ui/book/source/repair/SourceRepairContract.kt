package io.legado.app.ui.book.source.repair

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** 可修字段：字段名决定端上断言，page 决定调试 key 的形式（detail 传 URL / search 传关键词 / ++toc / --content） */
enum class RepairTarget(val field: String, val page: String, val title: String) {
    SearchUrl("searchUrl", "search", "搜索·URL"),
    Auto("", "", "自动挑"),
    SearchBookList("ruleSearch.bookList", "search", "搜索·列表"),
    SearchBookUrl("ruleSearch.bookUrl", "search", "搜索·书链"),
    SearchName("ruleSearch.name", "search", "搜索·书名"),
    InfoName("ruleBookInfo.name", "detail", "详情·书名"),
    InfoAuthor("ruleBookInfo.author", "detail", "详情·作者"),
    InfoIntro("ruleBookInfo.intro", "detail", "详情·简介"),
    InfoCover("ruleBookInfo.coverUrl", "detail", "详情·封面"),
    InfoTocUrl("ruleBookInfo.tocUrl", "detail", "详情·目录链"),
    TocChapterList("ruleToc.chapterList", "toc", "目录·章节"),
    TocChapterUrl("ruleToc.chapterUrl", "toc", "目录·章链"),
    ContentText("ruleContent.content", "content", "正文·内容"),
}

@Immutable
data class RepairStep(
    val title: String,
    val detail: String,
    /** null = 进行中/信息，true = 通过，false = 不通过 */
    val ok: Boolean? = null,
)

@Stable
data class SourceRepairUiState(
    val loading: Boolean = true,
    val sourceName: String = "",
    val sourceUrl: String = "",
    val query: String = "",
    val target: RepairTarget = RepairTarget.Auto,
    val running: Boolean = false,
    val round: Int = 0,
    val maxRounds: Int = 3,
    val usedTokens: Int = 0,
    val steps: ImmutableList<RepairStep> = persistentListOf(),
    val verdict: String = "",
    val ok: Boolean? = null,
    val log: String = "",
)

sealed interface SourceRepairIntent {
    data class Load(val sourceUrl: String?) : SourceRepairIntent
    data class SetQuery(val value: String) : SourceRepairIntent
    data class SetTarget(val target: RepairTarget) : SourceRepairIntent
    data object Start : SourceRepairIntent
    data object Stop : SourceRepairIntent
    data object CopyLog : SourceRepairIntent
}

sealed interface SourceRepairEffect {
    data class ShowMessage(val message: String) : SourceRepairEffect
    data class CopyText(val text: String) : SourceRepairEffect
}
