package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.model.ReadBook
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 本地书改名：把文件落到书库目录（Download/legado/novel）并按书名改名，库内引用一起搬。
 *
 * 「书 URL」在本地书上就是文件路径，所以改文件名 = 改主键：所有按 bookUrl 挂的表都要搬，
 * 见 [BookUrlMigration]。
 *
 * 顺序是「先把文件放到位 → 再搬库 → 最后删源」：[LocalLibrary.PendingMove] 保证任一步失败都能回退，
 * 不会留下「文件在 A、库指向 B」的烂摊子。
 */
object LocalBookRename {

    data class Result(val bookUrl: String, val fileName: String)

    /**
     * 把 [book] 的文件改名成 [newName]（落到书库目录），并搬迁库内引用。
     *
     * @return 新的 bookUrl 与文件名；源文件不存在 / 目标重名 / 搬移或迁移失败时返回 null（调用方别改库）
     */
    suspend fun rename(book: Book, newName: String): Result? = withContext(Dispatchers.IO) {
        val safeName = LocalLibrary.sanitize(newName)
        if (safeName.isBlank()) return@withContext bail("新书名为空")
        val oldUrl = book.bookUrl
        val source = LocalLibrary.docOf(oldUrl) ?: return@withContext bail("读不到本地书：$oldUrl")
        val sourceFile = LocalLibrary.barePathOf(source)?.let(::File)?.takeIf(File::isFile)
        if (sourceFile == null && !oldUrl.startsWith("content://")) {
            return@withContext bail("源文件不存在或不可读：$oldUrl")
        }
        val (_, ext) = LocalLibrary.splitExtension(source.name)
        val target = LocalLibrary.exactTarget(safeName, ext)
        if (target.absolutePath == sourceFile?.absolutePath) {
            // 文件已在书库目录、名字也对：只把库内 URL / originName 对齐
            //（SAF 书在这里顺带换成裸路径，之后才享受得到「同卷 rename 秒改」）
            if (!alignReferences(book, oldUrl, target.absolutePath, target.name)) {
                return@withContext bail("库搬迁失败")
            }
            return@withContext Result(target.absolutePath, target.name)
        }
        // 手动改名要确定性：目标被别本书占着就说清楚，不偷偷加序号
        if (target.exists()) return@withContext bail("书库目录里已有同名文件：${target.name}")

        val pending = LocalLibrary.stage(source, target)
            ?: return@withContext bail("文件搬移失败：${source.name}")
        val newUrl = target.absolutePath
        val migrated = runCatching {
            BookUrlMigration.migrate(
                db = appDb.openHelper.writableDatabase,
                oldUrl = oldUrl,
                newUrl = newUrl,
                newOriginName = target.name,
                oldName = book.name,
                newName = safeName,
                author = book.author,
            )
        }.onFailure {
            it.printOnDebug()
            AppLog.put("改名连源文件失败（库未搬迁，已回退）：$it", it, true)
            pending.rollback()
        }.isSuccess
        if (!migrated) return@withContext bail("库搬迁失败，文件已改回")

        if (!pending.commit()) {
            AppLog.put("改名：源文件未删除（权限不足），书库副本已保留 ${target.name}")
        }
        syncLocalUriCache(oldUrl, newUrl)
        syncReadBook(oldUrl, newUrl, target.name)
        Result(newUrl, target.name)
    }

    /**
     * 只对齐引用，不动文件：URL（含 SAF → 裸路径）/ originName / 缓存 / 阅读会话。
     * @return 是否成功；迁移失败返回 false
     */
    fun alignReferences(book: Book, oldUrl: String, newUrl: String, fileName: String): Boolean {
        if (oldUrl == newUrl && book.originName == fileName) return true
        val ok = runCatching {
            BookUrlMigration.migrate(
                db = appDb.openHelper.writableDatabase,
                oldUrl = oldUrl,
                newUrl = newUrl,
                newOriginName = fileName,
                oldName = book.name,
                newName = book.name,
                author = book.author,
            )
        }.onFailure {
            it.printOnDebug()
            AppLog.put("改名连源文件失败（只对齐引用）：$it", it, true)
        }.isSuccess
        if (!ok) return false
        syncLocalUriCache(oldUrl, newUrl)
        syncReadBook(oldUrl, newUrl, fileName)
        return true
    }

    /** 放弃改名时不要静默：写日志，并给用户一句可见的反馈。 */
    private fun bail(reason: String): Result? {
        AppLog.put("改名连源文件跳过：$reason")
        runCatching { appCtx.toastOnUi("源文件未改名：$reason") }
        return null
    }

    /**
     * bookUrl 变了，`URL → 打开用的 Uri` 缓存必须作废：否则还会拿旧 Uri 去读，
     * 表现成「文件不存在 / 目录出错」。
     */
    fun syncLocalUriCache(oldUrl: String, newUrl: String) {
        clearLocalUriCache(oldUrl)
        clearLocalUriCache(newUrl)
    }

    /** 正在读这本书时要换成新 URL：阅读会话还按旧 URL 校验章节输入，不换就会一直拒收。 */
    private fun syncReadBook(oldUrl: String, newUrl: String, fileName: String) {
        runCatching {
            if (!ReadBook.isCurrentBook(oldUrl)) return
            val current = ReadBook.book ?: return
            ReadBook.replaceCurrentBook(current.copy(bookUrl = newUrl, originName = fileName))
        }.onFailure {
            it.printOnDebug()
            AppLog.put("改名：阅读会话未跟上新路径（$newUrl）", it)
        }
    }
}
