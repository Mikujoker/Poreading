package io.legado.app.ui.book.source.repair

import io.legado.app.model.Debug
import org.jsoup.Jsoup
import io.legado.app.utils.fromJsonObject

/**
 * 修源引擎的纯逻辑部分：判据（断言）、失败字段自检、机械探针、提示词、语法预检。
 *
 * 与 PC 侧 `project/tools/ai_repair_source.py` 同一套判据；端上版本直接从 `Debug` 事件里取值。
 */
object SourceRepairEngine {

    private val CHALLENGE_MARKS = listOf(
        "Just a moment", "请稍候", "Attention Required", "cf-browser-verification", "_cf_chl_opt"
    )
    private val UI_WORDS = listOf("关灯", "字号", "章节报错", "上一章", "下一章", "加入书架", "推荐本书")
    private val TIME_PREFIX = Regex("^\\[\\d\\d:\\d\\d\\.\\d+\\] ")

    fun isChallenge(text: String): Boolean = CHALLENGE_MARKS.any { text.contains(it) }

    /** 登录墙判定：有密码框，或明确的「请先登录」话术（导航栏里的「登录」链接不算） */
    fun looksLikeLoginWall(html: String): Boolean {
        val hasPassword = html.contains("type=\"password\"") || html.contains("type='password'")
        // 注意：不能用「用户登录」当标记——它只是已登录页面的侧栏区块标题（会误判）
        val afterLogin = listOf("请先登录", "登录后可见", "登录后查看", "请登录后", "登录后才能")
            .any { html.contains(it) }
        if (!hasPassword) return afterLogin
        // 有密码框 ≠ 登录墙：已登录页面的侧栏常常就带着「用户登录」块
        if (listOf("退出登录", "logout", "个人中心", "我的书架").any { html.contains(it) }) return false
        val title = Regex("<title>([^<]{0,80})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1).orEmpty()
        if (Regex("login|登录|登陆", RegexOption.IGNORE_CASE).containsMatchIn(title)) return true
        // 很短还带密码框、又没有已登录迹象 → 基本就是登录页
        return html.length < 20_000
    }

    /** 需要人工介入（挑战页/登录墙/空壳页）；这类问题选择器救不了 */
    fun needsHuman(html: String?): Boolean =
        html == null || html.isBlank() || html.length < 400 || isChallenge(html) || looksLikeLoginWall(html)

    fun debugKey(page: String, key: String): String = when (page) {
        "toc" -> "++$key"
        "content" -> "--$key"
        else -> key
    }

    /** 把 Debug 事件拼成可解析的文本（去掉时间前缀，保留 ┌标签/└值 结构） */
    fun plainText(events: List<Debug.Event>): String =
        events.joinToString("\n") { TIME_PREFIX.replace(it.message, "") }

    fun valueBlock(text: String, label: String): String =
        Regex(Regex.escape(label) + "\\s*[└◇]([\\s\\S]*?)(?=\\n┌|\\Z)")
            .find(text)?.groupValues?.get(1).orEmpty()

    fun valueAfter(text: String, label: String): String {
        // 两种形态都要认：①「┌标签」下一行是「└值」；②「◇标签:值」（标记在标签前面）
        Regex("[└◇]\\s*" + Regex.escape(label) + "[:：]?([^\\n]*)").find(text)?.let {
            return it.groupValues[1].trim()
        }
        return Regex(Regex.escape(label) + "\\s*[└◇]([^\\n]*)")
            .find(text)?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun coverUrls(text: String): List<String> = valueBlock(text, "获取封面链接")
        .lines()
        .map { it.trim().removePrefix("└").trim() }
        .filter { it.startsWith("http", ignoreCase = true) }

    /** 字段 → 断言（确定性，不打折扣；挑战页一律判失败） */
    fun assertField(field: String, text: String): Pair<Boolean, String> {
        if (isChallenge(text)) return false to "引擎拿到的是人机验证页（Just a moment/请稍候），不是真页面"
        val label = when (field.substringAfterLast('.')) {
            "name" -> "获取书名"
            "author" -> "获取作者"
            "intro" -> "获取简介"
            "bookUrl" -> "获取详情页链接"
            "tocUrl" -> "获取目录链接"
            else -> ""
        }
        return when (field.substringAfterLast('.')) {
            "coverUrl" -> {
                val urls = coverUrls(text)
                if (urls.size != 1) {
                    // 0 个 = 没解析到；>1 个 = 多张封面拼成多行（无效 URL，封面会加载失败）
                    false to "封面链接必须唯一，实际 ${urls.size} 个：${urls.take(3)}"
                } else {
                    true to "封面 OK（唯一）：${urls.first().take(90)}"
                }
            }

            "bookList", "searchUrl" -> {
                // 站点把「唯一命中」302 到详情页时列表本就为空，Legado 会用详情页兜底解析出 1 本 —— 那也是成功
                val size = valueAfter(text, "列表大小")
                val total = valueAfter(text, "书籍总数")
                val ok = (size.toIntOrNull() ?: 0) > 0 || (total.toIntOrNull() ?: 0) >= 1
                ok to "列表大小=${size.ifEmpty { "空" }}，书籍总数=${total.ifEmpty { "空" }}"
            }

            "chapterList" -> {
                val total = valueAfter(text, "目录总数").ifEmpty { valueAfter(text, "列表大小") }
                ((total.toIntOrNull() ?: 0) > 0) to "目录总数=${total.ifEmpty { "空" }}"
            }

            "content" -> {
                val body = text.substringAfter("获取正文内容", text)
                val cjk = body.count { it.code in 0x4E00..0x9FFF }
                val junk = UI_WORDS.filter { body.contains(it) }
                (cjk > 200 && junk.isEmpty()) to "正文中文字数≈$cjk，含站点 UI 词=$junk"
            }

            else -> {
                val value = valueAfter(text, label)
                value.isNotEmpty() to "$label=${value.take(80)}"
            }
        }
    }

    /** 按页面类型给出待检查字段顺序，挑第一个失败的（用于"自动挑"） */
    fun detectOrder(page: String): List<String> = when (page) {
        "search" -> listOf(
            "ruleSearch.bookList", "ruleSearch.name", "ruleSearch.bookUrl",
            "ruleBookInfo.name", "ruleBookInfo.author", "ruleBookInfo.intro",
            "ruleBookInfo.coverUrl", "ruleBookInfo.tocUrl",
            "ruleToc.chapterList", "ruleToc.chapterUrl", "ruleContent.content"
        )

        "detail" -> listOf(
            "ruleBookInfo.name", "ruleBookInfo.author", "ruleBookInfo.intro",
            "ruleBookInfo.coverUrl", "ruleBookInfo.tocUrl",
            "ruleToc.chapterList", "ruleToc.chapterUrl", "ruleContent.content"
        )

        "toc" -> listOf("ruleToc.chapterList", "ruleToc.chapterUrl", "ruleContent.content")
        else -> listOf("ruleContent.content")
    }

    fun pickFailingField(page: String, text: String): String? =
        detectOrder(page).firstOrNull { !assertField(it, text).first }

    /** 本地语法预检：把常见规则语法错误挡在端上验证之前 */
    fun syntaxHint(value: String): String {
        if (value.startsWith("//") && Regex("@(src|href|text|html|ownText)$").containsMatchIn(value)) {
            return "XPath 的属性要写在路径里，末尾不能接 @ 后缀：`//a@href` → `//a/@href`"
        }
        if (value.count { it == '(' } != value.count { it == ')' }) return "圆括号不配对"
        if (value.count { it == '[' } != value.count { it == ']' }) return "方括号不配对"
        return ""
    }

    /** 机械探针：只列结构候选，不给结论 */
    fun probe(html: String, field: String): List<String> {
        if (html.isEmpty()) return listOf("（页面为空）")
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return listOf("（HTML 解析失败）")
        val out = mutableListOf<String>()
        val leaf = field.substringAfterLast('.')
        if (leaf == "coverUrl" || field.contains("img")) {
            val imgs = doc.select("img")
            out += "页面共 ${imgs.size} 个 <img>，前 8 个："
            out += imgs.take(8).map { "  ${it.outerHtml().take(150)}" }
            out += "含内联样式的容器（前 6 个）："
            out += doc.select("div[style]").take(6).map { "  <div style=\"${it.attr("style").take(80)}\">" }
        }
        if (leaf == "searchUrl") {
            out += "页面里的表单（action/method/字段）——搜索端点通常在这里："
            out += doc.select("form").take(5).map { form ->
                val fields = form.select("input,select").joinToString(",") { it.attr("name") }
                "  action=${form.attr("action")} method=${form.attr("method")} 字段=[$fields]"
            }
            out += "页面里含 search 关键字的 URL 片段："
            out += Regex("[\"'(](/[^\"'()]{0,60}search[^\"'()]{0,60})[\"')]")
                .findAll(html).map { it.groupValues[1] }.distinct().take(8).map { "  $it" }
            out += "全站可见的搜索链接："
            out += doc.select("a[href*=search]").take(6).map { "  ${it.attr("href").take(90)}" }
        }
        if (leaf == "bookList" || field.contains("Search")) {
            out += "链接最多的容器（标签:数量）："
            out += doc.select("a[href]").groupBy { it.parent()?.tagName() ?: "?" }
                .entries.sortedByDescending { it.value.size }.take(4)
                .map { "  ${it.key}: ${it.value.size}" }
            out += "前 8 个 <a>："
            out += doc.select("a[href]").take(8).map { "  ${it.outerHtml().take(140)}" }
        }
        if (leaf == "chapterList" || field.contains("toc")) {
            out += "前 10 个带文本链接（目录候选）："
            out += doc.select("a[href]").filter { it.text().isNotBlank() }.take(10)
                .map { "  href=${it.attr("href").take(60)} text=${it.text().take(24)}" }
        }
        if (leaf == "content") {
            out += "中文字数最多的 3 个块（前 100 字）："
            out += doc.select("div,p,article").sortedByDescending { block ->
                block.text().count { it.code in 0x4E00..0x9FFF }
            }.take(3).map { "  ${it.text().take(100)}" }
        }
        if (html.contains("search_guard") || html.contains("jieqiSearch") ||
            html.contains("__cf") || html.contains("challenge-platform")
        ) {
            out += "⚠️ 页面疑似由 JS 计算内容（含 search_guard / jieqiSearch / CF 标记）：" +
                "纯选择器可能永远拿不到列表，应优先考虑 @js: 规则或站点提供的 JS-free 端点（如 ?search_guard=css）"
        }
        if (out.isEmpty()) out += "（无专用探针）"
        return out
    }

    /** 裁剪 HTML：字段相关区域优先，避免整页 token 爆炸 */
    fun trimForLlm(html: String, field: String, limit: Int = 24_000): String {
        if (html.isEmpty()) return ""
        val leaf = field.substringAfterLast('.')
        return when {
            leaf == "searchUrl" -> runCatching {
                val forms = Regex("<form[\\s\\S]{0,800}?</form>").findAll(html).take(3)
                    .joinToString("\n----\n") { it.value }
                (forms + "\n\n" + html.take(6000)).take(limit)
            }.getOrDefault(html.take(limit))
            leaf == "coverUrl" || field.contains("img") ->
                Regex("<img[^>]*>").findAll(html)
                    .take(12)
                    .joinToString("\n----\n") { match ->
                        html.substring((match.range.first - 400).coerceAtLeast(0), (match.range.last + 120).coerceAtMost(html.length))
                    }.take(limit)

            leaf == "content" -> runCatching {
                Jsoup.parse(html).select("div,p,article")
                    .sortedByDescending { it.text().count { c -> c.code in 0x4E00..0x9FFF } }
                    .take(2).joinToString("\n----\n") { it.outerHtml() }.take(limit)
            }.getOrDefault(html.take(limit))

            else -> html.take(limit)
        }
    }

    fun systemPrompt(): String = buildString {
        append("你是 Legado（阅读）书源规则修复器。只输出一个 JSON 对象，字段固定为 ")
        append("{\"field\":\"...\",\"value\":\"...\",\"reason\":\"...\",\"evidence\":\"...\"}。\n")
        append("硬约束：只改指定的那一个字段，不得改动任何其他字段；不得编造 URL；")
        append("默认保持与当前值同一语法族、只做最小改动（当前是 CSS 就改 CSS，当前是 XPath 就改 XPath）。\n")
        append("Legado 规则语法（value 必须是下面三种之一）：\n")
        append("1) CSS：选择器 + 可选取值后缀 @text/@href/@src/@html/@ownText/@属性名；")
        append("如 `#content div[style*='99%'] img@src`、`a[href^='/book/']@href`（属性选择器要带引号；支持 :contains()/:has()/:not()）\n")
        append("2) XPath：以 // 开头，属性直接写在路径里，如 `//td[@width='20%']/img/@src`；")
        append("⚠️ 绝不能在末尾再接 @src/@href/@text（`//a@href` 是错的，应为 `//a/@href`）\n")
        append("3) JS：`@js:脚本`，脚本里可用 result/baseUrl/key 等绑定\n")
        append("4) searchUrl 专项：要给出「从关键词到取到搜索结果页」的完整 URL/选项。")
        append("⚠️ 能用 GET 就别用 POST：Legado 对 POST searchUrl 会**先用 OkHttp 发一次**，")
        append("CF 站会因此拿到人机验证页（应写 `/search?key={{key}}&page={{page}},{\"charset\":\"gbk\",\"webView\":true,\"webViewDelayTime\":3000}` 这种形态）；")
        append("POST 必须写成 `/x,{\"method\":\"POST\",\"body\":\"a={{key}}&b=1\",\"webView\":true}`。\n")
        append("如果这个页面根本没有该字段对应的元素，就返回空 value 并把原因写清楚。")
    }

    fun userPrompt(
        field: String,
        current: String,
        evidence: List<String>,
        history: List<String>,
        excerpt: String,
    ): String = buildString {
        append("要修的字段：$field\n当前值：${current.ifEmpty { "(空)" }}\n\n")
        append("机械探针给出的候选：\n").append(evidence.joinToString("\n")).append("\n\n")
        append("此前几轮已试过（不要重复）：\n")
        append(if (history.isEmpty()) "(无)" else history.joinToString("\n")).append("\n\n")
        if (history.isNotEmpty()) {
            append("⚠️ 上一轮已失败：必须换一种**真正不同**的写法（例如把 POST 换成 GET、")
            append("或改用 @js: 脚本、或换成站点真实存在的搜索地址），不要重复 history 里出现过的值。\n")
        }
        append("页面 HTML 片段（已裁剪）：\n```html\n").append(excerpt).append("\n```\n\n")
        append("请给出这一字段的新值。")
    }

    /** 从 LLM 输出里取 {value, reason} */
    fun parsePatch(text: String): Pair<String, String>? {
        val json = Regex("\\{[\\s\\S]*\\}").find(text)?.value ?: return null
        val parsed = runCatching {
            io.legado.app.utils.GSON.fromJsonObject<Map<String, Any?>>(json).getOrNull()
        }.getOrNull() ?: return null
        val value = parsed["value"]?.toString().orEmpty().trim()
        val reason = parsed["reason"]?.toString().orEmpty().trim()
        return value to reason
    }

    fun pageOf(field: String): String = when (field.substringBefore('.')) {
        "searchUrl" -> "search"
        "ruleSearch" -> "search"
        "ruleBookInfo" -> "detail"
        "ruleToc" -> "toc"
        "ruleContent" -> "content"
        else -> "detail"
    }

    fun pageLabel(page: String): String = when (page) {
        "search" -> "搜索页"
        "detail" -> "详情页"
        "toc" -> "目录页"
        "content" -> "正文页"
        else -> page
    }

    /** 相对地址 → 绝对地址（章节链接常是 `2.htm` 这种） */
    fun resolveUrl(base: String, url: String): String {
        if (url.startsWith("http", ignoreCase = true)) return url
        return runCatching { java.net.URI(base).resolve(url).toString() }.getOrDefault(url)
    }

    /** 从页面里找「搜索页」地址：优先带文本框的 form action，其次含 search 的链接 */
    fun findSearchPageUrl(html: String, baseUrl: String): String? {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val formAction = doc.select("form").firstOrNull { form ->
            form.select("input[type=text],input:not([type]),input[type=search]").isNotEmpty()
        }?.attr("action")?.takeIf { it.isNotBlank() }
        return (formAction ?: doc.select("a[href*=search]").firstOrNull()?.attr("href"))
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(baseUrl, it) }
    }

    /**
     * 搜索端点变体（确定性，不靠 LLM）：从当前 searchUrl 与页面线索里派生候选。
     * 典型场景：站点把结果交给 JS 计算（`search_guard=js`），页面同时提供了 `search_guard=css` 的降级形态。
     */
    fun searchUrlVariants(currentValue: String, pageHtml: String, baseUrl: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val suffix = ",{\"webView\":true,\"webViewDelayTime\":3000}"
        val raw = currentValue.substringBeforeLast(",{", currentValue).trim()
        val optionsJson = currentValue.substringAfterLast(",{", "").let { if (it.isEmpty()) "" else "{$it" }
        val options = runCatching {
            io.legado.app.utils.GSON.fromJsonObject<Map<String, Any?>>(optionsJson).getOrNull()
        }.getOrNull().orEmpty()
        val body = options["body"]?.toString().orEmpty()
        val method = options["method"]?.toString()?.uppercase().orEmpty()
        if (raw.startsWith("/") || raw.startsWith("http")) {
            if (method == "POST" || body.isNotEmpty()) {
                val keyPart = body.substringBefore("&").ifBlank { "searchkey={{key}}" }
                if (body.contains("search_guard")) {
                    out += "$raw,{\"method\":\"POST\",\"body\":\"${body.replace("search_guard=js", "search_guard=css")}\",\"webView\":true,\"webViewDelayTime\":3000}" to
                        "POST：把 search_guard 改成 css"
                } else {
                    out += "$raw,{\"method\":\"POST\",\"body\":\"$body&search_guard=css\",\"webView\":true,\"webViewDelayTime\":3000}" to
                        "POST：追加 search_guard=css"
                }
                out += "$raw?$keyPart&search_guard=css$suffix" to "GET：?$keyPart&search_guard=css"
            } else {
                out += "$raw?search_guard=css$suffix" to "GET：追加 search_guard=css"
            }
        }
        Regex("/(?:search|so)/[A-Za-z0-9_%\\-]{1,32}_\\{\\{page\\}\\}\\.html")
            .findAll(pageHtml).map { it.value }.distinct().take(2).forEach {
                out += "$it$suffix" to "页面里出现的搜索结果页形态"
            }
        findSearchPageUrl(pageHtml, baseUrl)?.takeIf { it.startsWith("http") }?.let { action ->
            out += "$action?searchkey={{key}}&search_guard=css$suffix" to "form action 的 GET 形态（带 css 降级）"
        }
        return out.distinctBy { it.first }.take(5)
    }

    data class SearchForm(
        val action: String,
        val method: String,
        val keyName: String,
        val extra: Map<String, String>,
    )

    /** 从页面里抠出搜索表单：action / method / 关键词字段名 / 隐藏字段 */
    fun searchForm(html: String, baseUrl: String): SearchForm? {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val form = doc.select("form").firstOrNull {
            it.select("input[type=text],input:not([type]),input[type=search]").isNotEmpty()
        } ?: return null
        val keyInput = form.select("input[type=text],input:not([type]),input[type=search]").first()
        val extra = form.select("input[type=hidden]").mapNotNull { input ->
            val name = input.attr("name")
            if (name.isBlank()) null else name to input.attr("value")
        }.toMap()
        return SearchForm(
            action = resolveUrl(baseUrl, form.attr("action").ifBlank { baseUrl }),
            method = form.attr("method").ifBlank { "GET" }.uppercase(),
            keyName = keyInput?.attr("name")?.takeIf { it.isNotBlank() } ?: "searchkey",
            extra = extra,
        )
    }

    /** 生成模式：一次要一整组规则（键必须在 fields 里），而不是单个字段 */
    fun generateSystemPrompt(fields: List<String>): String = buildString {
        append(systemPrompt())
        append("\n【生成模式】这次要输出**一整组规则**：只输出一个 JSON 对象，键只能是 ")
        append(fields.joinToString("、"))
        append("，值都是字符串规则；不确定的字段可以省略，但不要编造站点上没有的东西。")
    }

    fun generateUserPrompt(
        group: String,
        current: Map<String, String>,
        evidence: List<String>,
        excerpt: String,
        history: List<String>,
    ): String = buildString {
        append("目标规则组：$group\n现有规则：")
        append(if (current.isEmpty()) "(空)" else current.entries.joinToString("；") { "${it.key}=${it.value}" })
        append("\n\n机械探针给出的结构证据：\n").append(evidence.joinToString("\n"))
        append("\n\n此前失败过（不要重复）：\n")
        append(if (history.isEmpty()) "(无)" else history.joinToString("\n"))
        append("\n\n页面片段（已裁剪）：\n```html\n").append(excerpt).append("\n```\n\n")
        append("请输出这一组的 JSON。")
    }

    /** 解析「一整组规则」的 JSON（值统一转字符串） */
    fun parseGroup(text: String): Map<String, String>? {
        val json = Regex("\\{[\\s\\S]*\\}").find(text)?.value ?: return null
        val map = runCatching {
            io.legado.app.utils.GSON.fromJsonObject<Map<String, Any?>>(json).getOrNull()
        }.getOrNull() ?: return null
        return map.entries.mapNotNull { (k, v) ->
            val value = v?.toString()?.trim().orEmpty()
            if (value.isEmpty()) null else k to value
        }.toMap()
    }

    /** 默认登录检测（Rhino 与 WebView 都能跑，只用 indexOf）：返回响应对象=已登录 */
    const val DEFAULT_LOGIN_CHECK_JS: String =
        "result.body().indexOf('退出登录') >= 0 || result.body().indexOf('logout') >= 0 " +
            "|| result.body().indexOf('个人中心') >= 0 ? result : false"

    /** 默认登录表单（账号/密码）：写进 loginUi 后，读者端登录页可填 */
    const val DEFAULT_LOGIN_UI: String =
        "[{\"name\":\"账号\",\"type\":\"text\"},{\"name\":\"密码\",\"type\":\"password\"}]"

    /** 从页面里找登录页候选（链接或文本含 login/登录） */
    fun loginLinks(html: String, baseUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        return doc.select("a[href]").filter { a ->
            Regex("login|signin|登录|登陆", RegexOption.IGNORE_CASE)
                .containsMatchIn(a.attr("href") + a.text())
        }.map { it.attr("href") }
            .filter { it.isNotBlank() }
            .map { resolveUrl(baseUrl, it) }
            .distinct()
            .take(5)
    }

    /**
     * 登录能力：补齐 `loginUrl` / `loginUi` / `loginCheckJs` / `enabledCookieJar`。
     * 登录一次、长期复用（对齐 wenku8 的做法：字段 + cookieJar + 登录态检测）。
     */
    fun applyLoginCapability(source: io.legado.app.data.entities.BookSource, loginUrl: String)
            : io.legado.app.data.entities.BookSource {
        var result = source
        if (result.loginUrl.isNullOrBlank() && loginUrl.isNotBlank()) {
            result = result.copy(loginUrl = loginUrl)
        }
        if (result.loginUi.isNullOrBlank()) {
            result = result.copy(loginUi = DEFAULT_LOGIN_UI)
        }
        if (result.loginCheckJs.isNullOrBlank()) {
            result = result.copy(loginCheckJs = DEFAULT_LOGIN_CHECK_JS)
        }
        if (result.enabledCookieJar != true) {
            result = result.copy(enabledCookieJar = true)
        }
        return result
    }

    /** 某组规则的当前值（只保留非空） */
    fun groupValues(source: io.legado.app.data.entities.BookSource, group: String, fields: List<String>): Map<String, String> =
        fields.mapNotNull { leaf ->
            val value = currentValue(source, "$group.$leaf")
            if (value.isEmpty()) null else leaf to value
        }.toMap()

    /** 把 LLM 给的一整组规则写进源（逐字段 applyPatch，不碰其他字段） */
    fun applyGroup(source: io.legado.app.data.entities.BookSource, group: String, values: Map<String, String>)
            : io.legado.app.data.entities.BookSource {
        var result = source
        values.forEach { (leaf, value) -> result = applyPatch(result, "$group.$leaf", value) }
        return result
    }

    fun currentValue(source: io.legado.app.data.entities.BookSource, field: String): String {
        val leaf = field.substringAfterLast('.')
        val value: String? = when (field.substringBefore('.')) {
            "searchUrl" -> source.searchUrl
            "ruleSearch" -> source.ruleSearch?.let {
                when (leaf) {
                    "bookList" -> it.bookList
                    "name" -> it.name
                    "author" -> it.author
                    "intro" -> it.intro
                    "bookUrl" -> it.bookUrl
                    "coverUrl" -> it.coverUrl
                    else -> null
                }
            }

            "ruleBookInfo" -> source.ruleBookInfo?.let {
                when (leaf) {
                    "name" -> it.name
                    "author" -> it.author
                    "intro" -> it.intro
                    "coverUrl" -> it.coverUrl
                    "tocUrl" -> it.tocUrl
                    else -> null
                }
            }

            "ruleToc" -> source.ruleToc?.let {
                when (leaf) {
                    "chapterList" -> it.chapterList
                    "chapterUrl" -> it.chapterUrl
                    "chapterName" -> it.chapterName
                    else -> null
                }
            }

            "ruleContent" -> source.ruleContent?.let { if (leaf == "content") it.content else null }
            else -> null
        }
        return value.orEmpty()
    }

    /** 只改目标字段的副本（不动其他字段） */
    fun applyPatch(source: io.legado.app.data.entities.BookSource, field: String, value: String)
            : io.legado.app.data.entities.BookSource {
        val leaf = field.substringAfterLast('.')
        if (field == "searchUrl") return source.copy(searchUrl = value)
        return when (field.substringBefore('.')) {
            "ruleSearch" -> source.copy(
                ruleSearch = (source.ruleSearch ?: io.legado.app.data.entities.rule.SearchRule()).let {
                    when (leaf) {
                        "bookList" -> it.copy(bookList = value)
                        "name" -> it.copy(name = value)
                        "author" -> it.copy(author = value)
                        "intro" -> it.copy(intro = value)
                        "bookUrl" -> it.copy(bookUrl = value)
                        "coverUrl" -> it.copy(coverUrl = value)
                        else -> it
                    }
                }
            )

            "ruleBookInfo" -> source.copy(
                ruleBookInfo = (source.ruleBookInfo ?: io.legado.app.data.entities.rule.BookInfoRule()).let {
                    when (leaf) {
                        "name" -> it.copy(name = value)
                        "author" -> it.copy(author = value)
                        "intro" -> it.copy(intro = value)
                        "coverUrl" -> it.copy(coverUrl = value)
                        "tocUrl" -> it.copy(tocUrl = value)
                        else -> it
                    }
                }
            )

            "ruleToc" -> source.copy(
                ruleToc = (source.ruleToc ?: io.legado.app.data.entities.rule.TocRule()).let {
                    when (leaf) {
                        "chapterList" -> it.copy(chapterList = value)
                        "chapterUrl" -> it.copy(chapterUrl = value)
                        "chapterName" -> it.copy(chapterName = value)
                        else -> it
                    }
                }
            )

            "ruleContent" -> source.copy(
                ruleContent = (source.ruleContent ?: io.legado.app.data.entities.rule.ContentRule()).let {
                    if (leaf == "content") it.copy(content = value) else it
                }
            )

            else -> source
        }
    }
}
