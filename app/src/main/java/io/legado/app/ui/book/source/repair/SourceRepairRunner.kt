package io.legado.app.ui.book.source.repair

import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiTaskPresetConfig
import io.legado.app.domain.model.AiTaskType
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 修源循环（与 UI 解耦）：出口自检 → 取页分级（挑战页/登录墙自动弹内置浏览器）→ 全链自检挑失败字段 →
 * 循环「探针 → LLM 单字段 patch → 端上真引擎断言」→ 只在通过后写回。
 *
 * 界面（`SourceRepairViewModel`）与 HTTP 接口（`POST /repairSource`）都调它，判据与预算完全一致：
 * 3 轮 / 10 分钟 / 200k tokens。
 */
class SourceRepairRunner(
    private val bookSourceRepository: BookSourceRepository,
    private val aiProfileGateway: AiProfileGateway,
    private val aiTextGateway: AiTextGateway,
) {

    /** 端上引擎需要 CoroutineScope；runner 每次运行一个实例，生命周期随调用方 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Request(
        val sourceUrl: String,
        val input: String = "",
        val targetField: String = "",
        val targetPage: String = "",
        val maxRounds: Int = MAX_ROUNDS,
    )

    data class Step(val title: String, val detail: String, val ok: Boolean?)

    data class Report(
        val sourceUrl: String,
        val ok: Boolean,
        val verdict: String,
        val field: String?,
        val after: String?,
        val rounds: Int,
        val tokens: Int,
        val steps: List<Step>,
        val log: String,
    )

    private class DebugResult(val text: String, val ok: Boolean, val html: String?)
    private class PatchResult(val value: String, val reason: String, val tokens: Int)

    suspend fun run(
        request: Request,
        onStep: (Step) -> Unit = {},
        onProgress: (round: Int, tokens: Int) -> Unit = { _, _ -> },
    ): Report {
        val current = withContext(Dispatchers.IO) {
            bookSourceRepository.getBookSource(request.sourceUrl)
        }
        val steps = mutableListOf<Step>()
        val log = StringBuilder()
        val history = mutableListOf<String>()
        var spent = 0
        var roundNo = 0
        var written: String? = null
        val startedAt = System.currentTimeMillis()
        // 端上引擎需要 CoroutineScope（与调用方生命周期绑定）
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

        fun step(title: String, detail: String, ok: Boolean?) {
            val item = Step(title, detail, ok)
            steps += item
            onStep(item)
        }

        fun report(ok: Boolean, verdict: String, field: String? = null): Report {
            log.append("结论：").append(verdict).append('\n')
            return Report(
                sourceUrl = request.sourceUrl,
                ok = ok,
                verdict = verdict,
                field = field,
                after = written,
                rounds = roundNo,
                tokens = spent,
                steps = steps.toList(),
                log = log.toString(),
            )
        }

        if (current == null) return report(false, "库里没有这个书源：${request.sourceUrl}")

        step("源", "${current.bookSourceName}\n${current.bookSourceUrl}", null)
        step("出口自检", egress(current), null)

        val input = request.input.ifBlank { current.bookSourceName }
        var page = request.targetPage.ifEmpty {
            if (input.startsWith("http")) "detail" else "search"
        }
        log.append("输入：").append(input).append("（").append(SourceRepairEngine.pageLabel(page)).append("）\n")

        val isUrl = input.startsWith("http")
        var dbg = runDebug(current, SourceRepairEngine.debugKey(page, input))
        if (dbg.text.isBlank()) {
            return report(false, "端上引擎没有产出日志（源可能已被禁用、或 searchUrl 的 JS 直接抛错）")
        }
        var pageHtml = if (isUrl) fetchHtmlWithUpgrade(current, input, ::step) else null
        if (!isUrl && SourceRepairEngine.needsHuman(dbg.html)) {
            // 关键词路径拿不到页面 → 弹内置浏览器登录，登录后重跑引擎
            fetchHtmlWithUpgrade(current, input, ::step)
            dbg = runDebug(current, SourceRepairEngine.debugKey(page, input))
            pageHtml = dbg.html
        } else {
            pageHtml = pageHtml ?: dbg.html
        }
        var homeFallback = false
        if (pageHtml.isNullOrBlank()) {
            val home = fetchHtml(current, current.bookSourceUrl, 3000)
            if (home.isNullOrBlank()) {
                return report(false, "取页失败：目标页只有 ${pageHtml?.length ?: 0} 字节，首页也取不到（站点可能已失效）")
            }
            step("取页失败 → 改抓首页", "${home.length} 字符，用于推断搜索端点", null)
            pageHtml = home
            homeFallback = true
        }
        step("取页（${SourceRepairEngine.pageLabel(page)}）", "${pageHtml.length} 字符", true)

        var target = request.targetField
        if (target.isEmpty() && homeFallback) {
            target = "searchUrl"
            page = "search"
        }
        if (target.isEmpty()) {
            target = SourceRepairEngine.pickFailingField(page, dbg.text).orEmpty()
        }
        if (target.isEmpty()) {
            step("全链自检", "未发现失败字段", true)
            return report(true, "这条链路当前是通的，没有需要修的字段")
        }
        page = request.targetPage.ifEmpty { SourceRepairEngine.pageOf(target) }
        val targetKey = when (page) {
            "detail" -> SourceRepairEngine.valueAfter(dbg.text, "获取详情页链接").ifEmpty { input }
            "toc", "content" -> SourceRepairEngine.valueAfter(dbg.text, "获取目录链接").ifEmpty { input }
            else -> input
        }
        step("目标字段", "$target\n（${SourceRepairEngine.pageLabel(page)}，key=${targetKey.take(80)}）", null)
        log.append("目标字段：").append(target).append('\n')

        // searchUrl 目标：探针页要用「搜索页」而不是首页（表单/真实端点都在那儿）
        var probeHtml = if (targetKey == input) pageHtml else (fetchHtmlWithUpgrade(current, targetKey, ::step) ?: pageHtml)
        if (target == "searchUrl") {
            val searchPage = SourceRepairEngine.findSearchPageUrl(probeHtml, current.bookSourceUrl)
            if (searchPage != null && searchPage != targetKey) {
                val fetched = fetchHtmlWithUpgrade(current, searchPage, ::step)
                if (!fetched.isNullOrBlank()) {
                    step("探针改抓搜索页", "$searchPage（${fetched.length} 字符）", true)
                    probeHtml = fetched
                }
            }
        }

        // 阶段 3.5：端点变体扫描（确定性、先于 LLM）。命中就直接成功——这类问题选择器救不了
        if (target == "searchUrl" || target.endsWith("bookList")) {
            val variants = SourceRepairEngine.searchUrlVariants(
                currentValue = SourceRepairEngine.currentValue(current, "searchUrl"),
                pageHtml = probeHtml,
                baseUrl = current.bookSourceUrl,
            )
            for ((variant, note) in variants) {
                if (variant == SourceRepairEngine.currentValue(current, "searchUrl")) continue
                step("端点变体", "$note\n${variant.take(150)}", null)
                val patched = SourceRepairEngine.applyPatch(current, "searchUrl", variant)
                val verify = runDebug(patched, SourceRepairEngine.debugKey(page, targetKey))
                val (ok, why) = SourceRepairEngine.assertField("searchUrl", verify.text)
                if (ok) {
                    withContext(Dispatchers.IO) { bookSourceRepository.insert(patched) }
                    written = variant
                    step("端点变体 验证", "✓ $why", true)
                    log.append("端点变体命中：").append(note).append(" → ").append(variant).append('\n')
                    return report(true, "修好了（端点变体）：searchUrl = ${variant.take(120)}", "searchUrl")
                }
                history += "变体失败 ${variant.take(70)} → $why"
            }
        }

        val preset = aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: return report(false, "没有可用的 AI 对话模型：先去「我的 → AI 设置」配一个")
        step("AI 模型", preset.model.displayName, null)

        var working = current
        for (round in 1..request.maxRounds) {
            roundNo = round
            onProgress(round, spent)
            if (System.currentTimeMillis() - startedAt > MAX_MINUTES * 60_000 || spent > MAX_TOKENS) {
                return report(false, "预算用尽（已跑 ${round - 1} 轮，$spent tokens）→ 需要人工介入", target)
            }
            if (round > 1) step("换策略（第 $round 轮）", "重新取页 + 重跑探针", null)
            val currentValue = SourceRepairEngine.currentValue(working, target)
            val patch = requestPatch(preset, target, currentValue, probeHtml, history, ::step) ?: continue
            spent += patch.tokens
            onProgress(round, spent)
            log.append("第").append(round).append("轮提案：").append(patch.value).append('\n')

            if (patch.value.isEmpty()) {
                history += "第${round}轮 空提案：${patch.reason.take(80)}"
                step("第 $round 轮", "LLM 给不出值（${patch.reason.take(80)}）", false)
                if (history.count { it.contains("空提案") } >= 2) {
                    return report(false, "该页面没有这个字段对应的元素（LLM 连续两轮给不出值）→ 保持现状", target)
                }
                continue
            }
            if (history.any { it.contains(patch.value.take(60)) }) {
                history += "第${round}轮 重复提案 ${patch.value.take(60)}（已试过，跳过端上验证）"
                step("第 $round 轮", "重复提案（已试过）→ 跳过验证，换策略", false)
                continue
            }
            val hint = SourceRepairEngine.syntaxHint(patch.value)
            if (hint.isNotEmpty()) {
                history += "第${round}轮 ${patch.value.take(60)} 语法错误：$hint"
                step("第 $round 轮", "本地语法预检不通过：$hint", false)
                continue
            }
            val patched = SourceRepairEngine.applyPatch(working, target, patch.value)
            step("第 $round 轮 提案", "新值：${patch.value.take(140)}\n理由：${patch.reason.take(160)}", null)
            val verify = runDebug(patched, SourceRepairEngine.debugKey(page, targetKey))
            val (ok, why) = SourceRepairEngine.assertField(target, verify.text)
            if (ok) {
                withContext(Dispatchers.IO) { bookSourceRepository.insert(patched) }
                written = patch.value
                step("第 $round 轮 端上验证", "✓ $why", true)
                log.append("第").append(round).append("轮验证：通过，").append(why).append('\n')
                log.append("已写回：").append(target).append(" = ").append(patch.value).append('\n')
                return report(true, "修好了：$target = ${patch.value.take(80)}", target)
            }
            // 把「这一轮引擎到底取到了什么」也回喂给 LLM：否则它只会围着同一写法打转
            val verifyExcerpt = verify.text.lines()
                .filter { it.isNotBlank() && !it.startsWith("<") }
                .takeLast(8).joinToString(" / ").take(600)
            history += "第${round}轮 试过 ${patch.value.take(80)} → 失败：$why｜引擎日志：$verifyExcerpt"
            step("第 $round 轮 端上验证", "✗ $why\n引擎日志：$verifyExcerpt", false)
            log.append("第").append(round).append("轮验证：失败，").append(why).append('\n')
        }
        return report(false, "${request.maxRounds} 轮未修好 → needs_human（每轮证据见 steps）", target)
    }

    data class GenerateRequest(
        val siteUrl: String,
        val keyword: String,
        val sourceName: String = "",
        val maxAttempts: Int = 2,
    )

    private data class GroupSpec(val group: String, val fields: List<String>, val probeField: String)

    /**
     * 生成/修复**整套书源**：给站点 URL + 关键词 → 首页/搜索页 → 搜索组 → 详情组 → 目录组 → 正文组，
     * **每组一通过就立刻写回**（不再"全通才写"）；需要登录时弹内置浏览器。
     */
    suspend fun generate(
        request: GenerateRequest,
        onStep: (Step) -> Unit = {},
        onProgress: (round: Int, tokens: Int) -> Unit = { _, _ -> },
    ): Report {
        val startedAt = System.currentTimeMillis()
        val steps = mutableListOf<Step>()
        val log = StringBuilder()
        var spent = 0
        var groupsDone = 0

        fun step(title: String, detail: String, ok: Boolean?) {
            val item = Step(title, detail, ok)
            steps += item
            onStep(item)
        }

        fun report(ok: Boolean, verdict: String): Report {
            log.append("结论：").append(verdict).append('\n')
            return Report(
                sourceUrl = request.siteUrl, ok = ok, verdict = verdict, field = null, after = null,
                rounds = groupsDone, tokens = spent, steps = steps.toList(), log = log.toString(),
            )
        }

        fun budgetLeft() = System.currentTimeMillis() - startedAt < MAX_MINUTES * 60_000 && spent < MAX_TOKENS

        val siteUrl = request.siteUrl.trim().trimEnd('/')
        if (siteUrl.isEmpty()) return report(false, "站点 URL 不能为空")
        val keyword = request.keyword.trim()
        if (keyword.isEmpty()) return report(false, "需要一个搜索关键词来验证搜索链（例如站上随便一本书的书名）")

        val existing = withContext(Dispatchers.IO) { bookSourceRepository.getBookSource(siteUrl) }
        var working = existing ?: BookSource(
            bookSourceName = request.sourceName.ifBlank {
                siteUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
            },
            bookSourceUrl = siteUrl,
        )
        step("站点", "$siteUrl\n${if (existing != null) "库里已有书源 → 逐组修复" else "库里没有 → 新建书源"}", null)
        step("出口自检", egress(working), null)
        val preset = aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: return report(false, "没有可用的 AI 对话模型（我的 → AI 设置）")
        step("AI 模型", preset.model.displayName, null)

        val home = fetchHtmlWithUpgrade(working, siteUrl, ::step)
            ?: return report(false, "首页取不到（站点失效或被挡）")
        step("首页", "${home.length} 字符", true)
        val searchPageUrl = SourceRepairEngine.findSearchPageUrl(home, siteUrl)
        val searchHtml = if (searchPageUrl != null && searchPageUrl != siteUrl) {
            fetchHtmlWithUpgrade(working, searchPageUrl, ::step) ?: home
        } else home
        val form = SourceRepairEngine.searchForm(searchHtml, siteUrl)
        step(
            "搜索表单",
            form?.let { "action=${it.action}\nmethod=${it.method} 关键词字段=${it.keyName} 隐藏字段=${it.extra.keys}" }
                ?: "没找到搜索表单（交给 LLM 推断）",
            form != null,
        )

        // ---- 组 1：searchUrl ----
        val tried = mutableListOf<String>()
        var searchOk = false
        val mechanical = buildList {
            form?.let { f ->
                val path = f.action.removePrefix(siteUrl).ifBlank { "/" }
                if (f.method == "POST") {
                    val body = (f.extra.map { "${it.key}=${it.value}" } + "${f.keyName}={{key}}").joinToString("&")
                    add("$path,{\"method\":\"POST\",\"body\":\"$body\",\"webView\":true,\"webViewDelayTime\":3000}")
                } else {
                    add("$path?${f.keyName}={{key}}&page={{page}},{\"webView\":true,\"webViewDelayTime\":3000}")
                }
            }
        }
        for (candidate in mechanical) {
            val patched = SourceRepairEngine.applyPatch(working, "searchUrl", candidate)
            val text = runDebug(patched, keyword).text
            val (ok, why) = SourceRepairEngine.assertField("searchUrl", text)
            step("searchUrl（机械表单）", "${candidate.take(150)}\n→ $why", ok)
            tried += "${candidate.take(60)} → $why"
            if (ok) {
                working = patched
                writeSource(working)
                searchOk = true
                break
            }
        }
        var attempt = 0
        while (!searchOk && attempt < request.maxAttempts && budgetLeft()) {
            attempt++
            val currentMap = mapOf("searchUrl" to SourceRepairEngine.currentValue(working, "searchUrl"))
            val asked = askGroup(preset, "searchUrl", listOf("searchUrl"), "ruleSearch.bookList", currentMap, searchHtml, tried, ::step)
                ?: continue
            spent += asked.tokens
            onProgress(attempt, spent)
            val value = asked.values["searchUrl"] ?: continue
            if (value == currentMap["searchUrl"]) {
                tried += "重复提案，跳过"
                continue
            }
            val patched = SourceRepairEngine.applyPatch(working, "searchUrl", value)
            val text = runDebug(patched, keyword).text
            val (ok, why) = SourceRepairEngine.assertField("searchUrl", text)
            step("searchUrl（LLM 第 $attempt 次）", "${value.take(150)}\n→ $why", ok)
            if (ok) {
                working = patched
                writeSource(working)
                searchOk = true
                break
            }
            tried += "${value.take(60)} → $why"
        }
        if (!searchOk) return report(false, "搜索组没通过 → 需要人工（可能是 JS 计算类搜索，或要登录）")
        groupsDone = 1
        step("✅ 搜索组已写回", SourceRepairEngine.currentValue(working, "searchUrl").take(150), true)

        val searchDbg = runDebug(working, keyword)
        val detailUrl = SourceRepairEngine.valueAfter(searchDbg.text, "获取详情页链接").takeIf { it.isNotBlank() }
            ?: return report(true, "搜索组已写回，但拿不到详情页链接（ruleSearch.bookUrl 需要单独修）")
        val detailHtml = fetchHtmlWithUpgrade(working, detailUrl, ::step)
            ?: return report(true, "搜索组已写回，详情页取不到")

        // ---- 组 2：详情 ----
        val infoSpec = GroupSpec("ruleBookInfo", listOf("name", "author", "intro", "coverUrl", "tocUrl"), "ruleBookInfo.coverUrl")
        val (afterInfo, infoOk) = generateGroup(
            preset, infoSpec, working, detailHtml, detailUrl,
            listOf("ruleBookInfo.name", "ruleBookInfo.author", "ruleBookInfo.coverUrl"),
            request.maxAttempts, ::step, { spent += it; onProgress(groupsDone + 1, spent) },
        )
        if (infoOk) {
            working = afterInfo
            writeSource(working)
            groupsDone = 2
            step("✅ 详情组已写回", infoSpec.fields.joinToString("、"), true)
        }

        val infoDbg = runDebug(working, detailUrl)
        val tocUrl = SourceRepairEngine.valueAfter(infoDbg.text, "获取目录链接").takeIf { it.isNotBlank() }
            ?: return report(infoOk, "详情${if (infoOk) "✓" else "✗"}，但没有目录链接（tocUrl 未生成）")
        val tocHtml = fetchHtmlWithUpgrade(working, tocUrl, ::step)
            ?: return report(infoOk, "详情${if (infoOk) "✓" else "✗"}，目录页取不到")

        // ---- 组 3：目录 ----
        val tocSpec = GroupSpec("ruleToc", listOf("chapterList", "chapterName", "chapterUrl"), "ruleToc.chapterList")
        val tocKey = SourceRepairEngine.debugKey("toc", tocUrl)
        val (afterToc, tocOk) = generateGroup(
            preset, tocSpec, working, tocHtml, tocKey,
            listOf("ruleToc.chapterList"),
            request.maxAttempts, ::step, { spent += it; onProgress(groupsDone + 1, spent) },
        )
        if (tocOk) {
            working = afterToc
            writeSource(working)
            groupsDone = 3
            step("✅ 目录组已写回", tocSpec.fields.joinToString("、"), true)
        }

        val tocDbg = runDebug(working, tocKey)
        val chapterRel = SourceRepairEngine.valueAfter(tocDbg.text, "章节链接")
        if (chapterRel.isBlank()) {
            return report(tocOk, "目录${if (tocOk) "✓" else "✗"}，但拿不到章节链接")
        }
        val chapterUrl = SourceRepairEngine.resolveUrl(tocUrl.substringBefore(",{"), chapterRel)
        val chapterHtml = fetchHtmlWithUpgrade(working, chapterUrl, ::step)
            ?: return report(tocOk, "目录${if (tocOk) "✓" else "✗"}，章节页取不到")

        // ---- 组 4：正文 ----
        val contentSpec = GroupSpec("ruleContent", listOf("content"), "ruleContent.content")
        val contentKey = SourceRepairEngine.debugKey("content", chapterUrl)
        val (afterContent, contentOk) = generateGroup(
            preset, contentSpec, working, chapterHtml, contentKey,
            listOf("ruleContent.content"),
            request.maxAttempts, ::step, { spent += it; onProgress(groupsDone + 1, spent) },
        )
        if (contentOk) {
            working = afterContent
            writeSource(working)
            groupsDone = 4
            step("✅ 正文组已写回", "ruleContent.content", true)
        }

        return report(
            searchOk && infoOk && tocOk && contentOk,
            "生成结束：搜索${if (searchOk) "✓" else "✗"} 详情${if (infoOk) "✓" else "✗"} " +
                "目录${if (tocOk) "✓" else "✗"} 正文${if (contentOk) "✓" else "✗"}（每组通过即已写回）",
        )
    }

    private suspend fun generateGroup(
        preset: AiTaskPresetConfig,
        spec: GroupSpec,
        source: BookSource,
        pageHtml: String,
        verifyKey: String,
        assertFields: List<String>,
        maxAttempts: Int,
        step: (String, String, Boolean?) -> Unit,
        onTokens: (Int) -> Unit,
    ): Pair<BookSource, Boolean> {
        var working = source
        val history = mutableListOf<String>()
        repeat(maxAttempts) { idx ->
            val asked = askGroup(
                preset, spec.group, spec.fields, spec.probeField,
                SourceRepairEngine.groupValues(working, spec.group, spec.fields),
                pageHtml, history, step,
            ) ?: return@repeat
            onTokens(asked.tokens)
            val patched = SourceRepairEngine.applyGroup(working, spec.group, asked.values)
            val text = runDebug(patched, verifyKey).text
            val checks = assertFields.map { it to SourceRepairEngine.assertField(it, text) }
            val ok = checks.all { it.second.first }
            step(
                "${spec.group}（第 ${idx + 1} 次）",
                checks.joinToString("\n") { "${it.first}: ${if (it.second.first) "✓" else "✗"} ${it.second.second.take(90)}" },
                ok,
            )
            if (ok) return patched to true
            history += "${asked.values} → ${checks.joinToString("；") { it.second.second.take(60) }}"
        }
        return working to false
    }

    private class GroupAnswer(val values: Map<String, String>, val tokens: Int)

    private suspend fun askGroup(
        preset: AiTaskPresetConfig,
        group: String,
        fields: List<String>,
        probeField: String,
        current: Map<String, String>,
        html: String,
        history: List<String>,
        step: (String, String, Boolean?) -> Unit,
    ): GroupAnswer? {
        val request = AiGenerateRequest(
            model = preset.model,
            messages = listOf(
                AiMessage(role = "system", content = SourceRepairEngine.generateSystemPrompt(fields)),
                AiMessage(
                    role = "user",
                    content = SourceRepairEngine.generateUserPrompt(
                        group = group,
                        current = current,
                        evidence = SourceRepairEngine.probe(html, probeField),
                        excerpt = SourceRepairEngine.trimForLlm(html, probeField),
                        history = history,
                    ),
                ),
            ),
            params = AiGenerationParams(temperature = 0.2f),
        )
        val text = withContext(Dispatchers.IO) { aiTextGateway.generate(request).getOrNull()?.text }.orEmpty()
        val tokens = (request.messages.sumOf { it.content.length } + text.length) / 4
        val values = SourceRepairEngine.parseGroup(text)
        if (values == null) {
            step("LLM（$group）", "没有给出可用 JSON：${text.take(160)}", false)
            return null
        }
        return GroupAnswer(values, tokens)
    }

    private suspend fun writeSource(source: BookSource) = withContext(Dispatchers.IO) {
        bookSourceRepository.insert(source)
    }

    /** 登录能力：补齐 loginUrl / loginUi / loginCheckJs / cookieJar —— 登录一次、长期复用（对齐 wenku8 的做法） */

    /** 人工登录成功后：把登录能力写回书源（这样读者端/后续运行都能复用，不会重复问你） */
    private suspend fun writeLoginCapability(sourceUrl: String, loginUrl: String) {
        val source = bookSourceRepository.getBookSource(sourceUrl) ?: return
        val patched = SourceRepairEngine.applyLoginCapability(source, loginUrl)
        if (patched != source) {
            withContext(Dispatchers.IO) { bookSourceRepository.insert(patched) }
        }
    }

    private suspend fun egress(source: BookSource): String {
        val html = fetchHtml(source, "https://cloudflare.com/cdn-cgi/trace", 0)
            ?: return "自检失败（取不到 trace）"
        val ip = Regex("^ip=(\\S+)", RegexOption.MULTILINE).find(html)?.groupValues?.get(1) ?: "?"
        val loc = Regex("^loc=(\\S+)", RegexOption.MULTILINE).find(html)?.groupValues?.get(1) ?: "?"
        val colo = Regex("^colo=(\\S+)", RegexOption.MULTILINE).find(html)?.groupValues?.get(1) ?: "?"
        return "ip=$ip loc=$loc colo=$colo"
    }

    /** 取页分级：WebView 抓 → 拉长延迟重试 → 仍不行（挑战页/登录墙/空壳页）就弹出内置浏览器人工登录后重试 */
    private suspend fun fetchHtmlWithUpgrade(
        source: BookSource,
        url: String,
        step: (String, String, Boolean?) -> Unit,
    ): String? {
        val target = if (url.startsWith("http", ignoreCase = true)) url else source.bookSourceUrl
        var html = fetchHtml(source, target, 3000)
        if (!SourceRepairEngine.needsHuman(html)) return html
        html = fetchHtml(source, target, 6000)
        if (!SourceRepairEngine.needsHuman(html)) return html

        val popupUrl = source.loginUrl?.takeIf { it.isNotBlank() }
            ?.let { SourceRepairEngine.resolveUrl(source.getKey(), it) } ?: target
        val reason = when {
            html == null -> "取不到页面"
            SourceRepairEngine.isChallenge(html) -> "命中人机验证"
            else -> "页面疑似登录墙/空壳页（${html.length} 字节）"
        }
        step("人工登录", "$reason → 已弹出内置浏览器，请在页面里完成登录\n（右上角 ✓ 提交，返回=取消）\n$popupUrl", null)
        val verified = withContext(Dispatchers.IO) {
            runCatching {
                SourceVerificationHelp.getVerificationResult(
                    source = source,
                    url = popupUrl,
                    title = "登录 ${source.bookSourceName}",
                    useBrowser = true,
                    refetchAfterSuccess = false,
                )
            }
        }
        if (verified.isFailure) {
            step("人工登录", "未完成：${verified.exceptionOrNull()?.localizedMessage}", false)
            return html
        }
        val retried = fetchHtml(source, target, 3000)
        // 登录是否真的落库：读 jar 里的 cookie 长度（0 = 没记住，别再让用户重复点）
        val cookieLen = runCatching { io.legado.app.help.http.CookieStore.getCookie(popupUrl).length }
            .getOrDefault(0)
        val usable = !SourceRepairEngine.needsHuman(retried)
        step(
            "人工登录后重试",
            if (usable) {
                "已拿到可用页面（${retried?.length} 字符）｜登录 cookie $cookieLen 字节"
            } else {
                "仍然拿不到可用页面｜登录 cookie $cookieLen 字节" +
                    if (cookieLen == 0) "（登录没落库，需要再登一次）" else "（登录已记住，问题在站点/端点）"
            },
            usable,
        )
        // 登录成功 → 把登录能力（loginUrl/loginUi/loginCheckJs/cookieJar）写回书源：登录一次长期复用
        writeLoginCapability(source.bookSourceUrl, popupUrl)
        step("登录能力已写回", "loginUrl=$popupUrl\nloginUi / loginCheckJs / cookieJar 已补齐（下次不用重登）", true)
        return retried ?: html
    }

    private suspend fun fetchHtml(source: BookSource, url: String, delayMs: Long): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val mUrl = if (url.contains(",{")) url else "$url,{\"webView\":true,\"webViewDelayTime\":$delayMs}"
                val analyzeUrl = AnalyzeUrl(
                    mUrl = mUrl,
                    baseUrl = source.getKey(),
                    source = source,
                    ruleData = RuleData(),
                    coroutineContext = currentCoroutineContext(),
                )
                analyzeUrl.getStrResponseAwait().body.orEmpty()
            }.getOrNull()
        }

    private suspend fun runDebug(source: BookSource, key: String): DebugResult {
        val events = mutableListOf<Debug.Event>()
        val session = Debug.startDebug(scope, source, key)
        withTimeoutOrNull(120_000) {
            session.events.collect { events.add(it) }
        }
        session.cancel()
        val text = SourceRepairEngine.plainText(events)
        val ok = events.isNotEmpty() && events.none { it.kind == Debug.EventKind.Error }
        val html = events.asSequence()
            .map { it.message }
            .filter { it.contains("<html", true) || it.contains("<!DOCTYPE", true) }
            .maxByOrNull { it.length }
        return DebugResult(text, ok, html)
    }

    private suspend fun requestPatch(
        preset: AiTaskPresetConfig,
        field: String,
        current: String,
        html: String,
        history: List<String>,
        step: (String, String, Boolean?) -> Unit,
    ): PatchResult? {
        val request = AiGenerateRequest(
            model = preset.model,
            messages = listOf(
                AiMessage(role = "system", content = SourceRepairEngine.systemPrompt()),
                AiMessage(
                    role = "user",
                    content = SourceRepairEngine.userPrompt(
                        field = field,
                        current = current,
                        evidence = SourceRepairEngine.probe(html, field),
                        history = history,
                        excerpt = SourceRepairEngine.trimForLlm(html, field),
                    ),
                ),
            ),
            params = AiGenerationParams(temperature = 0.2f),
        )
        val text = withContext(Dispatchers.IO) {
            aiTextGateway.generate(request).getOrNull()?.text
        }.orEmpty()
        val tokens = (request.messages.sumOf { it.content.length } + text.length) / 4
        val patch = SourceRepairEngine.parsePatch(text)
        if (patch == null) {
            step("LLM", "没有给出可用 JSON：${text.take(160)}", false)
            return null
        }
        return PatchResult(patch.first, patch.second, tokens)
    }

    companion object {
        const val MAX_ROUNDS = 3
        private const val MAX_MINUTES = 10
        private const val MAX_TOKENS = 200_000
    }
}
