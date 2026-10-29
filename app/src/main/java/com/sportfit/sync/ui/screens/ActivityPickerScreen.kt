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
import androidx.compose.material3.AlertDialog
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
    // 三选一的筛选：默认只看待同步的，「全部」看全量，「已排除」用来批量恢复。
    var filter by remember(direction) { mutableStateOf(PickerFilter.SYNCABLE) }
    var pendingBatch by remember(direction) { mutableStateOf<BatchAction?>(null) }

    val visible = when (filter) {
        PickerFilter.SYNCABLE -> items.filter { it.status != SyncStatus.SYNCED && !it.excluded }
        PickerFilter.ALL -> items
        PickerFilter.EXCLUDED -> items.filter { it.excluded }
    }
    val selectable = visible.filterNot { it.excluded }
    // 勾选语义：**统一是「我挑中的这些条」**。
    // 以前已排除的活动把复选框禁掉了（那时候勾选只代表「要同步」，
    // 已排除的本来就不该同步），结果在「已排除」筛选下根本挑不出条目，
    // 批量恢复无从谈起。现在勾选一律可用，具体怎么用由当前筛选决定：
    //   待同步/全部 → 批量排除勾选中的；已排除 → 批量恢复勾选中的。
    val selectedVisible = visible.filter { it.activityId in selected }
    // 批量排除的对象：勾选中、且还没被排除的。
    val excludableIds = selectedVisible.filterNot { it.excluded }.map { it.activityId }
    // 批量恢复的对象：勾选中、且已被排除的。
    val restorableIds = selectedVisible.filter { it.excluded }.map { it.activityId }
    // 同步/导出只认「没被排除且在当前列表里」的勾选项：
    // 已排除的活动本来就不该被同步。
    val syncableSelected = selectedVisible.filterNot { it.excluded }.map { it.activityId }.toSet()

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

            // ---- 拉取 ------------------------------------------------------
            Row(
                // 横向留白 16dp：和统计卡、活动卡片、筛选 chips 对齐。
                modifier = Modifier.padding(horizontal = 16.dp),
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
            }

            // ---- 筛选 + 批量操作 ---------------------------------------------
            // 三选一：默认「待同步」，另外两个用来查全量 / 查已排除。
            // 批量按钮**并在这一行的右边**：它作用的就是当前筛选出来的那些活动，
            // 放一起最说得通，也省掉一整行纵向空间（这一页顶部本来就很挤）。
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PickerFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                // 两个按钮互斥：在「已排除」筛选下只可能恢复，其它筛选下只可能排除，
                // 不会出现两个含义打架的按钮同时挂在页面上。
                if (filter == PickerFilter.EXCLUDED) {
                    OutlinedButton(
                        onClick = { pendingBatch = BatchAction.Restore(restorableIds) },
                        enabled = restorableIds.isNotEmpty(),
                    ) { Text("批量恢复 ${restorableIds.size} 条") }
                } else {
                    OutlinedButton(
                        onClick = { pendingBatch = BatchAction.Exclude(excludableIds) },
                        enabled = excludableIds.isNotEmpty(),
                        // 危险操作用错误色描边：和卡片里那条错误色的「删除」同一套语言。
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        // 禁用时描边退回中性色，红框 + 灰字看起来像「出错了」。
                        border = BorderStroke(
                            1.dp,
                            if (excludableIds.isEmpty()) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        ),
                    ) { Text("批量排除 ${excludableIds.size} 条") }
                }
            }

            // ---- 批量选择 --------------------------------------------------
            Row(
                // 横向留白 16dp：和上面的拉取按钮、筛选 chips 对齐。
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 全选/反选针对**当前可见的全部**条目，包括已排除的——
                // 在「已排除」筛选下正是靠它把要恢复的条目一次性挑出来。
                TextButton(onClick = { selected = visible.map { it.activityId }.toSet() }) {
                    Text("全选")
                }
                TextButton(onClick = {
                    selected = visible.map { it.activityId }.toSet() - selected
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
                    // 文案要区分「整个列表是空的」和「当前筛选没有匹配项」：
                    // 否则在「待同步」「已排除」筛选下会误报「还没有活动列表」，
                    // 让用户以为拉取失败了。
                    val emptyTitle: String
                    val emptyMessage: String
                    when {
                        items.isEmpty() -> {
                            emptyTitle = "还没有活动列表"
                            emptyMessage =
                                "先点「拉取最新列表」，把 ${direction.source.displayName} 的活动拉回来。"
                        }
                        filter == PickerFilter.EXCLUDED -> {
                            emptyTitle = "没有被排除的活动"
                            emptyMessage =
                                "在「待同步」或「全部」里选中活动后点「批量排除」，它们会出现在这里"
                        }
                        else -> {
                            emptyTitle = "当前没有待同步的活动"
                            emptyMessage = "切到「全部」可以查看已同步和已排除的活动"
                        }
                    }
                    EmptyState(
                        illustration = R.drawable.ic_empty_activities,
                        title = emptyTitle,
                        message = emptyMessage,
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
                onClick = { viewModel.startExport(syncableSelected) },
                enabled = syncableSelected.isNotEmpty() && !loading,
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
                onClick = { viewModel.startSync(selectedIds = syncableSelected) },
                enabled = syncableSelected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .weight(1.4f)
                    .height(50.dp),
            ) {
                Text("同步 ${syncableSelected.size} 条")
            }
        }
    }

    // ---- 批量操作确认 ------------------------------------------------------
    // 提示语由 ViewModel 自己发（_message → Snackbar），这里不再重复弹一次。
    pendingBatch?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingBatch = null },
            title = {
                Text(
                    when (action) {
                        is BatchAction.Exclude -> "批量排除 ${action.ids.size} 条活动？"
                        is BatchAction.Restore -> "恢复 ${action.ids.size} 条活动？"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        is BatchAction.Exclude -> "这些活动之后不会被同步，可以在「已排除」里随时恢复。"
                        is BatchAction.Restore -> "这些活动会重新出现在待同步列表里。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            is BatchAction.Exclude -> viewModel.excludeMany(direction, action.ids)
                            is BatchAction.Restore -> viewModel.unexcludeMany(direction, action.ids)
                        }
                        // 操作完把这一批从勾选里去掉：被排除的会离开「待同步」视图，
                        // 恢复的会离开「已排除」视图，留着勾选只是脏状态。
                        selected = selected - action.ids.toSet()
                        pendingBatch = null
                    },
                    colors = if (action is BatchAction.Exclude) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(if (action is BatchAction.Exclude) "排除" else "恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatch = null }) { Text("取消") }
            },
        )
    }
}

/** 列表的筛选口径。 */
private enum class PickerFilter(val label: String) {
    /** 未同步、且没有被排除的。 */
    SYNCABLE("待同步"),

    /** 所有活动（含已同步、已排除）。 */
    ALL("全部"),

    /** 只看被排除的，用来批量恢复。 */
    EXCLUDED("已排除"),
}

/** 待确认的批量操作。 */
private sealed interface BatchAction {
    val ids: List<String>

    data class Exclude(override val ids: List<String>) : BatchAction

    data class Restore(override val ids: List<String>) : BatchAction
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
                    // 一律可勾：已排除的活动在「已排除」筛选下要能被挑出来批量恢复。
                    onCheckedChange = { onToggle() },
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
