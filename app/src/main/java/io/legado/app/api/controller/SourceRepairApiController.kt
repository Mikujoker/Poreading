package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.ui.book.source.repair.SourceRepairJournal
import io.legado.app.ui.book.source.repair.SourceRepairRunner
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.launch

/**
 * 「AI 修源」的调试接口：
 * - `POST /repairSource`：**直接跑修源循环**并返回完整报告（步骤/判据/结论），不点界面、不截图；
 * - `GET /getRepairJournal`：读 app 内「AI 修复」界面最近一次运行的状态。
 */
object SourceRepairApiController {

    /** 长任务（整源生成）后台跑：避免 HTTP 长连接被掐，进度写 journal 供轮询 */
    private val apiScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private fun reportMap(report: SourceRepairRunner.Report): Map<String, Any?> = linkedMapOf(
        "running" to false,
        "sourceUrl" to report.sourceUrl,
        "ok" to report.ok,
        "verdict" to report.verdict,
        "field" to report.field,
        "after" to report.after,
        "groupsDone" to report.rounds,
        "tokens" to report.tokens,
        "steps" to report.steps.map {
            linkedMapOf("title" to it.title, "detail" to it.detail, "ok" to it.ok)
        },
        "log" to report.log,
    )

    fun getJournal(): ReturnData = ReturnData().setData(SourceRepairJournal.snapshot())

    /**
     * body: {"source":"书源URL","key":"关键词或URL","field"?,"page"?,"rounds"?}
     */
    suspend fun repair(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("数据格式错误")
        val sourceUrl = body["source"]?.toString()?.trim().orEmpty()
        if (sourceUrl.isEmpty()) return ReturnData().setErrorMsg("source（书源URL）不能为空")
        val runner = SourceRepairRunner(
            bookSourceRepository = GlobalContext.get().get<BookSourceRepository>(),
            aiProfileGateway = GlobalContext.get().get<AiProfileGateway>(),
            aiTextGateway = GlobalContext.get().get<AiTextGateway>(),
        )
        val report = runner.run(
            request = SourceRepairRunner.Request(
                sourceUrl = sourceUrl,
                input = body["key"]?.toString()?.trim().orEmpty(),
                targetField = body["field"]?.toString()?.trim().orEmpty(),
                targetPage = body["page"]?.toString()?.trim().orEmpty(),
                maxRounds = (body["rounds"] as? Number)?.toInt() ?: SourceRepairRunner.MAX_ROUNDS,
            )
        )
        return ReturnData().setData(
            linkedMapOf(
                "sourceUrl" to report.sourceUrl,
                "ok" to report.ok,
                "verdict" to report.verdict,
                "field" to report.field,
                "after" to report.after,
                "rounds" to report.rounds,
                "tokens" to report.tokens,
                "steps" to report.steps.map {
                    linkedMapOf("title" to it.title, "detail" to it.detail, "ok" to it.ok)
                },
                "log" to report.log,
            )
        )
    }

    /**
     * body: {"site":"站点URL","key":"关键词","name"?,"attempts"?}
     * 生成/修复**整套书源**（搜索→详情→目录→正文），**每组一通过就写回**；需要登录时会弹内置浏览器。
     */
    suspend fun generate(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("数据格式错误")
        val site = body["site"]?.toString()?.trim().orEmpty()
        val keyword = body["key"]?.toString()?.trim().orEmpty()
        if (site.isEmpty() || keyword.isEmpty()) {
            return ReturnData().setErrorMsg("site（站点URL）与 key（关键词）都不能为空")
        }
        val runner = SourceRepairRunner(
            bookSourceRepository = GlobalContext.get().get<BookSourceRepository>(),
            aiProfileGateway = GlobalContext.get().get<AiProfileGateway>(),
            aiTextGateway = GlobalContext.get().get<AiTextGateway>(),
        )
        val request = SourceRepairRunner.GenerateRequest(
            siteUrl = site,
            keyword = keyword,
            sourceName = body["name"]?.toString()?.trim().orEmpty(),
            maxAttempts = (body["attempts"] as? Number)?.toInt() ?: 2,
        )
        if (body["wait"] as? Boolean == true) {
            return ReturnData().setData(reportMap(runner.generate(request)))
        }
        // 默认异步：后台跑，进度与结论写进 journal，用 GET /getRepairJournal 轮询
        val steps = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        apiScope.launch {
            try {
                val report = runner.generate(
                    request = request,
                    onStep = { step ->
                        steps += linkedMapOf("title" to step.title, "detail" to step.detail, "ok" to step.ok)
                        io.legado.app.ui.book.source.repair.SourceRepairJournal.publishApi(
                            linkedMapOf("running" to true, "sourceUrl" to site, "steps" to steps.toList())
                        )
                    },
                )
                io.legado.app.ui.book.source.repair.SourceRepairJournal.publishApi(reportMap(report))
            } catch (e: Exception) {
                io.legado.app.ui.book.source.repair.SourceRepairJournal.publishApi(
                    linkedMapOf(
                        "running" to false, "ok" to false,
                        "verdict" to "出错：${e.localizedMessage ?: e.javaClass.simpleName}",
                    )
                )
            }
        }
        return ReturnData().setData(
            linkedMapOf("started" to true, "hint" to "GET /getRepairJournal 轮询进度与结论")
        )
    }

    /**
     * body: {"source":"书源URL","loginUrl"?}
     * 只补齐**登录能力**字段（loginUrl/loginUi/loginCheckJs/cookieJar），不动任何规则。
     * 用途：你已经在 app 里登录过某站、只想让书源记住这件事（以后不再重复问）。
     */
    suspend fun fillLoginFields(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("数据格式错误")
        val sourceUrl = body["source"]?.toString()?.trim().orEmpty()
        if (sourceUrl.isEmpty()) return ReturnData().setErrorMsg("source（书源URL）不能为空")
        val repository = GlobalContext.get().get<BookSourceRepository>()
        val source = repository.getBookSource(sourceUrl)
            ?: return ReturnData().setErrorMsg("库里没有这个书源：$sourceUrl")
        val loginUrl = body["loginUrl"]?.toString()?.trim().orEmpty().ifEmpty { sourceUrl }
        val patched = io.legado.app.ui.book.source.repair.SourceRepairEngine.applyLoginCapability(source, loginUrl)
        repository.insert(patched)
        return ReturnData().setData(
            linkedMapOf(
                "loginUrl" to patched.loginUrl,
                "loginUi" to patched.loginUi,
                "loginCheckJs" to patched.loginCheckJs,
                "enabledCookieJar" to patched.enabledCookieJar,
                "changed" to (patched.loginUi != source.loginUi || patched.loginCheckJs != source.loginCheckJs ||
                    patched.loginUrl != source.loginUrl || patched.enabledCookieJar != source.enabledCookieJar),
            )
        )
    }

    /**
     * body: {"enabledOnly":true,"dryRun":false}
     * 批量补齐「有 loginUrl 但缺 loginUi / loginCheckJs / cookieJar」的书源 —— 这正是"每次都问登录"的根源。
     * 只动登录字段，不碰任何规则。
     */
    suspend fun fillAllLoginFields(postData: String?): ReturnData {
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
        val enabledOnly = body["enabledOnly"] as? Boolean ?: true
        val dryRun = body["dryRun"] as? Boolean ?: false
        val all = io.legado.app.data.appDb.bookSourceDao.all
        val candidates = all.filter { source ->
            (source.loginUrl?.isNotBlank() == true) && (!enabledOnly || source.enabled == true)
        }
        val toFix = mutableListOf<io.legado.app.data.entities.BookSource>()
        val samples = mutableListOf<String>()
        candidates.forEach { source ->
            val patched = io.legado.app.ui.book.source.repair.SourceRepairEngine.applyLoginCapability(
                source, source.loginUrl.orEmpty()
            )
            val changed = patched.loginUi != source.loginUi || patched.loginCheckJs != source.loginCheckJs ||
                patched.loginUrl != source.loginUrl || patched.enabledCookieJar != source.enabledCookieJar
            if (changed) {
                toFix += patched
                if (samples.size < 8) samples += source.bookSourceName
            }
        }
        if (!dryRun && toFix.isNotEmpty()) {
            io.legado.app.data.appDb.bookSourceDao.insert(*toFix.toTypedArray())
        }
        return ReturnData().setData(
            linkedMapOf(
                "totalSources" to all.size,
                "withLoginUrl" to candidates.size,
                "fixed" to toFix.size,
                "dryRun" to dryRun,
                "samples" to samples,
            )
        )
    }
}
