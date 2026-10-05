package io.legado.app.ui.book.source.manage

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SourceRuleFlags
import io.legado.app.utils.GSON

/**
 * 书源健康度，用于「书源管理」的五级分类与统计。
 * 判定顺序即优先级。
 */
enum class SourceHealth {
    /** 在用：启用中、规则齐全、响应正常 */
    ACTIVE,

    /** 待观察：已禁用，但未被判定要冻结 */
    IDLE,

    /** 冷冻：能读但响应过慢 */
    FROZEN,

    /** 待修：启用中但规则不全（缺搜索 / 目录 / 正文） */
    NEED_FIX,

    /** 坏源：URL 取不到域名 */
    BROKEN,
}

/** 响应时间超过这个值算「慢源」 */
const val SLOW_RESPOND_MS = 8000L

/**
 * 未测过的哨兵值。实体默认就是 180000（3 分钟），搜索超时也会写这么大的值。
 * 「未测」不能当成「慢」—— 否则两万多条从没体检过的书源会被整体误判成慢源。
 */
const val NEVER_MEASURED_RESPOND_MS = 180_000L

/** 核心判定：只依赖传入的事实，不绑定具体实体类型。 */
@Suppress("LongParameterList")
fun resolveSourceHealth(
    hasHost: Boolean,
    enabled: Boolean,
    respondTime: Long,
    searchBlank: Boolean,
    tocBlank: Boolean,
    contentBlank: Boolean,
): SourceHealth {
    if (!hasHost) return SourceHealth.BROKEN
    if (!enabled) return SourceHealth.IDLE
    if (searchBlank || tocBlank || contentBlank) return SourceHealth.NEED_FIX
    // 只在「确实测出慢」时才判冷冻：未测（<=1 或默认哨兵值）一律不算慢
    if (respondTime in (SLOW_RESPOND_MS + 1) until NEVER_MEASURED_RESPOND_MS) {
        return SourceHealth.FROZEN
    }
    return SourceHealth.ACTIVE
}

/** 列表用：DatabaseView 的 [BookSourcePart] + 规则快照。 */
fun BookSourcePart.health(
    flags: SourceRuleFlags?,
    enabledOverride: Boolean? = null,
): SourceHealth = resolveSourceHealth(
    hasHost = hasHost(bookSourceUrl),
    enabled = enabledOverride ?: enabled,
    respondTime = respondTime,
    searchBlank = flags?.searchBlank ?: false,
    tocBlank = flags?.tocBlank ?: false,
    contentBlank = flags?.contentBlank ?: false,
)

/** 完整实体用（编辑页等拿到 [BookSource] 的场景）。 */
fun BookSource.health(enabledOverride: Boolean? = null): SourceHealth = resolveSourceHealth(
    hasHost = hasHost(bookSourceUrl),
    enabled = enabledOverride ?: enabled,
    respondTime = respondTime,
    searchBlank = isBlankRule(ruleSearch),
    tocBlank = isBlankRule(ruleToc),
    contentBlank = isBlankRule(ruleContent),
)

/**
 * 是否取得到 host。这里**故意**不用 NetworkUtils.getSubDomainOrNull：
 * 那要走 URL 解析和 PublicSuffixDatabase 查询，两万多条书源每次重算要跑几万次，
 * 是「书源管理」卡顿的主要来源。判「坏源」只需要知道有没有 scheme 和 authority。
 */
private fun hasHost(url: String): Boolean {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return false
    val authority = url.substring(schemeEnd + 3)
        .substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.isNotBlank()
}

/** 规则对象字段全空时序列化结果就是 "{}"，用它做通用判空，避免逐条罗列字段。 */
private fun isBlankRule(rule: Any?): Boolean =
    rule == null || GSON.toJson(rule).let { it == "{}" || it == "null" }
