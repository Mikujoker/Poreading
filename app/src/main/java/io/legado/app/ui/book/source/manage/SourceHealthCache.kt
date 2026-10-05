package io.legado.app.ui.book.source.manage

import android.content.Context
import io.legado.app.utils.GSON
import java.io.File

/** 落盘的健康度索引。 */
data class CachedSourceHealth(
    /** 写入时的数据库指纹，用来判断缓存是否还有效 */
    val fingerprint: String = "",
    /** SourceHealth.name -> 计数 */
    val counts: Map<String, Int> = emptyMap(),
    /** bookSourceUrl -> SourceHealth.name */
    val byUrl: Map<String, String> = emptyMap(),
)

/**
 * 健康度索引的落盘缓存。
 *
 * 书源表 119 MB / 两万多行，任何全表扫描都要数秒（实测 PC SSD 上 2.6~5.9s，手机更慢）。
 * 这里把算好的索引缓存到文件，打开页面时先读缓存立即出图，后台重算完再覆盖。
 *
 * 失效用「数据库文件 + WAL 文件的 size/mtime」当指纹：书源一被写过，WAL 就会变，
 * 比逐表比对便宜得多。Room 默认 WAL 模式，所以两个文件都要看。
 */
object SourceHealthCache {

    private const val FILE_NAME = "source_health_cache.json"
    private const val DB_NAME = "legado.db"

    private fun cacheFile(context: Context) = File(context.cacheDir, FILE_NAME)

    /** 便宜的变更指纹：数据库主文件和 WAL 的大小与修改时间。 */
    fun fingerprint(context: Context): String {
        val db = context.getDatabasePath(DB_NAME)
        val wal = File(db.path + "-wal")
        return buildString {
            append(db.length()).append(':').append(db.lastModified())
            append('|').append(wal.length()).append(':').append(wal.lastModified())
        }
    }

    /** 读缓存；文件缺失、解析失败、或指纹已过期都返回 null。 */
    fun read(context: Context): CachedSourceHealth? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return null
        val cached = GSON.fromJson(file.readText(), CachedSourceHealth::class.java)
        cached?.takeIf { it.fingerprint == fingerprint(context) && it.byUrl.isNotEmpty() }
    }.getOrNull()

    fun write(context: Context, data: CachedSourceHealth) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            file.writeText(GSON.toJson(data))
        }
    }
}
