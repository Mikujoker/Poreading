package io.legado.app.utils

import splitties.init.appCtx
import java.io.File

/**
 * 临时的启动计时（定位冷启动慢在哪一段用）。
 *
 * 落文件而不是打日志：这个 app 启动期会刷大量 LiveEventBus/网络日志，logcat 里的埋点会被冲掉。
 * 定位完就该删掉这一整套。
 */
object StartupTrace {

    private val file: File?
        get() = runCatching {
            File(appCtx.getExternalFilesDir(null), "startup-timing.log")
        }.getOrNull()

    fun reset() = runCatching { file?.writeText("") }

    fun mark(label: String) = runCatching {
        file?.appendText("${System.currentTimeMillis()} $label\n")
    }
}
