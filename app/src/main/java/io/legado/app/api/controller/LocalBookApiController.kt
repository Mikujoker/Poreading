package io.legado.app.api.controller

import android.net.Uri
import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookUrlMigration
import io.legado.app.help.book.LocalBookRename
import io.legado.app.help.book.LocalLibrary
import io.legado.app.help.book.isLocal
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.security.MessageDigest

/**
 * 本地书文件治理（Web 服务侧）。
 *
 * - `POST /renameLocalBooks`：把本地书搬到书库目录 `Download/legado/novel/<书名><ext>`，
 *   文件名与库内引用（含 content:// SAF 书）一起对齐。默认 `dryRun=true`，只出计划。
 * - `POST /scanLocalLibrary`：只读清点 —— 书库目录里没被引用的孤儿文件、内容重复、
 *   以及书库外同名的「原文件还在」的副本。
 * - `POST /deleteLocalFiles`：按明确路径删除，删前再确认「没有书引用它」。
 */
object LocalBookApiController {

    suspend fun renameLocalBooks(postData: String?): ReturnData {
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
        val dryRun = body["dryRun"] as? Boolean ?: true
        // 只处理文件名与书名还对不上的（乱码名就属于这种）
        val onlyMismatch = body["onlyMismatch"] as? Boolean ?: false
        // content:// 的本地书：搬进书库目录并改成裸路径
        val includeSaf = body["includeSaf"] as? Boolean ?: true
        // 书库目录之外的裸路径书：也搬进书库目录
        val includeOutside = body["includeOutside"] as? Boolean ?: false
        // 目录内的文件名与书名不一致时改名
        val normalizeNames = body["normalizeNames"] as? Boolean ?: true
        // 文件已经不存在的记录（多数是「文件搬走了、旧行没清」的重复行）
        val deleteMissing = body["deleteMissing"] as? Boolean ?: false

        if (LocalLibrary.dirOrNull() == null) {
            return ReturnData().setErrorMsg("书库目录不可用：${LocalLibrary.dir().absolutePath}")
        }

        val plan = mutableListOf<Map<String, Any?>>()
        val done = mutableListOf<Map<String, Any?>>()
        val failed = mutableListOf<Map<String, Any?>>()
        val skipped = mutableListOf<Map<String, Any?>>()
        var originNameFixed = 0
        var missing = 0

        appDb.bookDao.all.filter { it.isLocal }.forEach { book ->
            val url = book.bookUrl
            val doc = LocalLibrary.docOf(url)
            val barePath = doc?.let { LocalLibrary.barePathOf(it) }
            val file = barePath?.let(::File)
            val exists = (file?.isFile == true) || (url.startsWith("content://") && doc != null)
            if (!exists) {
                missing++
                if (deleteMissing) {
                    if (dryRun) {
                        plan += linkedMapOf("action" to "deleteMissing", "name" to book.name, "bookUrl" to url)
                    } else {
                        runCatching { appDb.bookDao.delete(book) }
                            .onSuccess {
                                done += linkedMapOf("action" to "deleteMissing", "name" to book.name, "bookUrl" to url)
                            }
                            .onFailure { failed += linkedMapOf("action" to "deleteMissing", "name" to book.name, "error" to it.localizedMessage) }
                    }
                } else {
                    skipped += linkedMapOf("action" to "missingFile", "name" to book.name, "bookUrl" to url)
                }
                return@forEach
            }

            val actualName = file?.name ?: doc?.name ?: url
            val safeName = LocalLibrary.sanitize(book.name)
            val (_, ext) = LocalLibrary.splitExtension(actualName)
            val needMove = when {
                url.startsWith("content://") -> includeSaf
                file != null && !LocalLibrary.isInLibrary(file.absolutePath) -> includeOutside
                else -> false
            }
            val needRename = file != null &&
                    LocalLibrary.isInLibrary(file.absolutePath) &&
                    normalizeNames &&
                    actualName != "$safeName$ext"
            val needOriginName = book.originName != actualName
            if (onlyMismatch && !needMove && !needRename && !needOriginName) return@forEach
            if (!needMove && !needRename && !needOriginName) return@forEach

            val target = LocalLibrary.exactTarget(safeName, ext)
            if (dryRun) {
                val action = when {
                    needMove -> "moveIntoLibrary"
                    needRename -> "renameFile"
                    else -> "fixOriginName"
                }
                plan += linkedMapOf(
                    "action" to action,
                    "name" to book.name,
                    "from" to url,
                    // 只对齐 originName 时文件不动，报真实路径，别让人以为要搬
                    "to" to if (needMove || needRename) target.absolutePath else (file?.absolutePath ?: url),
                )
                return@forEach
            }
            when {
                needMove || needRename -> {
                    val result = LocalBookRename.rename(book, book.name)
                    if (result == null) {
                        // 目标名被占（例如书库里有同名的孤儿文件）：退一步，至少把 URL 换成裸路径
                        val bare = file?.absolutePath
                        if (bare != null && LocalLibrary.isInLibrary(bare) && url != bare &&
                            LocalBookRename.alignReferences(book, url, bare, file.name)
                        ) {
                            done += linkedMapOf(
                                "action" to "alignUrlOnly",
                                "name" to book.name,
                                "to" to bare,
                                "note" to "文件名与书名不一致（目标名被占）",
                            )
                        } else {
                            failed += linkedMapOf("action" to "rename", "name" to book.name, "bookUrl" to url)
                        }
                    } else {
                        book.bookUrl = result.bookUrl
                        if (book.originName != result.fileName) {
                            book.originName = result.fileName
                            appDb.bookDao.update(book)
                        }
                        done += linkedMapOf(
                            "action" to if (needMove) "moveIntoLibrary" else "renameFile",
                            "name" to book.name,
                            "to" to result.bookUrl,
                        )
                    }
                }

                else -> {
                    // 只对齐 originName：URL 不变，走同一套迁移保证引用一致
                    runCatching {
                        BookUrlMigration.migrate(
                            db = appDb.openHelper.writableDatabase,
                            oldUrl = url,
                            newUrl = url,
                            newOriginName = actualName,
                        )
                        book.originName = actualName
                    }.onSuccess {
                        originNameFixed++
                        done += linkedMapOf("action" to "fixOriginName", "name" to book.name, "originName" to actualName)
                    }.onFailure {
                        failed += linkedMapOf("action" to "fixOriginName", "name" to book.name, "error" to it.localizedMessage)
                    }
                }
            }
        }

        return ReturnData().setData(
            linkedMapOf(
                "dryRun" to dryRun,
                "libraryDir" to LocalLibrary.dir().absolutePath,
                "localBooks" to appDb.bookDao.all.count { it.isLocal },
                "missingFile" to missing,
                "originNameFixed" to originNameFixed,
                "planned" to plan.size,
                "moved" to done.count { it["action"] == "moveIntoLibrary" },
                "renamed" to done.count { it["action"] == "renameFile" },
                "originNameAligned" to originNameFixed,
                "deleted" to done.count { it["action"] == "deleteMissing" },
                "failed" to failed.size,
                "plan" to plan.take(500),
                "done" to done.take(500),
                "failedItems" to failed.take(50),
                "skipped" to skipped.take(200),
            )
        )
    }

    /**
     * 导入一个本地文件（与「用阅读打开」走同一条路径：搬进书库目录 + 按书名改名）。
     * `{"path": "/sdcard/Download/xxx.txt"}`
     */
    suspend fun importLocalFile(postData: String?): ReturnData {
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
        val path = body["path"]?.toString()?.trim().orEmpty()
        if (path.isBlank()) return ReturnData().setErrorMsg("path 为空")
        val file = File(path)
        if (!file.isFile) return ReturnData().setErrorMsg("文件不存在：$path")
        val book = LocalBook.importFile(Uri.fromFile(file))
        return ReturnData().setData(
            linkedMapOf(
                "name" to book.name,
                "author" to book.author,
                "bookUrl" to book.bookUrl,
                "originName" to book.originName,
                "sourceStillThere" to file.exists(),
            )
        )
    }

    /** 只读清点：书库里的孤儿文件 / 内容重复 / 书库外同名副本。 */
    suspend fun scanLocalLibrary(postData: String?): ReturnData {
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
        val hashOutside = body["hashOutside"] as? Boolean ?: false
        val libraryDir = LocalLibrary.dirOrNull()
            ?: return ReturnData().setErrorMsg("书库目录不可用：${LocalLibrary.dir().absolutePath}")

        val referenced = mutableSetOf<String>()
        val referencedNames = mutableSetOf<String>()
        val outsideBooks = mutableListOf<File>()
        appDb.bookDao.all.filter { it.isLocal }.forEach { book ->
            val url = book.bookUrl
            val path = if (url.startsWith("content://")) {
                LocalLibrary.docOf(url)?.let { LocalLibrary.barePathOf(it) }
            } else {
                url
            }
            val file = path?.let(::File)
            if (file == null) return@forEach
            referenced += file.absolutePath
            referencedNames += file.name
            if (file.parentFile?.absolutePath != libraryDir.absolutePath) outsideBooks += file
        }

        val libraryFiles = libraryDir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
        val orphan = libraryFiles.filter { it.absolutePath !in referenced }
        val duplicates = duplicatesBySizeThenMd5(libraryFiles)
        val sameNameOutside = if (!hashOutside) {
            // 只报「名字和书库里某个文件一样」的：那种才是「导入时拷了一份、原文件还在」的遗留
            val libraryNames = libraryFiles.map { it.name }.toSet()
            outsideBooks.filter { it.exists() && it.name in libraryNames }
                .map { linkedMapOf<String, Any?>("path" to it.absolutePath, "size" to it.length()) }
        } else {
            val referencedMd5 = libraryFiles.associate { it.absolutePath to md5(it) }
            outsideBooks.filter { it.exists() }
                .map { it to referencedMd5[it.absolutePath] }
                .filter { (_, digest) -> digest != null }
                .map { (file, _) -> linkedMapOf<String, Any?>("path" to file.absolutePath, "size" to file.length()) }
        }

        return ReturnData().setData(
            linkedMapOf(
                "libraryDir" to libraryDir.absolutePath,
                "libraryFiles" to libraryFiles.size,
                "referenced" to referenced.size,
                "orphanInLibrary" to orphan.map { linkedMapOf("name" to it.name, "size" to it.length(), "md5" to md5(it), "path" to it.absolutePath) },
                "duplicateGroups" to duplicates.map { group -> group.map { linkedMapOf("name" to it.name, "path" to it.absolutePath, "size" to it.length()) } },
                "sameNameOutsideLibrary" to sameNameOutside.take(200),
            )
        )
    }

    /** 按明确路径删除；删前再确认没有书引用它。 */
    fun deleteLocalFiles(postData: String?): ReturnData {
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
        val paths = (body["paths"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        if (paths.isEmpty()) return ReturnData().setErrorMsg("paths 为空")
        val referenced = appDb.bookDao.all.filter { it.isLocal }
            .mapNotNull { LocalLibrary.barePathOf(LocalLibrary.docOf(it.bookUrl) ?: return@mapNotNull null) }
            .toSet()
        val deleted = mutableListOf<String>()
        val refused = mutableListOf<Map<String, Any?>>()
        paths.forEach { path ->
            if (path in referenced) {
                refused += linkedMapOf("path" to path, "reason" to "仍被书架引用")
                return@forEach
            }
            val file = File(path)
            if (!file.exists()) {
                refused += linkedMapOf("path" to path, "reason" to "文件不存在")
                return@forEach
            }
            if (runCatching { file.delete() }.getOrDefault(false)) deleted += path
            else refused += linkedMapOf("path" to path, "reason" to "删除失败")
        }
        return ReturnData().setData(
            linkedMapOf("deleted" to deleted, "deletedCount" to deleted.size, "refused" to refused)
        )
    }

    /** 同尺寸才算 md5，避免把整个书库都读一遍。 */
    private fun duplicatesBySizeThenMd5(files: List<File>): List<List<File>> {
        val groups = mutableListOf<List<File>>()
        files.groupBy { it.length() }.filterValues { it.size > 1 }.forEach { (_, sameSize) ->
            sameSize.groupBy { md5(it) }.filterValues { it.size > 1 }.forEach { (_, sameContent) ->
                groups += sameContent.sortedBy { it.name }
            }
        }
        return groups
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
