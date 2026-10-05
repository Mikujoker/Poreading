package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName


@Entity(tableName = "txtTocRules")
data class TxtTocRule(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    var name: String = "",
    @SerializedName(value = "chapterRule", alternate = ["rule"])
    var chapterRule: String = "",
    var volumeRule: String = "",
    var example: String? = null,
    var serialNumber: Int = -1,
    var enable: Boolean = true,
    /**
     * 兜底规则：只在没有任何普通规则"能用"时才参与竞争。
     *
     * 典型例子是「分隔线（※——=＊）」——它把 `※※※` / `————` 这类场景分隔当章界，命中数
     * 天然远大于标题规则（一本书可能 600 条分隔线，而只有 190 个真章节名）。若同场竞争，
     * 它会靠命中数取胜，把有正经章节名的书拆成按场景分章。
     *
     * 刻意**不**用 serialNumber 的负数当标记：UI 新建规则时不设置 serialNumber，会落在
     * 默认值 -1 上，那样用户自己加的每条规则都会被静默变成兜底规则。
     */
    var isFallback: Boolean = false
) {

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is TxtTocRule) {
            return id == other.id
        }
        return false
    }

}