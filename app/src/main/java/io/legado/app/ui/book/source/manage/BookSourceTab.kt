package io.legado.app.ui.book.source.manage

import androidx.annotation.StringRes
import io.legado.app.R

/**
 * 书源清单的三块。用户口径：启用是启用，常用是常用（≈收藏），两者互不影响。
 *
 * - [COMMON] 打了星标的那批，也是「刷新」唯一会校验的范围
 * - [FAILED] 常用里被校验判失败的那批 —— 所以失效永远是常用的子集
 * - [ALL] 全部
 */
enum class BookSourceTab {
    COMMON, FAILED, ALL;

    @StringRes
    fun labelRes(): Int = when (this) {
        COMMON -> R.string.source_tab_common
        FAILED -> R.string.source_tab_failed
        ALL -> R.string.all
    }
}

/** [favorite] 已叠加未落库的乐观值；[failedIds] 是本次会话内存里的校验失败集合。 */
fun BookSourceTab.matches(url: String, favorite: Boolean, failedIds: Set<String>): Boolean =
    when (this) {
        BookSourceTab.COMMON -> favorite
        BookSourceTab.FAILED -> favorite && url in failedIds
        BookSourceTab.ALL -> true
    }
