package io.legado.app.ui.opds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.OpdsSource
import io.legado.app.model.opds.OpdsFeed
import org.koin.androidx.compose.koinViewModel

/**
 * OPDS 目录源：源管理 + 浏览/搜索 + 下载导入。
 * 入口在书架页的 + 菜单（新增书的一段路），下载后的书直接进书架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsScreen(
    onBack: () -> Unit,
    viewModel: OpdsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OpdsSource?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.message(null)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isBrowsing) {
                            state.title.ifBlank { state.source?.name.orEmpty() }.ifBlank { "OPDS 目录" }
                        } else {
                            "OPDS 目录源"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.isBrowsing) viewModel.back() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.isBrowsing) {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isBrowsing) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加 OPDS 源")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.isBrowsing) {
                BrowseContent(state = state, viewModel = viewModel)
            } else {
                SourceList(
                    state = state,
                    onOpen = viewModel::openSource,
                    onDelete = { pendingDelete = it },
                )
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (showAddDialog) {
        SourceEditDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, url, user, password ->
                viewModel.saveSource(name, url, user, password)
                showAddDialog = false
            },
        )
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 OPDS 源") },
            text = { Text(source.name.ifBlank { source.url }) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSource(source)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SourceList(
    state: OpdsUiState,
    onOpen: (OpdsSource) -> Unit,
    onDelete: (OpdsSource) -> Unit,
) {
    if (state.sources.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有 OPDS 源，点右下角 + 添加（填书库地址，如 /opds）", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.sources, key = { it.url }) { source ->
            ListItem(
                headlineContent = { Text(source.name.ifBlank { source.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        text = source.url + if (source.username.isNotBlank()) "（带账号）" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(source) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                },
                modifier = Modifier.clickableItem { onOpen(source) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun BrowseContent(state: OpdsUiState, viewModel: OpdsViewModel) {
    var keyword by remember { mutableStateOf(state.keyword) }
    LaunchedEffect(state.keyword) { keyword = state.keyword }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            label = { Text("搜索（源地址含 {searchTerms} 走服务端，否则过滤当页）") },
            trailingIcon = {
                IconButton(onClick = { viewModel.search(keyword) }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            },
            singleLine = true,
        )
        if (state.crumbs.isNotEmpty()) {
            Text(
                text = state.crumbs.joinToString(" / ") { it.title },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.entries.isEmpty() && !state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这里没有条目", style = MaterialTheme.typography.bodyMedium)
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.entries, key = { it.title + (it.navHref ?: it.acquisition?.href.orEmpty()) }) { entry ->
                EntryItem(entry = entry, downloading = state.downloading == entry.title, viewModel = viewModel)
                HorizontalDivider()
            }
            item {
                if (state.nextHref != null) {
                    OutlinedButton(
                        onClick = viewModel::nextPage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) { Text("加载下一页") }
                }
            }
        }
    }
}

@Composable
private fun EntryItem(entry: OpdsFeed.Entry, downloading: Boolean, viewModel: OpdsViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickableItem { viewModel.openEntry(entry) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(
                    entry.author,
                    entry.acquisition?.type?.substringBefore(';'),
                    if (entry.isNav) "目录" else null,
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry.isNav) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "进入")
            } else {
                TextButton(onClick = { viewModel.download(entry) }, enabled = !downloading) {
                    Text(if (downloading) "下载中…" else "下载")
                }
            }
        }
    }
}

@Composable
private fun SourceEditDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 OPDS 源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("地址（如 http://192.168.1.9:8083/opds）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可空）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号（可空，Basic Auth）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（可空）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, url, username, password) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 列表项点击（不想要 ripple 之外的额外依赖，简单包一层）。 */
private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
