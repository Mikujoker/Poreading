package io.legado.app.help

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.listFileDocs
import java.io.File

fun loadFontFiles(context: Context, folderUri: Uri?): List<FileDoc> {
    val fontRegex = Regex("(?i).*\\.[ot]tf")
    if (folderUri != null) {
        try {
            if (folderUri.isContentScheme()) {
                val documentFile = DocumentFile.fromTreeUri(context, folderUri)
                return documentFile?.listFileDocs { it.name.matches(fontRegex) } ?: emptyList()
            } else {
                val path = folderUri.path ?: folderUri.toString()
                val file = File(path)
                if (file.exists()) {
                    return file.listFileDocs { it.name.matches(fontRegex) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // 外挂字体目录（B3 的约定）：把字体丢进 /sdcard/legado/fonts 就能选，
    // 不必先去设置里授权一个 SAF 目录；授权过的目录优先。
    val externalFontDir = File(FileUtils.getSdCardPath(), "legado/fonts")
    if (externalFontDir.isDirectory) {
        externalFontDir.listFileDocs { it.name.matches(fontRegex) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    val fontPath = "${context.getExternalFilesDir(null)?.absolutePath}/font"
    return File(fontPath).listFileDocs { it.name.matches(fontRegex) }
}
