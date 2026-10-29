package com.sportfit.sync.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sportfit.sync.BuildConfig
import com.sportfit.sync.R
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.EmptyState
import com.sportfit.sync.ui.components.HeroCard
import com.sportfit.sync.ui.components.InfoRow
import com.sportfit.sync.ui.components.PlatformChip
import com.sportfit.sync.ui.components.ScreenHeader
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SoftChip
import com.sportfit.sync.ui.components.StatTile
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import com.sportfit.sync.ui.components.jobStatusLabel
import com.sportfit.sync.ui.components.jobStatusTone
import com.sportfit.sync.ui.theme.LocalHeroPalette

/**
 * Home tab.
 *
 * The interaction contract from the design review: pick a direction, then either
 * go straight through ("一键同步全部") or preview and cherry-pick first.
 *
 * 版式分三层：**头图卡**回答「现在是什么方向、现在能做什么」，
 * **统计小卡**回答「有多少东西要搬」，再往下的卡片才放配置和细节操作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(
    viewModel: AppViewModel,
    onSelectActivities: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenJob: (Long) -> Unit,
) {
    val source by viewModel.source.collectAsState()
    val target by viewModel.target.collectAsState()
    val runState by viewModel.runState.collectAsState()
    val summary by viewModel.activitySummary.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val strategies by viewModel.strategies.collectAsState()

    val direction = viewModel.currentDirection()
    // Rehearsal mode needs no account; every other platform does.
    val sourceReady = source == Platform.DEMO || accounts.any { it.platform == source.key }
    val targetReady = target == Platform.DEMO || accounts.any { it.platform == target.key }
    val accountsReady = sourceReady && targetReady
    val selectablePlatforms = Platform.pickable(includeDemo = BuildConfig.DEBUG)
    val canSync = accountsReady && !runState.running

    var showCorosWarning by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    val context = LocalContext.current
    // The sync runs in a foreground service so it survives the app going to
    // the background; on API 33+ that needs the runtime notification grant.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The run proceeds either way; only the progress is hidden. */ }

    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startWithWarning(action: () -> Unit) {
        ensureNotificationPermission()
        val involvesCoros = source == Platform.COROS || target == Platform.COROS
        if (involvesCoros && settings.warnCorosSingleSession) {
            pendingStart = action
            showCorosWarning = true
        } else {
            action()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部多留一截：否则最后一行 chip 正好卡在视口底边上，会被切掉半截。
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "运动数据同步",
            icon = Icons.Filled.Sync,
            subtitle = "把运动数据从一个平台搬到另一个平台",
        )

        // ---- hero：方向 + 主行动 -------------------------------------------
        HeroCard(
            title = direction.label,
            subtitle = if (accountsReady) {
                "把 ${source.displayName} 的活动同步到 ${target.displayName}"
            } else {
                "先去配置账号"
            },
            trailing = {
                // 按钮压在深色头图上，所以用头图自己的浅色做底、深色做字：
                // 两套主题下都跳得出来，也不会跟卡片糊在一起。
                val hero = LocalHeroPalette.current
                Button(
                    onClick = { startWithWarning { viewModel.startSync() } },
                    enabled = canSync,
                    // 撑满标题列：按钮和上面的标题共用一条右边缘，
                    // 看起来是一整列，而不是浮在卡片中间的一块。
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = hero.onHero,
                        contentColor = hero.start,
                        // 禁用态单独给色：M3 默认是深色字压深色底，等于看不见；
                        // 纯白底 + 淡色字又变成一块半透明的灰。
                        // 所以这里用「半透的白底 + 深色字」：按下去会亮，但现在不亮。
                        disabledContainerColor = hero.onHero.copy(alpha = 0.6f),
                        disabledContentColor = hero.start,
                    ),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("一键同步全部", style = MaterialTheme.typography.titleMedium)
                }
            },
        )

        // 没配好账号就没法同步，提示和入口紧挨着头图，最先被看到。
        if (!accountsReady) {
            SectionCard(
                title = "开始前需要配置账号",
                icon = Icons.Filled.AccountCircle,
            ) {
                Text(
                    if (!sourceReady) {
                        "尚未配置 ${source.displayName} 账号"
                    } else {
                        "尚未配置 ${target.displayName} 账号"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenAccounts,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = null)
                    Text("去添加账号")
                }
            }
        }

        OutlinedButton(
            // Always reachable: cached rows are viewable even before the
            // accounts are configured, and the page explains what is missing.
            onClick = { startWithWarning(onSelectActivities) },
            enabled = !runState.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Checklist, contentDescription = null)
            Text("选择活动同步")
        }

        // ---- 统计：有多少东西要搬 -------------------------------------------
        SectionCard(title = "活动状态", icon = Icons.Filled.Insights) {
            if (summary.cached == 0) {
                EmptyState(
                    illustration = R.drawable.ic_empty_activities,
                    title = "还没有活动列表",
                    message = "配置好账号并拉取历史活动后，这里会显示待同步的数量。",
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile("本地共", "${summary.cached}", Modifier.weight(1f))
                    StatTile("待同步", "${summary.pending}", Modifier.weight(1f))
                    StatTile(
                        label = "已同步",
                        value = "${summary.synced}",
                        modifier = Modifier.weight(1f),
                        tone = if (summary.synced > 0) StatusTone.OK else StatusTone.NEUTRAL,
                    )
                    StatTile(
                        label = "失败",
                        value = "${summary.failed}",
                        modifier = Modifier.weight(1f),
                        tone = if (summary.failed > 0) StatusTone.ERROR else StatusTone.NEUTRAL,
                    )
                }
            }
        }

        // ---- 方向选择 -------------------------------------------------------
        SectionCard(title = "同步方向", icon = Icons.AutoMirrored.Filled.CompareArrows) {
            Text(
                "源平台（读取活动）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selectablePlatforms.filter { it.canBeSource }.forEach { platform ->
                    PlatformChip(
                        platform = platform,
                        selected = source == platform,
                        onClick = { viewModel.setSource(platform) },
                    )
                }
            }
            // 交换按钮套一个圆形底座：比裸图标更像一个「把手」。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { viewModel.swapDirection() }) {
                        Icon(
                            Icons.Filled.SwapHoriz,
                            contentDescription = "交换方向",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            Text(
                "目标平台（写入活动）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                selectablePlatforms.filter { it.canBeTarget }.forEach { platform ->
                    PlatformChip(
                        platform = platform,
                        selected = target == platform,
                        onClick = { viewModel.setTarget(platform) },
                    )
                }
            }
            SoftChip(text = direction.label, icon = Icons.AutoMirrored.Filled.ArrowForward)
        }

        // ---- 常用策略 -------------------------------------------------------
        SectionCard(title = "常用策略", icon = Icons.Filled.Bookmark) {
            if (strategies.isEmpty()) {
                EmptyState(
                    illustration = R.drawable.ic_empty_history,
                    title = "还没有常用策略",
                    message = "把常用的方向保存为策略，之后可以一键切换（例如「Garmin → Coros」）。",
                )
            } else {
                strategies.forEach { strategy ->
                    val strategySource = Platform.fromKey(strategy.sourcePlatform)
                    val strategyTarget = Platform.fromKey(strategy.targetPlatform)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.applyStrategy(strategy) },
                        ) {
                            Text(strategy.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(strategySource?.displayName ?: strategy.sourcePlatform)
                                    append(" → ")
                                    append(strategyTarget?.displayName ?: strategy.targetPlatform)
                                    if (strategy.useForBackground) append(" · 后台同步")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                viewModel.setStrategyBackground(
                                    strategy.id,
                                    !strategy.useForBackground,
                                )
                            },
                        ) {
                            Text(
                                if (strategy.useForBackground) "取消后台" else "设为后台",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { viewModel.deleteStrategy(strategy.id) }) {
                            Text("删除", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            // Saving a strategy only records a direction; it must not require
            // the accounts to be configured yet.
            OutlinedButton(
                onClick = { viewModel.saveCurrentAsStrategy() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存当前方向为策略")
            }
        }

        // ---- 同步中 ---------------------------------------------------------
        if (runState.running) {
            SectionCard(title = "同步中", icon = Icons.Filled.CloudSync) {
                LinearProgressIndicator(
                    progress = { runState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                InfoRow("进度", "${runState.index} / ${runState.total}")
                runState.currentName?.let { InfoRow("当前", it) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { viewModel.cancelSync() }) { Text("取消同步") }
                }
            }
        }

        runState.error?.let { error ->
            SectionCard(title = "同步未开始", icon = Icons.Filled.ErrorOutline) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { viewModel.clearRunState() }) { Text("知道了") }
                }
            }
        }

        // ---- 本次结果 -------------------------------------------------------
        runState.summary?.let { result ->
            val isExport = result.direction.target == Platform.LOCAL_FILES
            SectionCard(
                title = if (isExport) "导出结果" else "本次结果",
                icon = Icons.Filled.TaskAlt,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "成功",
                        value = "${result.succeeded}",
                        modifier = Modifier.weight(1f),
                        tone = if (result.succeeded > 0) StatusTone.OK else StatusTone.NEUTRAL,
                    )
                    StatTile("重复", "${result.duplicated}", Modifier.weight(1f))
                    StatTile(
                        label = "失败",
                        value = "${result.failed}",
                        modifier = Modifier.weight(1f),
                        tone = if (result.failed > 0) StatusTone.ERROR else StatusTone.NEUTRAL,
                    )
                }
                if (isExport) {
                    Text(
                        "文件保存在「下载 / SportFitSync」目录，可直接上传到任意平台。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.failed > 0) {
                        Button(onClick = { viewModel.retryFailed() }) { Text("重试失败项") }
                    }
                    OutlinedButton(onClick = { onOpenJob(result.jobId) }) { Text("查看日志") }
                }
            }
        }

        // ---- 最近一次同步 ---------------------------------------------------
        jobs.firstOrNull()?.let { job ->
            SectionCard(title = "最近一次同步", icon = Icons.Filled.History) {
                InfoRow(
                    job.sourcePlatform.let { Platform.fromKey(it)?.displayName ?: it } +
                        " → " +
                        job.targetPlatform.let { Platform.fromKey(it)?.displayName ?: it },
                    Format.dateTime(job.startedAt),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        text = jobStatusLabel(job.status),
                        tone = jobStatusTone(job.status),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        job.message ?: "成功 ${job.okCount} · 失败 ${job.failCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onOpenJob(job.id) }) { Text("查看详情") }
                }
            }
        }

        // Coros 是单设备登录：把这件事说清楚，比让同步莫名其妙中断强。
        if (direction.source == Platform.COROS || direction.target == Platform.COROS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Coros 为单设备登录，同步期间请不要打开 Coros App 或网页，否则会中断同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }

    if (showCorosWarning) {
        AlertDialog(
            onDismissRequest = { showCorosWarning = false; pendingStart = null },
            title = { Text("Coros 单设备登录提醒") },
            text = {
                Text(
                    "同步会占用 Coros 的登录会话，期间手机上打开的 Coros App 会被挤下线。\n\n" +
                        "建议同步前先关闭 Coros App，同步完成后再重新登录。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCorosWarning = false
                    val action = pendingStart
                    pendingStart = null
                    action?.invoke()
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { showCorosWarning = false; pendingStart = null }) { Text("取消") }
            },
        )
    }
}
