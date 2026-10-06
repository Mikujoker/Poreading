package io.legado.app.web

import android.graphics.Bitmap
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.readAvailable
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.api.controller.SourceDebugController
import io.legado.app.api.controller.AiProfileController
import io.legado.app.api.controller.SourceRepairApiController
import io.legado.app.model.localBook.LocalBook
import io.legado.app.service.WebService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.socket.BookSearchWebSocket
import io.legado.app.web.socket.BookSourceDebugWebSocket
import io.legado.app.web.socket.RssSourceDebugWebSocket
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import io.legado.app.R

class KtorServer(private val port: Int) {
    private var server: EmbeddedServer<*, *>? = null
    private var wsServer: EmbeddedServer<*, *>? = null
    private val assetsWeb = AssetsWeb("web")

    fun start() {
        ensurePortFree(port)
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                gson {
                    setLenient()
                }
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }

            routing {
                post("/saveBookSource") { handlePost { BookSourceController.saveSource(it) } }
                post("/debugBookSource") { handlePost { SourceDebugController.debugSource(it) } }
                post("/saveBookSources") { handlePost { BookSourceController.saveSources(it) } }
                post("/deleteBookSources") { handlePost { BookSourceController.deleteSources(it) } }
                post("/saveBook") { handlePost { BookController.saveBook(it) } }
                post("/deleteBook") { handlePost { BookController.deleteBook(it) } }
                post("/saveBookProgress") { handlePost { BookController.saveBookProgress(it) } }
                post("/addLocalBook") {
                    WebService.serve()
                    val multipart = call.receiveMultipart()
                    var fileName: String? = null
                    val tempFile = File(appCtx.cacheDir, "upload_${System.currentTimeMillis()}")
                    try {
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "fileName") fileName = part.value
                                }
                                is PartData.FileItem -> {
                                    val channel = part.provider()
                                    tempFile.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val bytesRead = channel.readAvailable(buffer)
                                            if (bytesRead == -1) break
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                    if (fileName == null) {
                                        fileName = part.originalFileName
                                    }
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                        if (fileName != null && tempFile.exists()) {
                            val returnData = withContext(Dispatchers.IO) {
                                kotlin.runCatching {
                                    tempFile.inputStream().use {
                                        val uri = LocalBook.saveBookFile(it, fileName!!)
                                        LocalBook.importFile(uri)
                                        ReturnData().setData(true)
                                    }
                                }.getOrElse {
                                    LogUtils.e(TAG, it.stackTraceStr)
                                    ReturnData().setErrorMsg(it.localizedMessage ?: "Save book error")
                                }
                            }
                            respondReturnData(returnData)
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "Missing fileName or fileData")
                        }
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
                post("/saveReadConfig") { handlePost { BookController.saveWebReadConfig(it) } }
                post("/saveRssSource") { handlePost { RssSourceController.saveSource(it) } }
                post("/saveRssSources") { handlePost { RssSourceController.saveSources(it) } }
                post("/deleteRssSources") { handlePost { RssSourceController.deleteSources(it) } }
                post("/saveReplaceRule") { handlePost { ReplaceRuleController.saveRule(it) } }
                post("/deleteReplaceRule") { handlePost { ReplaceRuleController.delete(it) } }
                post("/testReplaceRule") { handlePost { ReplaceRuleController.testRule(it) } }

                get("/getBookSource") { handleGet { BookSourceController.getSource(it) } }
                get("/getBookSources") { handleGet { BookSourceController.sources } }
                get("/getBookshelf") { handleGet { BookController.bookshelf } }
                get("/getChapterList") { handleGet { BookController.getChapterList(it) } }
                get("/refreshToc") { handleGet { BookController.refreshToc(it) } }
                get("/getBookContent") { handleGet { BookController.getBookContent(it) } }
                get("/cover") { handleGet { BookController.getCover(it) } }
                get("/image") { handleGet { BookController.getImg(it) } }
                get("/getReadConfig") { handleGet { BookController.getWebReadConfig() } }
                get("/getRssSource") { handleGet { RssSourceController.getSource(it) } }
                get("/getRssSources") { handleGet { RssSourceController.sources } }
                get("/getReplaceRules") { handleGet { ReplaceRuleController.allRules } }
                get("/getCookie") { handleGet { SourceDebugController.getCookie(it) } }
                get("/getRepairJournal") { handleGet { SourceRepairApiController.getJournal() } }
                post("/repairSource") { handlePost { SourceRepairApiController.repair(it) } }
                post("/generateSource") { handlePost { SourceRepairApiController.generate(it) } }
                post("/fillLoginFields") { handlePost { SourceRepairApiController.fillLoginFields(it) } }
                post("/fillAllLoginFields") { handlePost { SourceRepairApiController.fillAllLoginFields(it) } }
                post("/saveAiProfile") { handlePost { AiProfileController.saveDefaultChat(it) } }
                get("/getAiProfile") { handleGet { AiProfileController.getDefaultChat() } }
                get("/verifyLogin") { handleGet { SourceDebugController.verifyLogin(it) } }
                get("/fetchPage") { handleGet { SourceDebugController.fetchPage(it) } }

                get("{...}") {
                    WebService.serve()
                    var uri = call.request.path()
                    if (uri.endsWith("/")) uri += "index.html"
                    val inputStream = assetsWeb.getInputStream(uri)
                    if (inputStream != null) {
                        inputStream.use { stream ->
                            call.respondOutputStream(ContentType.parse(assetsWeb.getMimeType(uri))) {
                                stream.copyTo(this)
                            }
                        }
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
    }

    fun startWebSocket(wsPort: Int) {
        ensurePortFree(wsPort)
        wsServer = embeddedServer(CIO, port = wsPort) {
            install(WebSockets)
            routing {
                webSocket("/bookSourceDebug") {
                    BookSourceDebugWebSocket(this).handle()
                }
                webSocket("/rssSourceDebug") {
                    RssSourceDebugWebSocket(this).handle()
                }
                webSocket("/searchBook") {
                    BookSearchWebSocket(this).handle()
                }
            }
        }.start(wait = false)
    }

    /**
     * Ktor(CIO) 把端口绑定放在引擎自己的协程里，绑定失败的 BindException 会在那里直接崩掉进程，
     * 外层的 try/catch 接不到，所以启动前先用原生 Socket 探一次，把失败变成可被捕获的异常。
     */
    private fun ensurePortFree(port: Int) {
        try {
            ServerSocket().use { it.bind(InetSocketAddress(port)) }
        } catch (e: IOException) {
            throw BindException(appCtx.getString(R.string.web_service_port_occupied, port))
        }
    }

    fun stop() {
        server?.stop(0, 0)
        wsServer?.stop(0, 0)
    }

    private suspend fun RoutingContext.handlePost(
        block: suspend (String?) -> ReturnData
    ) {
        WebService.serve()
        try {
            val postData = call.receiveText()
            val returnData = block(postData)
            respondReturnData(returnData)
        } catch (e: Exception) {
            LogUtils.e(TAG, e.stackTraceStr)
            call.respondText(e.message ?: "Unknown error")
        }
    }

    private suspend fun RoutingContext.handleGet(
        block: suspend (Map<String, List<String>>) -> ReturnData?
    ) {
        WebService.serve()
        try {
            val parameters = call.queryParameters.entries()
                .associate { it.key to it.value }
            val returnData = block(parameters)
            if (returnData != null) {
                respondReturnData(returnData)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, e.stackTraceStr)
            call.respondText(e.message ?: "Unknown error")
        }
    }

    private suspend fun RoutingContext.respondReturnData(returnData: ReturnData) {
        if (returnData.data is Bitmap) {
            val bitmap = returnData.data as Bitmap
            val outputStream = ByteArrayOutputStream()
            withContext(Dispatchers.IO) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            call.respondBytes(outputStream.toByteArray(), ContentType.Image.PNG)
        } else {
            call.respond(returnData)
        }
    }

    companion object {
        private const val TAG = "KtorServer"
    }
}
