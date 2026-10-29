package com.sportfit.sync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sportfit.sync.R
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.EmptyState
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.PlatformAvatar
import com.sportfit.sync.ui.components.ScreenHeader
import com.sportfit.sync.ui.components.StatTile
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import com.sportfit.sync.ui.theme.NumericSmallTextStyle
import kotlinx.coroutines.flow.map

/**
 * Activity picker.
 *
 * Reached from "选择活动同步". Mirrors the web admin's select-activities page,
 * but as the default preview step rather than a hidden entry point, and with
 * exclusions available via long-press.
 *
 * 版式：顶部是「标题 + 统计 + 拉取」三段式，中间是活动卡片列表，
 * 底部固定一条操作栏（返回 / 导出文件 / 同步）。
 */
@Composable
fun ActivityPickerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val direction = viewModel.currentDirection()
    val itemsFlow = remember(direction) {
        viewModel.pickerItems(direction).map { rows -> rows.sortedByDescending { it.startTime ?: 0L } }
    }
    val items by itemsFlow.collectAsState(initial = emptyList())
    val loading by viewModel.loadingActivities.collectAsState()

    var selected by remember(direction) { mutableStateOf(setOf<String>()) }
    var showSynced by remember { mutableStateOf(false) }

    val visible = items.filter { showSynced || it.status != SyncStatus.SYNCED }
    val selectable = visible.filterNot { it.excluded }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 标题 ------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                ScreenHeader(
                    title = "选择活动同步",
                    subtitle = "从 ${direction.source.displayName} 读取活动，同步到 ${direction.target.displayName}",
                    icon = Icons.Filled.Checklist,
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- 统计 ------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "活动总数",
                    value = items.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "可同步",
                    value = selectable.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                SelectedStatTile(
                    label = "已选",
                    value = selected.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- 拉取 + 过滤 -----------------------------------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.refreshActivityList(direction) },
                    enabled = !loading,
                    modifier = Modifier.weight(1f),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text("  拉取最新列表")
                }
                FilterChip(
                    selected = showSynced,
                    onClick = { showSynced = !showSynced },
                    label = { Text("显示已同步") },
                )
            }

            // ---- 批量选择 --------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = selectable.map { it.activityId }.toSet() }) {
                    Text("全选")
                }
                TextButton(onClick = {
                    selected = selectable.map { it.activityId }.toSet() - selected
                }) { Text("反选") }
                TextButton(onClick = { selected = emptySet() }) { Text("清空") }
            }
        }

        HairLine()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        illustration = R.drawable.ic_empty_activities,
                        title = "还没有活动列表",
                        message = "先点「拉取最新列表」，把 ${direction.source.displayName} 的活动拉回来。",
                    )
                }
            }
            items(visible, key = { it.activityId }) { item ->
                ActivityRow(
                    item = item,
                    platform = direction.source,
                    checked = item.activityId in selected,
                    onToggle = {
                        selected = if (item.activityId in selected) {
                            selected - item.activityId
                        } else {
                            selected + item.activityId
                        }
                    },
                    onToggleExclude = {
                        if (item.excluded) {
                            viewModel.unexclude(direction, item.activityId)
                        } else {
                            viewModel.exclude(direction, item.activityId)
                        }
                    },
                )
            }
        }

        HairLine()

        // ---- 底部操作栏 ---------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            // Export is the escape hatch: it needs no target platform, so it
            // keeps working even when a platform's import path breaks.
            OutlinedButton(
                onClick = { viewModel.startExport(selected) },
                enabled = selected.isNotEmpty() && !loading,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Text("导出文件")
            }
            Button(
                onClick = { viewModel.startSync(selectedIds = selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .weight(1.4f)
                    .height(50.dp),
            ) {
                Text("同步 ${selected.size} 条")
            }
        }
    }
}

/**
 * 一条活动。
 *
 * 左边平台徽章（一眼看出这条活动来自哪个平台），中间名称 + 时间/距离/时长，
 * 右边状态标签 + 复选框。整行可点切换选中，「排除 / 恢复」是行内独立操作。
 */
@Composable
private fun ActivityRow(
    item: AppViewModel.PickerItem,
    platform: Platform,
    checked: Boolean,
    onToggle: () -> Unit,
    onToggleExclude: () -> Unit,
) {
    Card(
        onClick = onToggle,
        // 已排除的活动不可选中，和原来的 clickable(enabled = !excluded) 一致。
        enabled = !item.excluded,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // 已排除的行只是「不能选」，不是「出错」：
            // 不做禁用态的灰化，否则「恢复」按钮会淡得看不清。
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlatformAvatar(platform = platform, size = 40.dp)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.name.ifBlank { item.activityId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Format.date(item.startTime)} · ${Format.distance(item.distanceMeters)} · " +
                            Format.duration(item.durationSec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onToggleExclude,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) {
                        Text(
                            if (item.excluded) "恢复" else "排除",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.excluded -> StatusBadge("已排除", StatusTone.NEUTRAL)
                    item.status == SyncStatus.SYNCED -> StatusBadge("已同步", StatusTone.OK)
                    item.status == SyncStatus.FAILED -> StatusBadge("失败", StatusTone.ERROR)
                    else -> StatusBadge("待同步", StatusTone.NEUTRAL)
                }
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle() },
                    enabled = !item.excluded,
                )
            }
        }
    }
}

/**
 * 「已选」统计卡。
 *
 * StatTile 的数字颜色由 StatusTone 决定（没有「主色」这一档），
 * 所以照着 StatTile 的样子自己画一张：已选数量用主色，一眼能看见选了多少。
 */
@Composable
private fun SelectedStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = NumericSmallTextStyle, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
