package io.legado.app.help.book

import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.constant.AppLog

/**
 * 本地书 bookUrl 迁移。
 *
 * books.bookUrl 是主键，对本地书就是文件路径：改文件名 = 改主键，所有按 bookUrl 挂的表都得跟着搬。
 * 表名不手写，而是查 schema —— 含 bookUrl 列的表、外键指向 books(bookUrl) 的表统一平移，以后加表也不会漏。
 *
 * 千万别用「删旧行 + 插新行」重绑本地书：chapters.bookUrl 是 ON DELETE CASCADE，
 * 删一行会把整本目录连带删掉（历史 bug：改个名目录就空了）。
 */
object BookUrlMigration {

    /**
     * 把 [oldUrl] 的所有引用搬到 [newUrl]。
     *
     * @param newOriginName 传了就把 books.originName 一起改掉（本地书的 originName 就是文件名）
     * @param oldName/newName/author 按「书名 + 作者」聚合的表（书签、阅读记录）跟着改，否则脱钩
     */
    fun migrate(
        db: SupportSQLiteDatabase,
        oldUrl: String,
        newUrl: String,
        newOriginName: String? = null,
        oldName: String? = null,
        newName: String? = null,
        author: String? = null,
    ) {
        if (oldUrl == newUrl && newOriginName == null) return
        // 已经在别人的事务里（例如 getLocalUri 的自动重绑）：开不了子事务，
        // 用 defer_foreign_keys 把外键检查推迟到最外层提交时
        val nested = db.inTransaction()
        if (nested) {
            db.execSQL("PRAGMA defer_foreign_keys = ON")
        } else {
            // 必须在 beginTransaction 之前：事务里 foreign_keys 这个 pragma 是 no-op
            db.execSQL("PRAGMA foreign_keys = OFF")
            db.beginTransaction()
        }
        var success = false
        try {
            val tables = allTables(db)
            db.execSQL(
                "update books set bookUrl = ?, originName = coalesce(?, originName) where bookUrl = ?",
                arrayOf(newUrl, newOriginName, oldUrl),
            )
            tables.filter { it != "books" && it.hasBookUrlColumn(db) }.forEach { table ->
                db.execSQL("update `$table` set bookUrl = ? where bookUrl = ?", arrayOf(newUrl, oldUrl))
            }
            foreignKeyChildColumns(db, tables).forEach { (table, column) ->
                db.execSQL("update `$table` set `$column` = ? where `$column` = ?", arrayOf(newUrl, oldUrl))
            }
            renameKeyedRows(db, tables, newUrl, oldName, newName, author)
            checkNoDanglingReference(db)
            success = true
        } finally {
            if (nested) {
                runCatching { db.execSQL("PRAGMA defer_foreign_keys = OFF") }
            } else {
                if (success) db.setTransactionSuccessful()
                db.endTransaction()
                runCatching { db.execSQL("PRAGMA foreign_keys = ON") }
            }
        }
    }

    private fun allTables(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        db.query("select name from sqlite_master where type = 'table'").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        return tables
    }

    private fun String.hasBookUrlColumn(db: SupportSQLiteDatabase): Boolean {
        db.query("pragma table_info(`$this`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "bookUrl") return true
            }
        }
        return false
    }

    /** 外键指向 books(bookUrl) 但列名不叫 bookUrl 的表（例如 exact_chapter_page_counts.bookId） */
    private fun foreignKeyChildColumns(
        db: SupportSQLiteDatabase,
        tables: List<String>,
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        tables.filter { it != "books" && !it.startsWith("sqlite_") }
            .filter { it != "android_metadata" && it != "room_master_table" }
            .forEach { table ->
                db.query("pragma foreign_key_list(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val refTable = cursor.getString(2)
                        val fromColumn = cursor.getString(3)
                        val toColumn = cursor.getString(4)
                        if (refTable == "books" && toColumn == "bookUrl" && fromColumn != "bookUrl") {
                            result += table to fromColumn
                        }
                    }
                }
            }
        return result
    }

    /**
     * 按「书名 + 作者」聚合的表：书名变了要跟上，否则书签 / 阅读记录会脱钩。
     * 这些表的 PK/UNIQUE 常在「同名重复副本」上冲突，冲突只记日志，不让整次迁移失败。
     */
    private fun renameKeyedRows(
        db: SupportSQLiteDatabase,
        tables: List<String>,
        newUrl: String,
        oldName: String?,
        newName: String?,
        author: String?,
    ) {
        if (oldName.isNullOrBlank() || newName.isNullOrBlank() || oldName == newName) return
        fun update(table: String, sql: String, args: Array<Any?>) {
            if (table !in tables) return
            runCatching { db.execSQL(sql, args) }.onFailure {
                AppLog.put("改名：$table 的书名未跟上（同名记录冲突？）：${it.localizedMessage}")
            }
        }
        // 有 bookUrl 的按 bookUrl 精确定位（上一步已改成 newUrl）
        update(
            "bookmarks",
            "update bookmarks set bookName = ? where bookUrl = ?",
            arrayOf(newName, newUrl),
        )
        update(
            "book_marks",
            "update book_marks set bookName = ? where bookUrl = ?",
            arrayOf(newName, newUrl),
        )
        update(
            "readRecordSession",
            "update readRecordSession set bookName = ? where bookUrl = ?",
            arrayOf(newName, newUrl),
        )
        // 只有「书名 + 作者」的按旧名定位
        if (!author.isNullOrBlank()) {
            update(
                "readRecordDetail",
                "update readRecordDetail set bookName = ? where bookName = ? and bookAuthor = ?",
                arrayOf(newName, oldName, author),
            )
            update(
                "readRecord",
                "update readRecord set bookName = ? where bookName = ? and bookAuthor = ?",
                arrayOf(newName, oldName, author),
            )
        }
    }

    /** 自证：搬完不能留悬空引用 */
    private fun checkNoDanglingReference(db: SupportSQLiteDatabase) {
        val dangling = mutableListOf<String>()
        db.query("PRAGMA foreign_key_check").use { cursor ->
            while (cursor.moveToNext()) dangling += "${cursor.getString(0)}#${cursor.getLong(1)}"
        }
        check(dangling.isEmpty()) { "迁移后外键校验失败：$dangling" }
    }
}
