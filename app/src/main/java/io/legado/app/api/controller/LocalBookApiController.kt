package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File

/**
 * 本地书文件治理（Web 服务侧）：
 * - `POST /renameLocalBooks`：把本地书的**磁盘文件名**规范化（默认移到 `Download/legado/novel/<书名><ext>`），
 *   同时更新数据库里的 `bookUrl`（本地书是裸路径，不需要 SAF，也不需要重导/回填进度）。
 * - 默认 `dryRun=true`（只看计划不动手），返回计划 + 冲突 + 回滚清单；`dryRun=false` 才真正执行。
 */
object LocalBookApiController {

    private const val DEFAULT_TARGET_DIR = "/storage/emulated/0/Download/legado/novel"

    suspend fun renameLocalBooks(postData: String?): ReturnData {
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData ?: "{}").getOrNull().orEmpty()
        val dryRun = body["dryRun"] as? Boolean ?: true
        val onlyGarbled = body["onlyGarbled"] as? Boolean ?: true
        val targetDir = body["targetDir"]?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_TARGET_DIR }
        val target = File(targetDir)
        if (!target.exists() && !target.mkdirs()) {
            return ReturnData().setErrorMsg("目标目录不可用：$targetDir")
        }

        val all = io.legado.app.data.appDb.bookDao.all
        val locals = all.filter { book ->
            val url = book.bookUrl
            url.startsWith("/storage/") || url.startsWith("/sdcard/")
        }.filter { book ->
            // 默认只治"乱码名"的：路径里带替换字符，或同一批下载器写出来的那种前缀
            !onlyGarbled || book.bookUrl.contains('\ufffd') || book.bookUrl.contains("soushu2025.com@")
        }

        val plan = mutableListOf<Map<String, Any?>>()
        val conflicts = mutableListOf<String>()
        val renamed = mutableListOf<Map<String, Any?>>()
        val failed = mutableListOf<Map<String, Any?>>()

        // 一次性清理：改名（改主键）造成的历史重复 —— 只删"旧路径那条"，目标目录里的留着
        var cleanedDuplicates = 0
        if (body["cleanupStale"] as? Boolean == true && !dryRun) {
            all.groupBy { it.name }.forEach { (_, rows) ->
                if (rows.size > 1 && rows.any { it.bookUrl.startsWith(targetDir) }) {
                    rows.filter {
                        !it.bookUrl.startsWith(targetDir) &&
                            (it.bookUrl.contains("/Download/Browser/") || it.bookUrl.contains('\ufffd'))
                    }.forEach {
                        runCatching { io.legado.app.data.appDb.bookDao.delete(it) }
                        cleanedDuplicates++
                    }
                }
            }
        }

        locals.forEach { book ->
            val src = File(book.bookUrl)
            if (!src.exists()) return@forEach
            val ext = src.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val safeName = sanitize(book.name).ifBlank { src.nameWithoutExtension }
            val dstFile = File(target, "$safeName$ext")
            if (dstFile.absolutePath == src.absolutePath) return@forEach
            if (dstFile.exists()) {
                conflicts += dstFile.absolutePath
                return@forEach
            }
            if (dryRun) {
                if (plan.size < 400) plan += linkedMapOf(
                    "name" to book.name,
                    "from" to src.absolutePath,
                    "to" to dstFile.absolutePath,
                )
                return@forEach
            }
            val ok = runCatching { src.renameTo(dstFile) }.getOrDefault(false)
            if (ok) {
                val oldUrl = book.bookUrl
                book.bookUrl = dstFile.absolutePath
                // bookUrl 是主键：改主键等于新增行 → 必须先删旧行，否则书架出现"同一本书两条"
                runCatching { io.legado.app.data.appDb.bookDao.delete(book.copy(bookUrl = oldUrl)) }
                io.legado.app.data.appDb.bookDao.insert(book)
                renamed += linkedMapOf("name" to book.name, "to" to dstFile.absolutePath)
            } else {
                failed += linkedMapOf("name" to book.name, "from" to src.absolutePath)
            }
        }

        return ReturnData().setData(
            linkedMapOf(
                "dryRun" to dryRun,
                "targetDir" to targetDir,
                "localBooks" to locals.size,
                "moved" to renamed.size,
                "failed" to failed.size,
                "conflicts" to conflicts.size,
                "plan" to plan,
                "renamed" to renamed,
                "failedItems" to failed,
                "conflictItems" to conflicts.take(20),
                "cleanedDuplicates" to cleanedDuplicates,
            )
        )
    }

    /** 文件名里不能出现 / 和不可见字符 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimEnd('.')
}
