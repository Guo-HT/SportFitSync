package com.sportfit.sync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.sportfit.sync.domain.SyncStatus
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import kotlinx.coroutines.flow.map

/**
 * Activity picker.
 *
 * Reached from "选择活动同步". Mirrors the web admin's select-activities page,
 * but as the default preview step rather than a hidden entry point, and with
 * exclusions available via long-press.
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${direction.source.displayName} → ${direction.target.displayName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.refreshActivityList(direction) },
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Text("  拉取最新列表")
                }
                FilterChip(
                    selected = showSynced,
                    onClick = { showSynced = !showSynced },
                    label = { Text("显示已同步") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = selectable.map { it.activityId }.toSet() }) {
                    Text("全选")
                }
                TextButton(onClick = {
                    selected = selectable.map { it.activityId }.toSet() - selected
                }) { Text("反选") }
                TextButton(onClick = { selected = emptySet() }) { Text("清空") }
            }
            Text(
                "共 ${items.size} 条 · 可同步 ${selectable.size} 条 · 已选 ${selected.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                item {
                    Text(
                        "还没有活动列表，先点「拉取最新列表」。",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.activityId }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !item.excluded) {
                            selected = if (item.activityId in selected) {
                                selected - item.activityId
                            } else {
                                selected + item.activityId
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = item.activityId in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + item.activityId else selected - item.activityId
                        },
                        enabled = !item.excluded,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.name.ifBlank { item.activityId },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${Format.date(item.startTime)} · ${Format.distance(item.distanceMeters)} · ${Format.duration(item.durationSec)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        item.excluded -> StatusBadge("已排除", StatusTone.NEUTRAL)
                        item.status == SyncStatus.SYNCED -> StatusBadge("已同步", StatusTone.OK)
                        item.status == SyncStatus.FAILED -> StatusBadge("失败", StatusTone.ERROR)
                        else -> StatusBadge("待同步", StatusTone.NEUTRAL)
                    }
                    IconButton(
                        onClick = {
                            if (item.excluded) {
                                viewModel.unexclude(direction, item.activityId)
                            } else {
                                viewModel.exclude(direction, item.activityId)
                            }
                        },
                    ) {
                        Text(
                            if (item.excluded) "恢复" else "排除",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("返回") }
            // Export is the escape hatch: it needs no target platform, so it
            // keeps working even when a platform's import path breaks.
            OutlinedButton(
                onClick = { viewModel.startExport(selected) },
                enabled = selected.isNotEmpty() && !loading,
                modifier = Modifier.weight(1.4f),
            ) {
                Text("导出文件")
            }
            Button(
                onClick = { viewModel.startSync(selectedIds = selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1.6f),
            ) {
                Text("同步 ${selected.size} 条")
            }
        }
    }
}
