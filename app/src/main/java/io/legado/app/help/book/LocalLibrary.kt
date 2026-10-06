package io.legado.app.help.book

import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File

/**
 * 本地书的「书库目录」：`Download/legado/novel`，文件名 = 书名。
 *
 * 导入搬移、改名连源文件、批量治理都只走这里，目录与命名规则只有一处。
 * 搬移一律「先把文件放到书库目录 → 库内引用搬完 → 才删源」，任何一步失败都能干净回退。
 */
object LocalLibrary {

    const val DIR_PATH = "Download/legado/novel"

    private val illegalChars = Regex("""[\\/:*?"<>|\n\r\t]""")
    private const val MAX_NAME_LENGTH = 80

    fun dir(): File = File(FileUtils.getSdCardPath(), DIR_PATH)

    /** 书库目录；不存在就建，建不出来返回 null */
    fun dirOrNull(): File? {
        val dir = dir()
        return dir.takeIf { it.isDirectory || dir.mkdirs() }
    }

    /** 裸路径是否已在书库目录里 */
    fun isInLibrary(path: String): Boolean =
        canonical(File(path).parentFile) == canonical(dir())

    /** 文件名安全化：替换非法字符、限长、去掉结尾的点 */
    fun sanitize(name: String): String = illegalChars.replace(name.trim(), "_")
        .take(MAX_NAME_LENGTH)
        .trim()
        .trimEnd('.')

    /** 拆出 (主名, ".扩展名")；没有扩展名时扩展名是空串 */
    fun splitExtension(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName to "" else fileName.substring(0, dot) to fileName.substring(dot)
    }

    /** 书库目录里的精确目标（不做重名退让），给「改名连源文件」这种要确定性的场景用 */
    fun exactTarget(baseName: String, ext: String): File =
        File(dir(), sanitize(baseName).ifBlank { "book" } + ext)

    /**
     * 书库目录里的目标文件，重名就退到 `" (2)"`、`" (3)"`…（导入用：宁可加序号也不覆盖别本书）。
     * [exclude] 传源文件时，目标正好等于它就算可用（等价于「不动」）。
     */
    fun target(baseName: String, ext: String, exclude: File? = null): File {
        val safe = sanitize(baseName).ifBlank { "book" }
        var candidate = File(dir(), safe + ext)
        var index = 1
        while (candidate.exists() && candidate.absolutePath != exclude?.absolutePath) {
            index++
            candidate = File(dir(), "$safe ($index)$ext")
        }
        return candidate
    }

    /** 一次在途搬移：库内引用搬完调 [commit]，失败调 [rollback] 回到原样 */
    interface PendingMove {
        val file: File

        /** 库内引用已搬好，可以删源了；返回源文件是否真的没了 */
        fun commit(): Boolean

        fun rollback()
    }

    /** 搬移结果 */
    data class Landed(
        val file: File,
        /** 源文件是否已消失（真搬走了）；false 表示源还在，书库这份是副本 */
        val sourceRemoved: Boolean,
        /** 是否真的动过文件（源已在书库目录且名字一致 → false） */
        val moved: Boolean,
    )

    /**
     * 把源文件落到书库目录并按 [baseName] 命名（保留原扩展名）。
     * 已在书库目录且名字一致时原样返回；源删不掉时保留副本并如实上报。
     */
    fun land(input: FileDoc, baseName: String): Landed? {
        val (_, ext) = splitExtension(input.name)
        val sourceFile = bareFileOf(input)
        val target = target(baseName, ext, exclude = sourceFile)
        if (sourceFile != null && target.absolutePath == sourceFile.absolutePath) {
            return Landed(sourceFile, true, false)
        }
        val pending = stage(input, target) ?: return null
        val removed = pending.commit()
        if (!removed) {
            AppLog.put("导入：源文件未删除（权限不足），已保留书库副本 ${target.name}")
        }
        return Landed(target, removed, true)
    }

    /**
     * 开始把 [input] 落到 [target]：源先不动，等 [PendingMove.commit] 才删。
     * 同卷 rename 是瞬时的；跨卷/权限不足退化成拷贝。
     */
    fun stage(input: FileDoc, target: File): PendingMove? {
        target.parentFile?.mkdirs()
        val sourceFile = bareFileOf(input)
        if (sourceFile != null) {
            if (sourceFile.absolutePath == target.absolutePath) return NoopMove(target)
            if (sourceFile.renameTo(target)) return RenamedMove(target, sourceFile)
            if (!copyFile(sourceFile, target)) return null
            return CopiedMove(target, input, sourceFile)
        }
        // SAF 文档：只能流拷贝
        val copied = input.uri.inputStream(appCtx).getOrNull()?.use { inStream ->
            runCatching {
                target.outputStream().use { out -> inStream.copyTo(out) }
                target.length() > 0
            }.getOrElse {
                it.printOnDebug()
                AppLog.put("导入搬移失败（拷贝）：${input.name} → ${target.name}", it)
                false
            }
        } ?: false
        if (!copied) {
            target.delete()
            return null
        }
        return CopiedMove(target, input, null)
    }

    /** bookUrl → 磁盘上的文件名（裸路径取最后一段，SAF 取文档名） */
    fun fileNameOf(bookUrl: String): String? {
        if (bookUrl.isBlank()) return null
        if (!bookUrl.startsWith("content://")) return File(bookUrl).name.takeIf { it.isNotBlank() }
        return runCatching { DocumentFile.fromSingleUri(appCtx, bookUrl.toUri())?.name }.getOrNull()
    }

    /** bookUrl → [FileDoc]；拿不到返回 null */
    fun docOf(bookUrl: String): FileDoc? = runCatching {
        // 裸路径不能走 Uri.parse：路径里带 # 或 ? 会被当成 fragment/query，文件名就截断了
        val uri = if (bookUrl.startsWith("content://")) bookUrl.toUri() else Uri.fromFile(File(bookUrl))
        FileDoc.fromUri(uri, false)
    }.getOrNull()

    /** file:// 或能解析成裸路径的 SAF 文档 → 真实路径；否则 null */
    fun barePathOf(input: FileDoc): String? {
        if (!input.isContentScheme) return input.uri.path
        return runCatching { RealPathUtil.getPath(appCtx, input.uri) }.getOrNull()
    }

    private fun bareFileOf(input: FileDoc): File? =
        barePathOf(input)?.let(::File)?.takeIf(File::isFile)

    /** canonicalPath 拿不到（路径不存在/异常）就退回 absolutePath，不往外抛 */
    private fun canonical(file: File?): String? = file?.let {
        runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
    }

    private fun copyFile(source: File, target: File): Boolean = runCatching {
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        target.length() > 0
    }.getOrElse {
        it.printOnDebug()
        AppLog.put("导入搬移失败（拷贝）：${source.name} → ${target.name}", it)
        target.delete()
        false
    }

    /** 删源：裸路径直接删，SAF 走 deleteDocument（失败再退 DocumentFile.delete） */
    private fun deleteSource(input: FileDoc, sourceFile: File?): Boolean {
        if (sourceFile != null && sourceFile.delete()) return true
        val uri = input.uri
        if (!uri.isContentScheme()) return false
        if (runCatching { DocumentsContract.deleteDocument(appCtx.contentResolver, uri) }
                .getOrDefault(false)
        ) {
            return true
        }
        if (sourceFile != null && !sourceFile.exists()) return true
        return runCatching { DocumentFile.fromSingleUri(appCtx, uri)?.delete() == true }
            .getOrDefault(false)
    }

    private class NoopMove(override val file: File) : PendingMove {
        override fun commit() = true
        override fun rollback() = Unit
    }

    private class RenamedMove(override val file: File, private val source: File) : PendingMove {
        override fun commit() = true

        override fun rollback() {
            if (file.exists() && !file.renameTo(source)) {
                AppLog.put("回退失败：${file.absolutePath} 没能改回 ${source.absolutePath}")
            }
        }
    }

    private class CopiedMove(
        override val file: File,
        private val input: FileDoc,
        private val sourceFile: File?,
    ) : PendingMove {
        override fun commit() = deleteSource(input, sourceFile)

        override fun rollback() {
            file.delete()
        }
    }
}
