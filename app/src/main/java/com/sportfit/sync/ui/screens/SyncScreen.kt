package com.sportfit.sync.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportfit.sync.BuildConfig
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.InfoRow
import com.sportfit.sync.ui.components.PlatformChip
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone

/**
 * Home tab.
 *
 * The interaction contract from the design review: pick a direction, then either
 * go straight through ("一键同步全部") or preview and cherry-pick first.
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
    val selectablePlatforms = Platform.pickable(includeDemo = BuildConfig.DEBUG)
    val canSync = sourceReady && targetReady && !runState.running

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("运动数据同步", style = MaterialTheme.typography.headlineSmall)

        if (!sourceReady || !targetReady) {
            SectionCard(title = "开始前需要配置账号") {
                Text(
                    if (!sourceReady) "尚未配置 ${source.displayName} 账号" else "尚未配置 ${target.displayName} 账号",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 直达账号管理页：用户点这里的意图就是「去配账号」，
                // 中间再插一层设置页只会把路径拉长。
                OutlinedButton(onClick = onOpenAccounts) { Text("去添加账号") }
            }
        }

        // ---- direction picker -------------------------------------------
        SectionCard(title = "同步方向") {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { viewModel.swapDirection() }) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = "交换方向")
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

            InfoRow("方向", "${source.displayName} → ${target.displayName}")
            InfoRow(
                "活动状态",
                if (summary.cached == 0) {
                    "尚未拉取列表"
                } else {
                    "本地共 ${summary.cached} 条 · 待同步 ${summary.pending} · " +
                        "已同步 ${summary.synced} · 失败 ${summary.failed}"
                },
            )
        }

        // ---- saved strategies --------------------------------------------
        SectionCard(title = "常用策略") {
            if (strategies.isEmpty()) {
                Text(
                    "把常用的方向保存为策略，之后可以一键切换（例如「Garmin → Coros」）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            OutlinedButton(onClick = { viewModel.saveCurrentAsStrategy() }) {
                Text("保存当前方向为策略")
            }
        }

        // ---- actions -----------------------------------------------------
        Button(
            onClick = { startWithWarning { viewModel.startSync() } },
            enabled = canSync,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text("  一键同步全部", style = MaterialTheme.typography.titleMedium)
        }

        OutlinedButton(
            // Always reachable: cached rows are viewable even before the
            // accounts are configured, and the page explains what is missing.
            onClick = { startWithWarning(onSelectActivities) },
            enabled = !runState.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Checklist, contentDescription = null)
            Text("  选择活动同步")
        }

        // ---- live progress ------------------------------------------------
        if (runState.running) {
            SectionCard(title = "同步中") {
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

        // ---- result -------------------------------------------------------
        runState.error?.let { error ->
            SectionCard(title = "同步未开始") {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { viewModel.clearRunState() }) { Text("知道了") }
            }
        }

        runState.summary?.let { result ->
            val isExport = result.direction.target == Platform.LOCAL_FILES
            SectionCard(title = if (isExport) "导出结果" else "本次结果") {
                InfoRow(if (isExport) "已导出文件" else "成功", "${result.succeeded}")
                if (!isExport) InfoRow("重复", "${result.duplicated}")
                InfoRow("失败", "${result.failed}")
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

        // ---- latest history ----------------------------------------------
        jobs.firstOrNull()?.let { job ->
            SectionCard(title = "最近一次同步") {
                InfoRow(
                    job.sourcePlatform.let { Platform.fromKey(it)?.displayName ?: it } +
                        " → " +
                        job.targetPlatform.let { Platform.fromKey(it)?.displayName ?: it },
                    Format.dateTime(job.startedAt),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = when (job.status) {
                            "SUCCESS" -> "成功"
                            "PARTIAL" -> "部分成功"
                            "RUNNING" -> "进行中"
                            "CANCELLED" -> "已取消"
                            else -> "失败"
                        },
                        tone = when (job.status) {
                            "SUCCESS" -> StatusTone.OK
                            "PARTIAL" -> StatusTone.WARN
                            "RUNNING" -> StatusTone.NEUTRAL
                            else -> StatusTone.ERROR
                        },
                    )
                    Text(
                        job.message ?: "${job.okCount} 成功 / ${job.failCount} 失败",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onOpenJob(job.id) }) { Text("查看详情") }
            }
        }

        if (direction.source == Platform.COROS || direction.target == Platform.COROS) {
            Text(
                "提示：Coros 为单设备登录，同步期间请不要打开 Coros App 或网页，否则会中断同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

