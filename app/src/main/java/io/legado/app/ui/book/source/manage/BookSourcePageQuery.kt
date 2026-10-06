package io.legado.app.ui.book.source.manage

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * 「全部」标签的翻页查询。
 *
 * 打开这页不再整表读 119 MB —— 靠 customOrder 索引只取前 [PAGE_SIZE] 行，滑到底再往后要。
 *
 * 筛选、搜索、排序都必须下推到 SQL：只在内存里过滤的话，看到的是「已加载的那一段」，
 * 「常用在第 20000 位」这种就会永远不出现，语义直接错掉。
 */
object BookSourcePageQuery {

    const val PAGE_SIZE = 200

    /**
     * 与 `book_sources_part` 视图同形的列清单。
     * 直接查表是因为搜索要能命中 `bookSourceComment`，那个列视图里没有。
     */
    private const val COLUMNS = "bookSourceUrl, bookSourceName, bookSourceGroup, customOrder, " +
        "enabled, enabledExplore, " +
        "(loginUrl is not null and trim(loginUrl) <> '') hasLoginUrl, lastUpdateTime, " +
        "respondTime, weight, " +
        "(exploreUrl is not null and trim(exploreUrl) <> '') hasExploreUrl, isFavorite"

    fun build(
        filter: String?,
        keyword: String,
        sort: BookSourceSort,
        ascending: Boolean,
        limit: Int,
    ): SupportSQLiteQuery {
        val (where, args) = whereOf(filter, keyword)
        val order = orderOf(sort, ascending)
        return SimpleSQLiteQuery(
            "select $COLUMNS from book_sources where $where $order limit $limit",
            args.toTypedArray(),
        )
    }

    /** 与 VM 里 `filterFor` 同一套语义，只是换成 SQL。 */
    private fun whereOf(filter: String?, keyword: String): Pair<String, List<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (filter) {
            BookSourceViewModel.FILTER_ENABLED -> parts += "enabled = 1"
            BookSourceViewModel.FILTER_DISABLED -> parts += "enabled = 0"
            BookSourceViewModel.FILTER_LOGIN ->
                parts += "(loginUrl is not null and trim(loginUrl) <> '')"

            BookSourceViewModel.FILTER_NO_GROUP ->
                parts += "(bookSourceGroup is null or bookSourceGroup = '' " +
                    "or bookSourceGroup like '%未分组%')"

            BookSourceViewModel.FILTER_ENABLED_EXPLORE -> parts += "enabledExplore = 1"
            BookSourceViewModel.FILTER_DISABLED_EXPLORE -> parts += "enabledExplore = 0"
            else -> if (filter != null && filter.startsWith(BookSourceViewModel.PREFIX_GROUP)) {
                val group = filter.removePrefix(BookSourceViewModel.PREFIX_GROUP)
                parts += "(bookSourceGroup = ? or bookSourceGroup like ? " +
                    "or bookSourceGroup like ? or bookSourceGroup like ?)"
                args += listOf(group, "$group,%", "%,$group", "%,$group,%")
            }
        }
        if (keyword.isNotBlank()) {
            parts += "(bookSourceName like ? or bookSourceGroup like ? " +
                "or bookSourceUrl like ? or bookSourceComment like ?)"
            val like = "%$keyword%"
            args += listOf(like, like, like, like)
        }
        return (parts.joinToString(" and ").ifBlank { "1" }) to args
    }

    /**
     * 行尾一定带 `bookSourceUrl` 兜底：排序键相同时，两次分页查询的顺序必须一致，
     * 否则翻页会漏行或重复行。
     */
    private fun orderOf(sort: BookSourceSort, ascending: Boolean): String {
        val column = when (sort) {
            BookSourceSort.Name -> "bookSourceName"
            BookSourceSort.Url -> "bookSourceUrl"
            BookSourceSort.Weight -> "weight"
            BookSourceSort.Update -> "lastUpdateTime"
            BookSourceSort.Respond -> "respondTime"
            BookSourceSort.Enable -> "enabled"
            BookSourceSort.Default -> "customOrder"
        }
        val direction = if (ascending) "asc" else "desc"
        return "order by $column $direction, bookSourceUrl asc"
    }
}
