package io.legado.app.ui.opds

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.OpdsSource
import io.legado.app.data.repository.OpdsRepository
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.opds.OpdsFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 面包屑：进过的每一层目录（用于返回上一级）。 */
@Immutable
data class OpdsCrumb(val title: String, val href: String)

@Immutable
data class OpdsUiState(
    val sources: List<OpdsSource> = emptyList(),
    val source: OpdsSource? = null,
    val crumbs: List<OpdsCrumb> = emptyList(),
    val entries: List<OpdsFeed.Entry> = emptyList(),
    val title: String = "",
    val nextHref: String? = null,
    val keyword: String = "",
    val loading: Boolean = false,
    val downloading: String? = null,
    val message: String? = null,
) {
    val isBrowsing: Boolean get() = source != null
}

/**
 * OPDS 浏览：源管理 + 目录/搜索 + 下载导入。
 * 业务全在 [OpdsRepository]，这里只管状态与线程（UI 不直连 DAO）。
 */
class OpdsViewModel(private val repository: OpdsRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(OpdsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.flowSources().collect { list ->
                _uiState.update { it.copy(sources = list) }
            }
        }
    }

    fun saveSource(name: String, url: String, username: String, password: String) {
        if (url.isBlank()) {
            message("地址不能为空")
            return
        }
        viewModelScope.launch {
            repository.save(
                OpdsSource(
                    url = url.trim(),
                    name = name.trim().ifBlank { url.trim() },
                    username = username.trim(),
                    password = password,
                ),
            )
            message("已保存：${name.trim().ifBlank { url.trim() }}")
        }
    }

    fun deleteSource(source: OpdsSource) {
        viewModelScope.launch {
            repository.delete(source)
            if (_uiState.value.source?.url == source.url) closeSource()
            message("已删除：${source.name}")
        }
    }

    /** 进入某个源（从根目录开始）。 */
    fun openSource(source: OpdsSource) {
        _uiState.update { it.copy(source = source, crumbs = emptyList(), keyword = "") }
        load(href = null, query = null)
    }

    fun closeSource() {
        _uiState.update {
            it.copy(source = null, crumbs = emptyList(), entries = emptyList(), title = "", nextHref = null)
        }
    }

    /** 点条目：目录进下一层；书条目直接下载导入。 */
    fun openEntry(entry: OpdsFeed.Entry) {
        val href = entry.navHref ?: entry.bookFeedHref
        if (href != null && entry.acquisition == null) {
            load(href = href, query = null, pushCrumb = entry.title)
        } else if (entry.isDownloadable) {
            download(entry)
        } else {
            message("这条没有可下载的内容")
        }
    }

    fun download(entry: OpdsFeed.Entry) {
        val source = _uiState.value.source ?: return
        if (_uiState.value.downloading != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloading = entry.title) }
            val file = runCatching { repository.download(source, entry) }.getOrNull()
            if (file == null) {
                _uiState.update { it.copy(downloading = null) }
                message("下载失败：${entry.title}")
                return@launch
            }
            val book = withContext(Dispatchers.IO) {
                runCatching { LocalBook.importFile(Uri.fromFile(file)) }.getOrNull()
            }
            _uiState.update { it.copy(downloading = null) }
            message(
                if (book != null) "已加入书架：${book.name}"
                else "已下载（导入失败）：${file.name}",
            )
        }
    }

    fun search(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
        if (keyword.isBlank()) {
            back()
            return
        }
        load(href = null, query = keyword, replaceCrumb = true)
    }

    fun nextPage() {
        val href = _uiState.value.nextHref ?: return
        load(href = href, query = null)
    }

    fun refresh() {
        val state = _uiState.value
        load(href = state.crumbs.lastOrNull()?.href, query = state.keyword.takeIf { it.isNotBlank() })
    }

    /** 返回上一级；已在根目录则回到源列表。 */
    fun back() {
        val state = _uiState.value
        if (state.crumbs.isEmpty()) {
            if (state.keyword.isNotBlank()) {
                _uiState.update { it.copy(keyword = "") }
                load(href = null, query = null)
            } else {
                closeSource()
            }
            return
        }
        val remain = state.crumbs.dropLast(1)
        _uiState.update { it.copy(crumbs = remain, keyword = "") }
        load(href = remain.lastOrNull()?.href, query = null)
    }

    fun message(text: String?) {
        _uiState.update { it.copy(message = text) }
    }

    private fun load(
        href: String?,
        query: String?,
        pushCrumb: String? = null,
        replaceCrumb: Boolean = false,
    ) {
        val source = _uiState.value.source ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val result = runCatching { repository.browse(source, href, query) }
            val page = result.getOrNull()
            if (page == null) {
                _uiState.update { it.copy(loading = false) }
                message("抓取失败：${result.exceptionOrNull()?.localizedMessage.orEmpty()}")
                return@launch
            }
            _uiState.update { state ->
                val crumbs = when {
                    replaceCrumb -> listOf(OpdsCrumb(page.title, href.orEmpty()))
                    pushCrumb != null -> state.crumbs + OpdsCrumb(pushCrumb, href.orEmpty())
                    else -> state.crumbs
                }
                state.copy(
                    entries = page.entries,
                    title = page.title,
                    nextHref = page.nextHref,
                    crumbs = crumbs,
                    loading = false,
                )
            }
        }
    }
}
