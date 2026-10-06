package io.legado.app.ui.book.info.edit

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.LocalBookRename
import io.legado.app.help.book.addType
import io.legado.app.help.book.applyTagGroupRulesForBook
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

enum class BookInfoEditType {
    TEXT,
    AUDIO,
    IMAGE
}

data class BookInfoEditUiState(
    val name: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String? = null,
    val remark: String? = null,
    val sourceKindList: List<String> = emptyList(),
    val kindList: List<String> = emptyList(),
    val originalKindList: List<String> = emptyList(),
    val selectedType: BookInfoEditType = BookInfoEditType.TEXT,
    val fixedType: Boolean = false,
    val book: Book? = null,
)

class BookInfoEditViewModel(
    application: Application,
    private val bookRepository: BookRepository,
) : BaseViewModel(application) {
    var book: Book? = null
    private val _uiState = MutableStateFlow(BookInfoEditUiState())
    val uiState: StateFlow<BookInfoEditUiState> = _uiState.asStateFlow()

    fun loadBook(bookUrl: String) {
        execute {
            book = bookRepository.getBook(bookUrl)
            book?.let {
                val selectedType = when {
                    it.isImage -> BookInfoEditType.IMAGE
                    it.isAudio -> BookInfoEditType.AUDIO
                    else -> BookInfoEditType.TEXT
                }
                val sourceKinds = it.kind?.splitNotBlank(",", "\n").orEmpty().distinct()
                val customTags = it.customTag?.splitNotBlank(",", "\n").orEmpty().distinct()
                _uiState.value = BookInfoEditUiState(
                    name = it.name,
                    author = it.author,
                    coverUrl = it.getDisplayCover(),
                    intro = it.getDisplayIntro(),
                    remark = it.remark,
                    sourceKindList = sourceKinds,
                    kindList = customTags,
                    originalKindList = customTags,
                    selectedType = selectedType,
                    fixedType = it.config.fixedType,
                    book = it
                )
            }
        }
    }

    fun resetKinds() {
        _uiState.value = _uiState.value.copy(kindList = _uiState.value.originalKindList.toList())
    }

    fun onNameChange(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun onAuthorChange(author: String) {
        _uiState.value = _uiState.value.copy(author = author)
    }

    fun onCoverUrlChange(coverUrl: String) {
        _uiState.value = _uiState.value.copy(coverUrl = coverUrl)
    }

    fun onIntroChange(intro: String) {
        _uiState.value = _uiState.value.copy(intro = intro)
    }

    fun onRemarkChange(remark: String) {
        _uiState.value = _uiState.value.copy(remark = remark)
    }

    fun onKindListChange(kindList: List<String>) {
        _uiState.value = _uiState.value.copy(kindList = kindList.distinct())
    }

    fun onBookTypeChange(bookType: BookInfoEditType) {
        _uiState.value = _uiState.value.copy(selectedType = bookType)
    }

    fun onFixedTypeChange(fixed: Boolean) {
        _uiState.value = _uiState.value.copy(fixedType = fixed)
    }

    fun resetCover() {
        _uiState.value = _uiState.value.copy(coverUrl = book?.coverUrl ?: "")
    }

    /** 连源文件一起改名后的新 bookUrl；没改名则为 null。编辑页把它回传给详情页。 */
    var renamedBookUrl: String? = null
        private set

    /**
     * 保存前该不该问「要不要连源文件一起改」。
     *
     * 判据不是「书名变了」，而是**书名与磁盘上的文件名对不上** —— 这样两类情况都会问：
     * 刚改了书名，以及书名和文件名本来就不一致（比如文件是乱码名）。只有两者一致时才不问。
     */
    fun shouldAskRenameSource(): Boolean {
        val current = _uiState.value
        val target = book ?: return false
        if (!target.isLocal) return false
        val sourceFile = java.io.File(target.bookUrl)
        // SAF（content://）导入的书：裸路径的 isFile 恒为 false，会永远不问"要不要一起改名"
        val fileName = if (target.bookUrl.startsWith("content://")) {
            runCatching {
                androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(splitties.init.appCtx, android.net.Uri.parse(target.bookUrl))
                    ?.name
            }.getOrNull()?.substringBeforeLast('.') ?: return false
        } else {
            val sourceFile = java.io.File(target.bookUrl)
            if (!sourceFile.isFile) return false
            sourceFile.name.substringBeforeLast('.')
        }
        return current.name.trim() != fileName
    }

    fun save(onSuccess: () -> Unit, renameSourceFile: Boolean = false) {
        execute {
            val currentState = _uiState.value
            book?.let { book ->
                val oldBook = book.copy()
                if (renameSourceFile) {
                    // 先把磁盘文件与库内引用一起搬过去（返回 null 表示没搬成，就只改书名）
                    LocalBookRename.rename(oldBook, currentState.name)?.let { result ->
                        book.bookUrl = result.bookUrl
                        renamedBookUrl = result.bookUrl
                        // originName 要用搬移结果里的真文件名：SAF 书的 URL 是 content://…，
                        // File(url).name 会得到 URL 编码串（"primary%3ADownload%2F…"），详情页就会显示这堆垃圾
                        book.originName = result.fileName
                    }
                }
                book.name = currentState.name
                book.author = currentState.author
                book.remark = currentState.remark
                val local = if (book.isLocal) BookType.local else 0
                val bookType = when (currentState.selectedType) {
                    BookInfoEditType.IMAGE -> BookType.image or local
                    BookInfoEditType.AUDIO -> BookType.audio or local
                    else -> BookType.text or local
                }
                book.removeType(BookType.local, BookType.image, BookType.audio, BookType.text)
                book.addType(bookType)
                book.config.fixedType = currentState.fixedType
                book.customCoverUrl = if (currentState.coverUrl == book.coverUrl) null else currentState.coverUrl
                book.customIntro = if (currentState.intro == book.intro) null else currentState.intro
                book.customTag = currentState.kindList.joinToString(",").ifBlank { null }
                applyTagGroupRulesForBook(book)
                BookHelp.updateCacheFolder(oldBook, book)

                // 改过名的书 URL 已经变了，要用旧 URL 判断「是不是正在读的这本」
                if (ReadBook.isCurrentBook(oldBook.bookUrl) || ReadBook.isCurrentBook(book.bookUrl)) {
                    ReadBook.replaceCurrentBook(book)
                }
                bookRepository.update(book)
            }
        }.onSuccess {
            onSuccess.invoke()
        }.onError {
            if (it is SQLiteConstraintException) {
                AppLog.put("书籍信息保存失败，存在相同书名作者书籍\n$it", it, true)
            } else {
                AppLog.put("书籍信息保存失败\n$it", it, true)
            }
        }
    }

    fun coverChangeTo(context: Context, uri: Uri) {
        execute {
            runCatching {
                val suffix = context.contentResolver.getType(uri)?.substringAfterLast("/") ?: "jpg"
                val coversDir = FileUtils.createFolderIfNotExist(context.externalFiles, "covers")
                val tempFile = File(coversDir, "${System.currentTimeMillis()}.tmp")
                uri.inputStream(context).getOrThrow().use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                val md5 = tempFile.inputStream().use { MD5Utils.md5Encode(it) }
                val coverFile = File(coversDir, "$md5.$suffix")
                if (coverFile.exists()) {
                    tempFile.delete()
                } else if (!tempFile.renameTo(coverFile)) {
                    tempFile.copyTo(coverFile, overwrite = true)
                    tempFile.delete()
                }
                _uiState.value = _uiState.value.copy(coverUrl = coverFile.absolutePath)
            }.onFailure {
                AppLog.put("书籍封面保存失败\n$it", it, true)
            }
        }
    }
}
