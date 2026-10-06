package io.legado.app.ui.book.source.repair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiTextGateway
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「AI 修源」界面的状态宿主：自身不实现循环，只把 `SourceRepairRunner` 的步骤/报告映射成 UI 状态；
 * 状态会同步到 `SourceRepairJournal`（`GET /getRepairJournal` 可读，验证不必截图）。
 */
class SourceRepairViewModel(
    private val bookSourceRepository: BookSourceRepository,
    private val aiProfileGateway: AiProfileGateway,
    private val aiTextGateway: AiTextGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SourceRepairUiState())
    val uiState = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<SourceRepairEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var source: BookSource? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            _uiState.collect { SourceRepairJournal.publish(it) }
        }
    }

    fun onIntent(intent: SourceRepairIntent) {
        when (intent) {
            is SourceRepairIntent.Load -> load(intent.sourceUrl)
            is SourceRepairIntent.SetQuery -> _uiState.update { it.copy(query = intent.value) }
            is SourceRepairIntent.SetTarget -> _uiState.update { it.copy(target = intent.target) }
            SourceRepairIntent.Start -> start()
            SourceRepairIntent.Stop -> stop()
            SourceRepairIntent.CopyLog -> _effects.tryEmit(SourceRepairEffect.CopyText(_uiState.value.log))
        }
    }

    private fun load(sourceUrl: String?) = viewModelScope.launch {
        val loaded = withContext(Dispatchers.IO) {
            sourceUrl?.let { bookSourceRepository.getBookSource(it) }
        }
        source = loaded
        _uiState.update {
            it.copy(
                loading = false,
                sourceName = loaded?.bookSourceName.orEmpty(),
                sourceUrl = loaded?.bookSourceUrl.orEmpty(),
            )
        }
        if (loaded == null) {
            _effects.tryEmit(SourceRepairEffect.ShowMessage("未找到书源"))
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
        _uiState.update { it.copy(running = false) }
    }

    private fun start() {
        val current = source ?: return
        if (_uiState.value.running) return
        job = viewModelScope.launch {
            val request = SourceRepairRunner.Request(
                sourceUrl = current.bookSourceUrl,
                input = _uiState.value.query,
                targetField = _uiState.value.target.field,
                targetPage = _uiState.value.target.page,
            )
            _uiState.update {
                it.copy(
                    running = true, steps = persistentListOf(), verdict = "", ok = null,
                    log = "", round = 0, usedTokens = 0,
                )
            }
            val runner = SourceRepairRunner(bookSourceRepository, aiProfileGateway, aiTextGateway)
            val report = runner.run(
                request = request,
                onStep = { item ->
                    _uiState.update {
                        it.copy(
                            steps = (it.steps + RepairStep(item.title, item.detail, item.ok)).toImmutableList()
                        )
                    }
                },
                onProgress = { round, tokens ->
                    _uiState.update { it.copy(round = round, usedTokens = tokens) }
                },
            )
            _uiState.update {
                it.copy(running = false, verdict = report.verdict, ok = report.ok, log = report.log)
            }
        }
    }

    companion object {
        const val MAX_ROUNDS = SourceRepairRunner.MAX_ROUNDS
    }
}
