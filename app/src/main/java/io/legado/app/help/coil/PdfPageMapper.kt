package io.legado.app.help.coil

import coil3.map.Mapper
import coil3.request.Options
import io.legado.app.model.localBook.PdfPageFiles

/**
 * 把 PDF 页的惰性地址换成本地图片文件；缺图就地渲染（Coil 在后台线程调用）。
 *
 * 整本 PDF 是一章、页项几十上百个，不可能在章加载时一次渲染完；地址先给惰性 scheme，
 * 真正取图时（包括预取）才渲染，预取因此天然带上了"提前把后面几页渲染好"。
 */
class PdfPageMapper : Mapper<String, String> {
    override fun map(data: String, options: Options): String? =
        PdfPageFiles.resolve(data)?.toString()
}
