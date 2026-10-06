package io.legado.app.data.entities

/**
 * 规则完整性快照，专供书源健康度判定。
 *
 * `book_sources_part` 是 Room 的 DatabaseView，里面**没有**规则字段，而把 24528 条完整
 * 实体读进内存又太重，所以用一条只读规则是否为空列的 SQL 拿到这份轻量快照。
 */
data class SourceRuleFlags(
    val bookSourceUrl: String = "",
    /** ruleSearch 为空（缺搜索规则） */
    val searchBlank: Boolean = false,
    /** ruleToc 为空（缺目录规则） */
    val tocBlank: Boolean = false,
    /** ruleContent 为空（缺正文规则） */
    val contentBlank: Boolean = false,
)
