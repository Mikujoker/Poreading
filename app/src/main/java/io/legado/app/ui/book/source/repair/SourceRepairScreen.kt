package io.legado.app.ui.book.source.repair

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.button.ToggleChip
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

@Composable
fun SourceRepairRoute(
    sourceUrl: String?,
    viewModel: SourceRepairViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(sourceUrl) {
        viewModel.onIntent(SourceRepairIntent.Load(sourceUrl))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SourceRepairEffect.CopyText -> context.sendToClip(effect.text)
                is SourceRepairEffect.ShowMessage -> context.toastOnUi(effect.message)
            }
        }
    }
    SourceRepairScreen(state, viewModel::onIntent, onBack)
}

@Composable
private fun SourceRepairScreen(
    state: SourceRepairUiState,
    onIntent: (SourceRepairIntent) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "AI 修源",
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(SourceRepairIntent.CopyLog) },
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制日志",
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AppFloatingActionButton(
                onClick = {
                    onIntent(if (state.running) SourceRepairIntent.Stop else SourceRepairIntent.Start)
                },
                icon = if (state.running) Icons.Default.Stop else Icons.Default.PlayArrow,
                tooltipText = if (state.running) "停止" else "开始修复",
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "controls") { RepairControls(state, onIntent) }
            if (state.verdict.isNotEmpty()) {
                item(key = "verdict") {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AppText(
                                text = if (state.ok == true) "✓ 结论" else "✗ 结论",
                                style = LegadoTheme.typography.labelMediumEmphasized,
                            )
                            AppText(text = state.verdict)
                        }
                    }
                }
            }
            if (state.steps.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage(
                        message = if (state.running) "运行中…" else "填一个关键词或书籍 URL，然后点右下角开始",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                }
            }
            items(state.steps, key = { "${it.title}|${it.detail.hashCode()}" }) { step ->
                RepairStepCard(step)
            }
        }
    }
}

@Composable
private fun RepairControls(
    state: SourceRepairUiState,
    onIntent: (SourceRepairIntent) -> Unit,
) {
    GlassCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = state.sourceName.ifEmpty { "（未找到书源）" },
                style = LegadoTheme.typography.labelMediumEmphasized,
            )
            AppText(text = state.sourceUrl)
            AppTextField(
                value = state.query,
                onValueChange = { onIntent(SourceRepairIntent.SetQuery(it)) },
                label = "关键词或书籍 URL（决定从哪一页开始查）",
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.running,
                maxLines = 3,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RepairTarget.entries.forEach { target ->
                    ToggleChip(
                        label = target.title,
                        selected = state.target == target,
                        onToggle = { onIntent(SourceRepairIntent.SetTarget(target)) },
                    )
                }
            }
            AppText(
                text = if (state.running) {
                    "运行中：第 ${state.round}/${SourceRepairViewModel.MAX_ROUNDS} 轮 · 约 ${state.usedTokens} tokens"
                } else {
                    "预算：${SourceRepairViewModel.MAX_ROUNDS} 轮 / 10 分钟 / 200k tokens；只有端上验证通过才写回"
                }
            )
        }
    }
}

@Composable
private fun RepairStepCard(step: RepairStep) {
    GlassCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(
                text = when (step.ok) {
                    true -> "✓ ${step.title}"
                    false -> "✗ ${step.title}"
                    null -> "• ${step.title}"
                },
                style = LegadoTheme.typography.labelMediumEmphasized,
            )
            AppText(text = step.detail)
        }
    }
}
