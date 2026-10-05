package io.legado.app.ui.book.source.manage

import android.app.Application
import android.net.Uri
import android.text.TextUtils
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.data.repository.UploadRepository
import io.legado.app.domain.gateway.BookSourceCheckFailure
import io.legado.app.domain.gateway.BookSourceCheckGateway
import io.legado.app.domain.gateway.BookSourceCheckResult
import io.legado.app.domain.gateway.BookSourceCheckStatus
import io.legado.app.domain.gateway.CheckSourceSettings
import io.legado.app.domain.gateway.CheckSourceSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.source.SourceHelp
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.ImportItemWrapper
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.splitNotBlank
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class BookSourceViewModel(
    private val application: Application,
    private val repository: BookSourceRepository,
    private val uploadRepository: UploadRepository,
    private val otherSettingsGateway: OtherSettingsGateway,
    private val checkGateway: BookSourceCheckGateway,
    private val checkSettingsGateway: CheckSourceSettingsGateway,
) : ViewModel() {
    companion object {
        const val FILTER_ENABLED = "@enabled"
        const val FILTER_DISABLED = "@disabled"
        const val FILTER_LOGIN = "@login"
        const val FILTER_NO_GROUP = "@noGroup"
        const val FILTER_ENABLED_EXPLORE = "@enabledExplore"
        const val FILTER_DISABLED_EXPLORE = "@disabledExplore"
        const val PREFIX_GROUP = "group:"
    }

    private val searchKey = MutableStateFlow("")
    private val isSearchMode = MutableStateFlow(false)
    private val filter = MutableStateFlow<String?>(null)
    private val tab = MutableStateFlow(BookSourceTab.COMMON)
    private val sort = MutableStateFlow(BookSourceSort.Default)
    private val sortAscending = MutableStateFlow(true)
    private val groupByDomain = MutableStateFlow(false)
    private val localItems = MutableStateFlow<List<BookSourcePart>?>(null)
    private val enabledOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** 星标的乐观覆盖值：点下去要立刻变，不能等全表快照回来（那要几秒） */
    private val favoriteOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val importState =
        MutableStateFlow<BaseImportUiState<BookSource>>(BaseImportUiState.Idle)
    private val _effects = MutableSharedFlow<BookSourceEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            var wasRunning = false
            checkGateway.state.collect { state ->
                if (wasRunning && !state.isRunning) {
                    val message = if (state.cancelledCount > 0) {
                        application.getString(
                            io.legado.app.R.string.book_source_check_cancelled,
                            state.succeededCount,
                            state.failedCount,
                            state.cancelledCount,
                        )
                    } else {
                        application.getString(
                            io.legado.app.R.string.book_source_check_completed,
                            state.succeededCount,
                            state.failedCount,
                        )
                    }
                    _effects.tryEmit(BookSourceEffect.ShowSnackbar(message))
                }
                wasRunning = state.isRunning
            }
        }
    }

    private val sourceFilter = combine(searchKey, filter, tab) { query, activeFilter, activeTab ->
        SourceFilter(query, activeFilter, activeTab)
    }

    private val sourceSort = combine(sort, sortAscending, groupByDomain) {
            activeSort,
            ascending,
            byDomain,
        ->
        SourceSort(activeSort, ascending, byDomain)
    }

    /**
     * 校验状态按 500ms 采样后再喂给 UI。
     * 校验每完成一条就发一次状态，而 UI 要拿它重算整张列表（行上的校验消息 + 失效筛选），
     * 几千条连着发会把主线程压满。采样只让界面慢半秒，不影响结果本身。
     * 注意 [init] 判断「刚跑完」用的是未采样的原状态，那里不能采样，否则会漏掉结束那一帧。
     */
    private val checkUiState = checkGateway.state.sample(500)

    /** 校验判失败的书源。只活在内存里：进程重启就没了，重新点刷新即可。 */
    private val failedIds = checkUiState
        .map { check ->
            check.results.filterValues { it.status == BookSourceCheckStatus.Failed }.keys.toSet()
        }
        .distinctUntilChanged()

    /**
     * 全量书源快照，列表与「刷新」共用同一次全表查询。
     *
     * 校验进行中刻意不订阅：校验每写完一条，Room 就会让这个 119 MB 的全表查询重跑一遍
     * （PC 上单次数秒），几千条连着写会把查询线程压死。校验期间用户看的是进度，
     * 列表沿用手上最后一份快照就够了；跑完自动恢复订阅并刷新一次。
     * null 表示第一份快照还没到，用来把「还在加载」和「真的没有书源」区分开。
     */
    private val allSources: StateFlow<List<BookSourcePart>?> = checkGateway.state
        .map { it.isRunning }
        .distinctUntilChanged()
        .flatMapLatest { running -> if (running) emptyFlow() else repository.flowAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val importedOrFilteredItems: Flow<List<BookSourcePart>> = combine(
        allSources,
        localItems,
        sourceFilter,
        failedIds,
        favoriteOverrides,
    ) { snapshot, local, activeFilter, failed, favorites ->
        when {
            snapshot == null -> emptyList()
            local != null -> {
                val latestById = snapshot.associateBy { it.bookSourceUrl }
                local.mapNotNull { latestById[it.bookSourceUrl] }
            }

            else -> snapshot.filterFor(activeFilter.name, activeFilter.query)
                .filter { part ->
                    val favorite = favorites[part.bookSourceUrl] ?: part.isFavorite
                    activeFilter.tab.matches(part.bookSourceUrl, favorite, failed)
                }
        }
    }

    private val visibleItems = combine(importedOrFilteredItems, localItems, sourceSort) {
            items,
            local,
            activeSort,
        ->
        if (local == null) {
            items.sortFor(activeSort.sort, activeSort.ascending, activeSort.groupByDomain)
        } else {
            items
        }
    }

    private val listConfiguration = combine(
        sourceFilter,
        isSearchMode,
        sourceSort,
        enabledOverrides,
        favoriteOverrides,
    ) { activeFilter, searchMode, activeSort, pendingEnabled, pendingFavorite ->
        ListConfiguration(
            activeFilter,
            searchMode,
            activeSort,
            pendingEnabled,
            pendingFavorite,
        )
    }

    private val listState = combine(
        visibleItems,
        repository.flowGroups(),
        listConfiguration,
    ) { visible, groups, configuration ->
        BookSourceUiState(
            items = visible.map { source ->
                BookSourceItemUi(
                    id = source.bookSourceUrl,
                    domain = NetworkUtils.getSubDomainOrNull(source.bookSourceUrl) ?: "#",
                    name = source.bookSourceName,
                    group = source.bookSourceGroup,
                    enabled = configuration.enabledOverrides[source.bookSourceUrl] ?: source.enabled,
                    enabledExplore = source.enabledExplore,
                    hasLoginUrl = source.hasLoginUrl,
                    hasExploreUrl = source.hasExploreUrl,
                    customOrder = source.customOrder,
                    favorite = configuration.favoriteOverrides[source.bookSourceUrl]
                        ?: source.isFavorite,
                )
            }.toImmutableList(),
            searchKey = configuration.filter.query,
            groupFilterName = configuration.filter.name?.displayName(application),
            activeFilter = configuration.filter.name,
            tab = configuration.filter.tab,
            groups = groups.toImmutableList(),
            sort = configuration.sort.sort,
            sortAscending = configuration.sort.ascending,
            groupByDomain = configuration.sort.groupByDomain,
            interaction = io.legado.app.ui.widget.components.list.InteractionState(
                isSearchMode = configuration.isSearchMode,
                // 第一份快照还没到：把「加载中」和「真的没有书源」区分开
                isLoading = allSources.value == null,
            ),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSourceUiState())

    private data class SourceFilter(
        val query: String,
        val name: String?,
        val tab: BookSourceTab,
    )

    private data class SourceSort(
        val sort: BookSourceSort,
        val ascending: Boolean,
        val groupByDomain: Boolean,
    )

    private data class ListConfiguration(
        val filter: SourceFilter,
        val isSearchMode: Boolean,
        val sort: SourceSort,
        val enabledOverrides: Map<String, Boolean>,
        val favoriteOverrides: Map<String, Boolean>,
    )

    val uiState = combine(
        listState,
        importState,
        checkUiState,
        checkSettingsGateway.settings,
    ) { state, importing, check, settings ->
        state.copy(
            items = state.items.map { item ->
                item.copy(
                    checkMessage = check.results[item.id]?.displayMessage(application)
                )
            }
                .toImmutableList(),
            importState = importing,
            hasScanResult = check.results.isNotEmpty(),
            isChecking = check.isRunning,
            checkProgress = if (check.isRunning) application.getString(
                io.legado.app.R.string.progress_show,
                check.currentSourceName,
                check.completed,
                check.total,
            ) else null,
            checkOptions = BookSourceCheckOptionsUi(
                timeoutSeconds = settings.timeoutMillis / 1000,
                checkSearch = settings.checkSearch,
                checkDiscovery = settings.checkDiscovery,
                checkInfo = settings.checkInfo,
                checkCategory = settings.checkCategory,
                checkContent = settings.checkContent,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSourceUiState())

    fun onIntent(intent: BookSourceIntent) {
        when (intent) {
            is BookSourceIntent.SetSearchMode -> {
                isSearchMode.value = intent.enabled
                if (!intent.enabled) searchKey.value = ""
            }

            is BookSourceIntent.SetSearchQuery -> {
                localItems.value = null; searchKey.value = intent.query
            }

            is BookSourceIntent.SetFilter -> {
                localItems.value = null; filter.value = intent.filter
            }

            is BookSourceIntent.SetTab -> {
                localItems.value = null; tab.value = intent.tab
            }

            is BookSourceIntent.ToggleFavorite -> setFavorite(intent.id)

            is BookSourceIntent.SetSort -> {
                localItems.value = null; sort.value = intent.sort
            }

            BookSourceIntent.ToggleSortDirection -> {
                localItems.value = null; sortAscending.update { !it }
            }

            BookSourceIntent.ToggleGroupByDomain -> {
                localItems.value = null; groupByDomain.update { !it }
            }

            is BookSourceIntent.SetEnabled -> setEnabled(intent.id, intent.enabled)

            is BookSourceIntent.SetExploreEnabled -> updateExplore(intent.ids, intent.enabled)
            is BookSourceIntent.Delete -> launch { repository.deleteSourceParts(parts(intent.ids)) }
            is BookSourceIntent.MoveToEdge -> moveToEdge(intent.ids, intent.toTop)
            is BookSourceIntent.MoveItem -> moveItem(intent.from, intent.to)
            BookSourceIntent.SaveSortOrder -> saveSortOrder()
            is BookSourceIntent.CommitSortOrder -> commitSortOrder(intent.ids, intent.ascending)
            is BookSourceIntent.UpdateGroup -> updateGroup(intent.old, intent.new)
            is BookSourceIntent.DeleteGroup -> updateGroup(intent.group, "")
            BookSourceIntent.RefreshCheck -> refreshCheck()

            is BookSourceIntent.UpdateCheckOptions -> viewModelScope.launch {
                checkSettingsGateway.update(intent.options.toSettings())
            }

            BookSourceIntent.CancelCheck -> _effects.tryEmit(BookSourceEffect.CancelCheck)
            is BookSourceIntent.Import -> importSources(intent.text)
            is BookSourceIntent.Export -> exportSources(intent.uri, intent.ids)
            is BookSourceIntent.Upload -> uploadSources(intent.ids)
            is BookSourceIntent.ToggleImportItem -> updateImportItems { items ->
                items.mapIndexed { index, item -> if (index == intent.index) item.copy(isSelected = !item.isSelected) else item }
            }

            is BookSourceIntent.ToggleImportAll -> updateImportItems { items ->
                items.map {
                    it.copy(
                        isSelected = intent.selected
                    )
                }
            }

            is BookSourceIntent.UpdateImportItem -> updateImportItems { items ->
                items.mapIndexed { index, item -> if (index == intent.index) item.copy(data = intent.source) else item }
            }

            is BookSourceIntent.SelectImportStatus -> selectImportStatus(intent.status)
            is BookSourceIntent.SetImportKeepName -> updateImportOptions { it.copy(keepOriginalName = intent.enabled) }
            is BookSourceIntent.SetImportKeepGroup -> updateImportOptions {
                it.copy(
                    keepOriginalGroup = intent.enabled
                )
            }

            is BookSourceIntent.SetImportKeepEnable -> updateImportOptions {
                it.copy(
                    keepOriginalEnable = intent.enabled
                )
            }

            is BookSourceIntent.SetImportCustomGroup -> updateImportOptions {
                it.copy(
                    customGroup = intent.group?.trim()?.takeIf(String::isNotEmpty),
                    isAddGroup = intent.add
                )
            }

            BookSourceIntent.CancelImport -> importState.value = BaseImportUiState.Idle
            BookSourceIntent.SaveImportedSources -> saveImportedSources()
        }
    }

    private fun launch(block: suspend () -> Unit) =
        viewModelScope.launch(Dispatchers.IO) { block() }

    private fun setEnabled(id: String, enabled: Boolean) {
        enabledOverrides.update { it + (id to enabled) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.setEnabled(id, enabled)
                // 等共享快照确认，别再单独开一次全表查询（那要好几秒）
                allSources.first { sources ->
                    sources?.firstOrNull { it.bookSourceUrl == id }?.enabled == enabled
                }
            }
            enabledOverrides.update { it - id }
        }
    }

    /** 星标切换：先乐观置位，等快照确认；写库失败就把覆盖值摘掉，星标回退。 */
    private fun setFavorite(id: String) {
        val current = favoriteOverrides.value[id]
            ?: allSources.value?.firstOrNull { it.bookSourceUrl == id }?.isFavorite
            ?: false
        val next = !current
        favoriteOverrides.update { it + (id to next) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.setFavorite(id, next)
                allSources.first { sources ->
                    sources?.firstOrNull { it.bookSourceUrl == id }?.isFavorite == next
                }
            }.onFailure { favoriteOverrides.update { it - id } }
        }
    }

    /**
     * 「刷新」：只校验常用书源，把失效的挑出来。
     * 用户口径 —— 没被选成常用的源不测失效，所以失效永远是常用的子集。
     */
    private fun refreshCheck() {
        if (checkGateway.state.value.isRunning) return
        viewModelScope.launch {
            val snapshot = allSources.first { it != null }.orEmpty()
            val ids = snapshot
                .filter { favoriteOverrides.value[it.bookSourceUrl] ?: it.isFavorite }
                .map { it.bookSourceUrl }
                .toSet()
            if (ids.isEmpty()) {
                _effects.emit(
                    BookSourceEffect.ShowSnackbar(
                        application.getString(io.legado.app.R.string.source_check_no_favorite)
                    )
                )
                return@launch
            }
            _effects.emit(
                BookSourceEffect.StartCheck(
                    ids = ids,
                    keyword = application.getString(
                        io.legado.app.R.string.book_source_check_default_keyword
                    ),
                )
            )
        }
    }

    private suspend fun parts(ids: Set<String>) =
        repository.getAllPart().filter { it.bookSourceUrl in ids }
    private fun updateExplore(ids: Set<String>, enabled: Boolean) =
        launch { repository.setExploreEnabled(enabled, parts(ids)) }

    private fun updateGroup(old: String, new: String) = launch {
        val sources = repository.getByGroup(old)
        sources.forEach { source ->
            source.bookSourceGroup?.splitNotBlank(",")?.toHashSet()
                ?.apply { remove(old); if (new.isNotBlank()) add(new) }
                ?.let { source.bookSourceGroup = TextUtils.join(",", it) }
        }
        repository.updateSources(*sources.toTypedArray())
    }

    private fun moveToEdge(ids: Set<String>, toTop: Boolean) = launch {
        repository.moveToEdge(parts(ids), toTop)
    }

    private fun moveItem(from: Int, to: Int) {
        if (sort.value != BookSourceSort.Default || groupByDomain.value) return
        val moved = (localItems.value ?: uiState.value.items.map { item ->
            BookSourcePart(bookSourceUrl = item.id, customOrder = item.customOrder)
        }).toMutableList()
        if (from !in moved.indices || to !in moved.indices) return
        moved.add(to, moved.removeAt(from)); localItems.value = moved
    }

    private fun saveSortOrder() {
        val items = localItems.value ?: return
        launch {
            repository.updateOrder(items.mapIndexed { index, item -> item.copy(customOrder = index + 1) }); localItems.value =
            null
        }
    }

    private fun commitSortOrder(ids: List<String>, ascending: Boolean) = launch {
        val sourcesById = repository.getAllPart().associateBy { it.bookSourceUrl }
        val orderSlots = ids.mapNotNull { sourcesById[it]?.customOrder }
            .let { if (ascending) it.sorted() else it.sortedDescending() }
        val ordered = ids.mapIndexedNotNull { index, id ->
            sourcesById[id]?.copy(
                customOrder = orderSlots.getOrNull(index) ?: return@mapIndexedNotNull null
            )
        }
        repository.updateOrder(ordered)
    }

    private fun importSources(input: String) {
        importState.value = BaseImportUiState.Loading
        launch {
            runCatching {
                val text = if (input.isAbsUrl()) {
                    okHttpClient.newCallResponseBody {
                        if (input.endsWith("#requestWithoutUA")) {
                            url(input.substringBeforeLast("#requestWithoutUA")); header(
                                AppConst.UA_NAME,
                                "null"
                            )
                        } else url(input)
                    }.decompressed().text("utf-8")
                } else if (input.isUri()) {
                    input.toUri().inputStream(application).getOrThrow()
                        .bufferedReader()
                        .use { it.readText() }
                } else input
                val sources = parseImportSources(text)
                val settings = otherSettingsGateway.currentSettings
                BaseImportUiState.Success(
                    source = input,
                    items = sources.map { source ->
                        val old = repository.getBookSource(source.bookSourceUrl)
                        val status = when {
                            old == null -> ImportStatus.New
                            source.lastUpdateTime > old.lastUpdateTime -> ImportStatus.Update
                            else -> ImportStatus.Existing
                        }
                        ImportItemWrapper(
                            data = source,
                            oldData = old,
                            isSelected = status != ImportStatus.Existing,
                            status = status,
                        )
                    },
                    keepOriginalName = settings.importKeepName,
                    keepOriginalGroup = settings.importKeepGroup,
                    keepOriginalEnable = settings.importKeepEnable,
                )
            }.onSuccess { importState.value = it }
                .onFailure {
                    importState.value = BaseImportUiState.Error(
                        it.localizedMessage
                            ?: application.getString(io.legado.app.R.string.book_source_import_failed)
                    )
                }
        }
    }

    private suspend fun parseImportSources(text: String): List<BookSource> {
        val sources = when {
            text.isJsonArray() -> GSON.fromJsonArray<BookSource>(text).getOrThrow()
            text.isJsonObject() -> {
                val objectValue = JsonParser.parseString(text).asJsonObject
                val sourceUrls = objectValue.getAsJsonArray("sourceUrls")
                if (sourceUrls != null) {
                    sourceUrls.flatMap { element ->
                        val url = element.asString
                        val sourceText = okHttpClient.newCallResponseBody {
                            if (url.endsWith("#requestWithoutUA")) {
                                url(url.substringBeforeLast("#requestWithoutUA")); header(
                                    AppConst.UA_NAME,
                                    "null"
                                )
                            } else url(url)
                        }.decompressed().text("utf-8")
                        parseImportSources(sourceText)
                    }
                } else listOf(GSON.fromJsonObject<BookSource>(text).getOrThrow())
            }

            else -> error(application.getString(io.legado.app.R.string.invalid_format))
        }
        require(sources.all { it.bookSourceUrl.isNotBlank() }) {
            application.getString(io.legado.app.R.string.book_source_invalid_source)
        }
        return sources
    }

    private fun updateImportItems(transform: (List<ImportItemWrapper<BookSource>>) -> List<ImportItemWrapper<BookSource>>) {
        val state = importState.value as? BaseImportUiState.Success<BookSource> ?: return
        importState.value = state.copy(items = transform(state.items), version = state.version + 1)
    }

    private fun updateImportOptions(
        transform: (BaseImportUiState.Success<BookSource>) -> BaseImportUiState.Success<BookSource>
    ) {
        val state = importState.value as? BaseImportUiState.Success<BookSource> ?: return
        val updated = transform(state)
        importState.value = updated
        viewModelScope.launch {
            otherSettingsGateway.update { settings ->
                settings.copy(
                    importKeepName = updated.keepOriginalName,
                    importKeepGroup = updated.keepOriginalGroup,
                    importKeepEnable = updated.keepOriginalEnable,
                )
            }
        }
    }

    private fun selectImportStatus(status: ImportStatus) {
        val state = importState.value as? BaseImportUiState.Success<BookSource> ?: return
        val matching = state.items.filter { it.status == status }
        val select = matching.any { !it.isSelected }
        importState.value = state.copy(
            items = state.items.map { if (it.status == status) it.copy(isSelected = select) else it }
        )
    }

    private fun saveImportedSources() {
        val state = importState.value as? BaseImportUiState.Success<BookSource> ?: return
        launch {
            val sources = state.items.filter { it.isSelected }.map { wrapper ->
                wrapper.data.copy().apply {
                    wrapper.oldData?.let { old ->
                        if (state.keepOriginalName) bookSourceName = old.bookSourceName
                        if (state.keepOriginalGroup) bookSourceGroup = old.bookSourceGroup
                        if (state.keepOriginalEnable) {
                            enabled = old.enabled
                            enabledExplore = old.enabledExplore
                        }
                        customOrder = old.customOrder
                    }
                    state.customGroup?.let { group ->
                        bookSourceGroup = if (state.isAddGroup) {
                            linkedSetOf<String>().apply {
                                bookSourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)
                                    ?.let(::addAll)
                                add(group)
                            }.joinToString(",")
                        } else group
                    }
                }
            }
            SourceHelp.insertBookSource(*sources.toTypedArray())
            ContentProcessor.upReplaceRules()
            importState.value = BaseImportUiState.Idle
            _effects.tryEmit(BookSourceEffect.ImportFinished)
            _effects.tryEmit(
                BookSourceEffect.ShowSnackbar(
                    application.getString(io.legado.app.R.string.book_source_import_success)
                )
            )
        }
    }

    private fun exportSources(uri: Uri, ids: Set<String>) = launch {
        runCatching {
            val selected = repository.getAll().filter { ids.isEmpty() || it.bookSourceUrl in ids }
            application.contentResolver.openOutputStream(uri)?.bufferedWriter()
                ?.use { it.write(GSON.toJson(selected)) }
                ?: error(application.getString(io.legado.app.R.string.book_source_export_open_failed))
        }.onSuccess {
            _effects.tryEmit(
                BookSourceEffect.ShowSnackbar(
                    application.getString(io.legado.app.R.string.export_success)
                )
            )
        }.onFailure {
            _effects.tryEmit(
                BookSourceEffect.ShowSnackbar(
                    application.getString(
                        io.legado.app.R.string.book_source_export_failed,
                        it.localizedMessage.orEmpty(),
                    )
                )
            )
        }
    }

    private fun uploadSources(ids: Set<String>) = launch {
        runCatching {
            val selected = repository.getAll().filter { ids.isEmpty() || it.bookSourceUrl in ids }
            uploadRepository.upload(
                fileName = "bookSource.json",
                file = GSON.toJson(selected),
                contentType = "application/json",
            )
        }.onSuccess { url ->
            _effects.tryEmit(
                BookSourceEffect.ShowSnackbar(
                    message = application.getString(io.legado.app.R.string.book_source_upload_success, url),
                    actionLabel = application.getString(io.legado.app.R.string.copy_url),
                    url = url,
                )
            )
        }.onFailure {
            _effects.tryEmit(
                BookSourceEffect.ShowSnackbar(
                    application.getString(
                        io.legado.app.R.string.book_source_upload_failed,
                        it.localizedMessage.orEmpty(),
                    )
                )
            )
        }
    }

}

private fun BookSourceCheckOptionsUi.toSettings() = CheckSourceSettings(
    timeoutMillis = timeoutSeconds * 1000,
    checkSearch = checkSearch,
    checkDiscovery = checkDiscovery,
    checkInfo = checkInfo,
    checkCategory = checkCategory,
    checkContent = checkContent,
)

private fun BookSourceCheckResult.displayMessage(application: Application): String {
    val errorDetail = detail ?: application.getString(io.legado.app.R.string.unknown_error)
    return when (status) {
        BookSourceCheckStatus.Pending -> application.getString(
            io.legado.app.R.string.book_source_check_waiting
        )

        BookSourceCheckStatus.Running -> application.getString(
            io.legado.app.R.string.book_source_check_running
        )

        BookSourceCheckStatus.Succeeded -> application.getString(
            io.legado.app.R.string.book_source_check_succeeded
        )

        BookSourceCheckStatus.Cancelled -> application.getString(
            io.legado.app.R.string.book_source_check_item_cancelled
        )

        BookSourceCheckStatus.Failed -> when (failure) {
            BookSourceCheckFailure.SourceMissing -> application.getString(
                io.legado.app.R.string.book_source_check_source_missing
            )

            BookSourceCheckFailure.SaveFailed -> application.getString(
                io.legado.app.R.string.book_source_check_save_failed,
                errorDetail,
            )

            BookSourceCheckFailure.Incomplete -> application.getString(
                io.legado.app.R.string.book_source_check_incomplete
            )

            BookSourceCheckFailure.CheckFailed,
            null -> application.getString(
                io.legado.app.R.string.book_source_check_failed,
                errorDetail,
            )
        }
    }
}

private fun List<BookSourcePart>.filterFor(filter: String?, query: String): List<BookSourcePart> =
    filter { source ->
        val filterMatch = when (filter) {
            null -> true; BookSourceViewModel.FILTER_ENABLED -> source.enabled; BookSourceViewModel.FILTER_DISABLED -> !source.enabled
            BookSourceViewModel.FILTER_LOGIN -> source.hasLoginUrl; BookSourceViewModel.FILTER_NO_GROUP -> source.bookSourceGroup.isNullOrBlank()
            BookSourceViewModel.FILTER_ENABLED_EXPLORE -> source.enabledExplore; BookSourceViewModel.FILTER_DISABLED_EXPLORE -> !source.enabledExplore
            else -> filter.startsWith(BookSourceViewModel.PREFIX_GROUP) && source.bookSourceGroup?.split(
                ","
            )?.contains(filter.removePrefix(BookSourceViewModel.PREFIX_GROUP)) == true
        }
        filterMatch && (query.isBlank() || listOf(
            source.bookSourceName,
            source.bookSourceUrl,
            source.bookSourceGroup
        ).any { it?.contains(query, true) == true })
    }

private fun List<BookSourcePart>.sortFor(
    sort: BookSourceSort,
    ascending: Boolean,
    byDomain: Boolean
): List<BookSourcePart> {
    if (byDomain) {
        val domains = associateWith { NetworkUtils.getSubDomainOrNull(it.bookSourceUrl) ?: "#" }
        return sortedWith(compareBy<BookSourcePart> { domains.getValue(it) == "#" }
            .thenBy { domains.getValue(it) }
            .thenByDescending { it.lastUpdateTime })
    }
    val comparator = when (sort) {
        BookSourceSort.Name -> compareBy<BookSourcePart> { it.bookSourceName }
        BookSourceSort.Url -> compareBy { it.bookSourceUrl }; BookSourceSort.Weight -> compareBy { it.weight }
        BookSourceSort.Update -> compareByDescending<BookSourcePart> { it.lastUpdateTime }; BookSourceSort.Respond -> compareBy { it.respondTime }
        BookSourceSort.Enable -> compareByDescending<BookSourcePart> { it.enabled }.thenBy { it.bookSourceName }
        BookSourceSort.Default -> compareBy { it.customOrder }
    }
    return if (ascending) sortedWith(comparator) else sortedWith(comparator.reversed())
}

private fun String.displayName(application: Application) = when (this) {
    BookSourceViewModel.FILTER_ENABLED -> application.getString(io.legado.app.R.string.enabled)
    BookSourceViewModel.FILTER_DISABLED -> application.getString(io.legado.app.R.string.disabled)
    BookSourceViewModel.FILTER_LOGIN -> application.getString(io.legado.app.R.string.need_login)
    BookSourceViewModel.FILTER_NO_GROUP -> application.getString(io.legado.app.R.string.no_group)
    BookSourceViewModel.FILTER_ENABLED_EXPLORE -> application.getString(io.legado.app.R.string.enabled_explore)
    BookSourceViewModel.FILTER_DISABLED_EXPLORE -> application.getString(io.legado.app.R.string.disabled_explore)
    else -> removePrefix(BookSourceViewModel.PREFIX_GROUP)
}
