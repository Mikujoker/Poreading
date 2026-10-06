package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * OPDS 目录源（Calibre-Web / Kavita / Komga 这类书库的 Atom 目录）。
 * 与书源分开存：OPDS 是"下载目录"而不是"在线书源"，搜索/目录/正文都不走规则。
 */
@Entity(tableName = "opdsSources")
data class OpdsSource(
    @PrimaryKey
    var url: String = "",
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    /** Basic Auth 账号，留空表示匿名访问。 */
    @ColumnInfo(defaultValue = "")
    var username: String = "",
    @ColumnInfo(defaultValue = "")
    var password: String = "",
    @ColumnInfo(defaultValue = "0")
    var sortNumber: Int = 0,
) {

    override fun hashCode(): Int = url.hashCode()

    override fun equals(other: Any?): Boolean = other is OpdsSource && other.url == url
}
