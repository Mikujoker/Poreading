package io.legado.app.ui.book.source.repair

/**
 * 「AI 修源」运行状态的**共享快照**：由 ViewModel 的 uiState 自动同步进来，
 * 再由 `GET /getRepairJournal` 读出去——这样调试/验证不必截图。
 */
object SourceRepairJournal {

    @Volatile
    private var latest: SourceRepairUiState? = null

    @Volatile
    private var updatedAt: Long = 0L

    fun publish(state: SourceRepairUiState) {
        latest = state
        updatedAt = System.currentTimeMillis()
    }

    fun snapshot(): Map<String, Any?> {
        val api = apiSnapshot
        val state = latest ?: return api ?: linkedMapOf("hasRun" to false)
        return linkedMapOf(
            "hasRun" to true,
            "updatedAt" to updatedAt,
            "ui" to uiSnapshot(state),
            "api" to api,
        )
    }

    private fun uiSnapshot(state: SourceRepairUiState): Map<String, Any?> = linkedMapOf(
        "sourceName" to state.sourceName,
        "sourceUrl" to state.sourceUrl,
        "query" to state.query,
        "target" to state.target.name,
        "running" to state.running,
        "round" to state.round,
        "maxRounds" to state.maxRounds,
        "usedTokens" to state.usedTokens,
        "ok" to state.ok,
        "verdict" to state.verdict,
        "log" to state.log,
        "steps" to state.steps.map {
            linkedMapOf("title" to it.title, "detail" to it.detail, "ok" to it.ok)
        },
    )
    @Volatile
    private var apiSnapshot: Map<String, Any?>? = null

    /** 后台任务（如整源生成）的进度/结果快照；与界面状态分开存 */
    fun publishApi(snapshot: Map<String, Any?>) {
        apiSnapshot = snapshot
        updatedAt = System.currentTimeMillis()
    }

}
