package io.legado.app.api.controller

import android.webkit.CookieManager
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.help.http.CookieStore
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
}
