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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.sportfit.sync.ui.theme.LocalSurfacePalette
import com.sportfit.sync.ui.components.StatTile
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import com.sportfit.sync.ui.components.SubPageHeader
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
                // 横向留白交给各子块自己加：标题要用 8dp 起点（和其它子页面一致），
                // 其余内容用 16dp，统一放在这一层会让标题位置差 8dp。
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 标题 ------------------------------------------------------
            // 用公共的 SubPageHeader：返回箭头 + 标题的 padding 各子页面统一，
            // 否则翻页面时标题会「跳」。
            SubPageHeader(
                title = "选择活动同步",
                onBack = onBack,
                subtitle = "从 ${direction.source.displayName} 读取活动，同步到 ${direction.target.displayName}",
                modifier = Modifier.padding(start = 8.dp, end = 16.dp, bottom = 4.dp),
            )

            // ---- 统计 ------------------------------------------------------
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                    // 不要手写空格给图标让位：Button 自己会在图标和文字之间留 8dp，
                    // 手写空格会把可见文字整体顶偏（实测右偏 15~16dp）。
                    Text("拉取最新列表")
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
            // 底部按操作栏的高度留白：最后一行要能完整滚出来，
            // 不能被底部操作栏切掉一半（日期行整行看不见、徽章被压扁）。
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 72.dp),
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
                // M3 默认的禁用态文字（onSurface 38%）只有 2.3:1，
                // 主按钮即使是禁用态也必须看得清 —— 用 onSurfaceVariant（≈4.6:1）。
                colors = ButtonDefaults.outlinedButtonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Text("导出文件")
            }
            Button(
                onClick = { viewModel.startSync(selectedIds = selected) },
                enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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
 * 左边是活动名 + 次要行（时间/距离/时长），右边是状态标签 + 复选框，
 * 「排除 / 恢复」跟在活动名后面。整行可点切换选中。
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
            containerColor = LocalSurfacePalette.current.card,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // 已排除的行只是「不能选」，不是「出错」：
            // 不做禁用态的灰化，否则「恢复」按钮会淡得看不清。
            disabledContainerColor = LocalSurfacePalette.current.card,
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 这里不放平台头像：整页的活动都来自同一个平台，
            // 顶部副标题已经写明「从 X 读取活动」，每行再挂一个徽章纯属重复，
            // 还把「时间 · 距离 · 时长」挤到放不下（截断成 3.64…）。
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name.ifBlank { item.activityId },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // 名称吃掉剩余宽度，「排除」按钮被推到行末、右对齐。
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = onToggleExclude,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp),
                    ) {
                        Text(
                            if (item.excluded) "恢复" else "排除",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                // 第二行独占整行宽度：时间/距离/时长一定能完整显示。
                Text(
                    activityMeta(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 状态徽章 + 复选框作为一个整体靠右收口。
            // Checkbox 的触达区有 48dp、可见方框只有 20dp，
            // 直接排在右边会在徽章后面空出 60dp 左右的死区；
            // 整组右移 14dp（正好是触达区多出来的边距），
            // 可见方框就贴住了卡片内边距，触达区仍然落在卡片范围内。
            Row(
                modifier = Modifier.offset(x = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
 * 活动的次要信息行：「开始时间 · 距离 · 时长」。
 *
 * 缺哪个字段就把**整个字段连同分隔符一起省掉** —— 否则行尾会留下一个
 * 悬空的「· —」（Format 对缺失值返回破折号）。
 * 不改公共的 Format：别的页面也在用它。
 */
private fun activityMeta(item: AppViewModel.PickerItem): String {
    val parts = buildList {
        Format.date(item.startTime).takeIf { it != "—" }?.let { add(it) }
        Format.distance(item.distanceMeters).takeIf { it != "—" }?.let { add(it) }
        Format.duration(item.durationSec).takeIf { it != "—" }?.let { add(it) }
    }
    return parts.joinToString(" · ")
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
        // 实色底 + 主色边框：半透明叠加出来的边框只有 1.7:1，几乎看不见。
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary,
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
