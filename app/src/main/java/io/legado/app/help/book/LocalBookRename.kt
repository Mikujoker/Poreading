package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地书改名时，把磁盘文件与库内引用一起改名。
 *
 * 「书 URL」在本地书上就是文件路径，所以改文件名 = 改主键：**所有按 bookUrl 挂的表都必须一起搬**，
 * 漏一张就掉一份数据（章节缓存、书签、划线、AI 产物…）。这里不手列表名，而是查 schema，
 * 把所有含 bookUrl 列的表都平移一遍 —— 以后新加表也不会漏。
 *
 * 另有几张表是按「书名 + 作者」聚合的（书签、阅读时长），改名后要同步改，否则会脱钩。
 */
object LocalBookRename {

    private val illegalChars = Regex("""[\\/:*?"<>|\n\r\t]""")

    /**
     * 把 [book] 对应的磁盘文件改名为 [newName]（保留原扩展名），并搬迁库内引用。
     *
     * @return 新的 bookUrl；文件不存在 / 已是同名 / 目标已存在 / 重命名失败时返回 null（调用方不要改库）
     */
    suspend fun rename(book: Book, newName: String): String? = withContext(Dispatchers.IO) {
        val oldUrl = book.bookUrl
        val oldFile = File(oldUrl)
        // 只处理「裸路径指向真实文件」的本地书；SAF 之类一律不动
        if (!oldFile.isFile) return@withContext bail("源文件不存在或不可读：$oldUrl")

        val safeName = illegalChars.replace(newName.trim(), "_").take(80)
        if (safeName.isBlank()) return@withContext bail("新书名为空")
        val extension = oldFile.name.substringAfterLast('.', "")
        val newFileName = if (extension.isBlank()) safeName else "$safeName.$extension"
        val newFile = File(oldFile.parentFile, newFileName)
        if (newFile.absolutePath == oldFile.absolutePath) return@withContext null
        if (newFile.exists()) return@withContext bail("目标文件名已被占用：$newFileName")

        var moved = oldFile.renameTo(newFile)
        if (!moved) {
            // 共享存储（FUSE）上 rename 会偶发失败，退化成「拷贝 + 删原文件」
            moved = runCatching {
                oldFile.copyTo(newFile, overwrite = false)
                oldFile.delete()
                true
            }.getOrDefault(false)
            if (moved) AppLog.put("改名：rename 失败，已退化为拷贝+删除（$newFileName）")
        }
        if (!moved) return@withContext bail("文件改名失败：${oldFile.name} → $newFileName")

        val newUrl = newFile.absolutePath
        val oldName = book.name
        val author = book.author
        runCatching {
            migrateReferences(oldUrl, newUrl, newFileName, oldName, newName.trim(), author)
        }.onFailure {
            // 库没搬成功就把文件改回去，不能留一个「文件在 A、库指向 B」的烂摊子
            it.printOnDebug()
            newFile.renameTo(oldFile)
            AppLog.put("改名连源文件失败（库未搬迁，文件已改回）：$it", it, true)
            runCatching { appCtx.toastOnUi("源文件已改回（库搬迁失败）") }
            return@withContext null
        }
        newUrl
    }

    /** 放弃改名时不要静默：写日志，并给用户一句可见的反馈。 */
    private fun bail(reason: String): String? {
        AppLog.put("改名连源文件跳过：$reason")
        runCatching { appCtx.toastOnUi("源文件未改名：$reason") }
        return null
    }

    private fun migrateReferences(
        oldUrl: String,
        newUrl: String,
        newFileName: String,
        oldName: String,
        newName: String,
        author: String,
    ) {
        val db = appDb.openHelper.writableDatabase
        val allTables = mutableListOf<String>()
        db.query("select name from sqlite_master where type = 'table'").use { cursor ->
            while (cursor.moveToNext()) allTables += cursor.getString(0)
        }
        // 有表用外键指向 books(bookUrl) 且 ON UPDATE NO ACTION（chapters.bookUrl、
        // exact_chapter_page_counts.bookId 都是），改父键必然违反约束：
        // 迁移期间关掉外键检查，搬完再用 foreign_key_check 自证没有悬空引用。
        db.execSQL("PRAGMA foreign_keys = OFF")
        db.beginTransaction()
        try {
            // 1) books 自己
            db.execSQL(
                "update books set bookUrl = ?, originName = ? where bookUrl = ?",
                arrayOf(newUrl, newFileName, oldUrl),
            )
            // 2) 所有含 bookUrl 列的表
            val bookUrlTables = mutableListOf<String>()
            db.query(
                """select m.name from sqlite_master m
                   where m.type = 'table'
                   and exists (select 1 from pragma_table_info(m.name) p where p.name = 'bookUrl')"""
            ).use { cursor ->
                while (cursor.moveToNext()) bookUrlTables += cursor.getString(0)
            }
            bookUrlTables.filter { it != "books" }.forEach { table ->
                db.execSQL("update `$table` set bookUrl = ? where bookUrl = ?", arrayOf(newUrl, oldUrl))
            }
            // 3) 按外键元数据找「引用 books(bookUrl) 但列名不叫 bookUrl」的表（例如 exact_chapter_page_counts.bookId）
            allTables.filter { it != "books" && !it.startsWith("sqlite_") }.forEach { table ->
                val refs = mutableListOf<String>()
                db.query("pragma foreign_key_list(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val refTable = cursor.getString(2)
                        val fromColumn = cursor.getString(3)
                        val toColumn = cursor.getString(4)
                        if (refTable == "books" && toColumn == "bookUrl") refs += fromColumn
                    }
                }
                refs.filter { it != "bookUrl" }.forEach { column ->
                    db.execSQL(
                        "update `$table` set `$column` = ? where `$column` = ?",
                        arrayOf(newUrl, oldUrl),
                    )
                }
            }
            // 4) 按书名聚合的表（书签、阅读会话）改名后要跟上
            db.execSQL("update bookmarks set bookName = ? where bookUrl = ?", arrayOf(newName, newUrl))
            db.execSQL(
                "update readRecordSession set bookName = ? where bookUrl = ?",
                arrayOf(newName, newUrl),
            )
            db.execSQL(
                "update readRecordDetail set bookName = ? where bookName = ? and bookAuthor = ?",
                arrayOf(newName, oldName, author),
            )
            // 自证：搬完不能留悬空引用
            val dangling = mutableListOf<String>()
            db.query("PRAGMA foreign_key_check").use { cursor ->
                while (cursor.moveToNext()) dangling += "${cursor.getString(0)}#${cursor.getLong(1)}"
            }
            check(dangling.isEmpty()) { "迁移后外键校验失败：$dangling" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            runCatching { db.execSQL("PRAGMA foreign_keys = ON") }
        }
    }
}
