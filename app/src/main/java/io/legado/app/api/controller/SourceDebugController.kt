package io.legado.app.api.controller

import android.webkit.CookieManager
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Web 服务侧的端上调试入口：跑真引擎并把结构化事件一次返回，替代「点 ▶ 再截图」。
 */
object SourceDebugController {

    private const val DEFAULT_TIMEOUT_MS = 120_000L

    /**
     * body: {"tag":"书源URL","key":"关键词|书籍URL|++目录URL|--章节URL","timeoutMs":120000}
     */
    suspend fun debugSource(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("数据格式错误")
        val tag = body["tag"]?.toString()?.trim()
        val key = body["key"]?.toString()?.trim()
        if (tag.isNullOrEmpty() || key.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("tag(书源URL)与key(关键词或URL)不能为空")
        }
        val source = appDb.bookSourceDao.getBookSource(tag)
            ?: return ReturnData().setErrorMsg("未找到书源: $tag")
        val timeoutMs = (body["timeoutMs"] as? Number)?.toLong()?.takeIf { it > 0 }
            ?: DEFAULT_TIMEOUT_MS

        val startTime = System.currentTimeMillis()
        // 与 app 内调试页共用 Debug 的单会话语义：这次调用会替换掉正在跑的调试会话
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = Debug.startDebug(scope, source, key)
        val events = arrayListOf<Debug.Event>()
        val finished = withTimeoutOrNull(timeoutMs) {
            session.events.collect { events.add(it) }
            true
        } ?: false
        if (!finished) session.cancel()
        scope.cancel()

        val error = events.lastOrNull { it.kind == Debug.EventKind.Error }?.message
        return ReturnData().setData(
            linkedMapOf(
                "tag" to tag,
                "key" to key,
                "ok" to (finished && error == null),
                "timeout" to !finished,
                "elapsedMs" to System.currentTimeMillis() - startTime,
                "eventCount" to events.size,
                "error" to (error ?: ""),
                "events" to events.map {
                    linkedMapOf(
                        "kind" to it.kind.name,
                        "elapsedMs" to it.elapsedMillis,
                        "message" to it.message,
                    )
                },
            )
        )
    }

    /**
     * 返回同一 URL 的 WebView cookie、书源 cookie jar 与落库值，用来判定登录状态存在哪一层。
     */
    suspend fun getCookie(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()?.trim()
        if (url.isNullOrEmpty()) return ReturnData().setErrorMsg("参数url不能为空")
        val domain = NetworkUtils.getSubDomain(url)
        val webViewCookie = withContext(Dispatchers.Main) {
            CookieManager.getInstance().getCookie(url)
        }
        return ReturnData().setData(
            linkedMapOf(
                "url" to url,
                "domain" to domain,
                "webViewCookie" to webViewCookie.orEmpty(),
                "jarCookie" to CookieStore.getCookie(url),
                "dbCookie" to (appDb.cookieDao.get(domain)?.cookie ?: ""),
            )
        )
    }

    /**
     * 拉起内置浏览器做人工验证/登录（过 CF 挑战、输账号密码都行），完成后回传页面地址与 cookie 状态。
     * 用户在内置浏览器里按「保存」会把当前页面 HTML 交回；直接返回则视为取消。
     * 会阻塞到用户完成，因此必须在非主线程调用（这里放 IO）。
     */
    suspend fun verifyLogin(parameters: Map<String, List<String>>): ReturnData {
        val sourceUrl = parameters["source"]?.firstOrNull()?.trim()
            ?: return ReturnData().setErrorMsg("参数source（书源URL）不能为空")
        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: return ReturnData().setErrorMsg("未找到书源: $sourceUrl")
        val url = parameters["url"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: sourceUrl
        val title = parameters["title"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "验证/登录 ${source.bookSourceName}"
        val maxChars = parameters["maxChars"]?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: 0
        val result = withContext(Dispatchers.IO) {
            runCatching {
                SourceVerificationHelp.getVerificationResult(
                    source = source,
                    url = url,
                    title = title,
                    useBrowser = true,
                    refetchAfterSuccess = false,
                )
            }
        }
        // 人工登录产生的 cookie 由内置浏览器在 onPageFinished 落库；这里回读做「是否真的记住了」的判据
        val cookie = CookieStore.getCookie(url)
        return result.fold(
            onSuccess = { (finalUrl, html) ->
                ReturnData().setData(
                    linkedMapOf<String, Any>(
                        "url" to finalUrl,
                        "htmlLength" to html.length,
                        "cookie" to cookie,
                        "html" to if (maxChars > 0) html.take(maxChars) else "",
                    )
                )
            },
            onFailure = {
                ReturnData().setErrorMsg("人工验证未完成（取消或被关闭）：${it.localizedMessage ?: it.javaClass.simpleName}")
            }
        )
    }

    /**
     * 用 app 的 WebView 抓一个 URL 的 HTML（带书源的 header/cookie，因此能过 CF），返回最终地址与正文。
     * 这是「AI 修源」爬页面的原语；只支持 GET。
     */
    suspend fun fetchPage(parameters: Map<String, List<String>>): ReturnData {
        val rawUrl = parameters["url"]?.firstOrNull()?.trim()
        if (rawUrl.isNullOrEmpty()) return ReturnData().setErrorMsg("参数url不能为空")
        val source = parameters["source"]?.firstOrNull()?.trim()
            ?.let { appDb.bookSourceDao.getBookSource(it) }
        val useWebView = parameters["webView"]?.firstOrNull() != "0"
        val delayTime = parameters["delay"]?.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: 3000L
        val maxChars = parameters["maxChars"]?.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: 200_000
        val analyzeUrl = AnalyzeUrl(
            mUrl = "$rawUrl,{\"webView\":$useWebView,\"webViewDelayTime\":$delayTime}",
            baseUrl = source?.getKey() ?: rawUrl,
            source = source,
            ruleData = RuleData(),
            coroutineContext = currentCoroutineContext(),
        )
        val response = runCatching { withContext(Dispatchers.IO) { analyzeUrl.getStrResponseAwait() } }
            .getOrElse { return ReturnData().setErrorMsg("取页失败：${it.localizedMessage ?: it.javaClass.simpleName}") }
        val body = response.body.orEmpty()
        return ReturnData().setData(
            linkedMapOf<String, Any>(
                "url" to rawUrl,
                "finalUrl" to response.url,
                "length" to body.length,
                "truncated" to (body.length > maxChars),
                "html" to body.take(maxChars),
            )
        )
    }
}
