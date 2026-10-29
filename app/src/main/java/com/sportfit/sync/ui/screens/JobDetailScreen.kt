package com.sportfit.sync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.data.local.SyncLogEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.InfoRow
import com.sportfit.sync.ui.components.ScreenHeader
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.StatRow
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.jobStatusLabel
import com.sportfit.sync.ui.components.jobStatusTone

/** Full log for one sync run, streamed from the database. */
@Composable
fun JobDetailScreen(
    viewModel: AppViewModel,
    jobId: Long,
    onBack: () -> Unit,
) {
    val job by viewModel.job(jobId).collectAsState(initial = null)
    val logs by viewModel.jobLogs(jobId).collectAsState(initial = emptyList())
    val failures = remember(logs) { failureEntries(logs) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶部：返回 + 标题 ----------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            ScreenHeader(
                title = "同步详情",
                subtitle = job?.let { directionLabel(it) },
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                job?.let { SummaryCard(it) }
            }
            if (failures.isNotEmpty()) {
                item { FailureCard(failures) }
            }
            item { LogCard(logs) }
        }
    }
}

/** 「概要」：方向、状态、计数、时间。 */
@Composable
private fun SummaryCard(job: SyncJobEntity) {
    SectionCard(title = "概要") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformIdentity(
                    platform = Platform.fromKey(job.sourcePlatform),
                    rawKey = job.sourcePlatform,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "同步到",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                PlatformIdentity(
                    platform = Platform.fromKey(job.targetPlatform),
                    rawKey = job.targetPlatform,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusBadge(text = jobStatusLabel(job.status), tone = jobStatusTone(job.status))
        }

        StatRow(
            tiles = listOf(
                "成功" to job.okCount.toString(),
                "重复" to job.dupCount.toString(),
                "失败" to job.failCount.toString(),
            ),
            tone = jobStatusTone(job.status),
        )

        InfoRow("开始时间", Format.dateTime(job.startedAt))
        InfoRow("耗时", Format.elapsed(job.startedAt, job.finishedAt))
        job.message?.let { InfoRow("结果", it) }
    }
}

/**
 * 「失败」：这次同步没成的活动，一条一张错误色卡片。
 *
 * 失败信息来自日志里的 ERROR 行（活动名 + 原因），所以不需要动数据层。
 */
@Composable
private fun FailureCard(failures: List<FailureEntry>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "失败（${failures.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            failures.forEach { failure ->
                Column {
                    Text(
                        text = failure.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = failure.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/** 「日志」：等宽字体的代码块，一行一条。 */
@Composable
private fun LogCard(logs: List<SyncLogEntity>) {
    SectionCard(title = "日志（${logs.size} 条）") {
        if (logs.isEmpty()) {
            Text(
                text = "没有日志",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        LogBlock(logs)
    }
}

/**
 * 日志正文。
 *
 * 等宽字体 + 浅容器 + 细边框，看起来像一段代码而不是一段说明文字；
 * 时间戳用弱化色，消息按 level 上色。
 */
@Composable
private fun LogBlock(logs: List<SyncLogEntity>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            logs.forEach { line ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = Format.time(line.ts),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                    Text(
                        text = "  ${line.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = logLevelColor(line.level),
                    )
                }
            }
        }
    }
}

/** 日志级别 → 颜色：普通=弱化、成功=辅色、提醒=第三色、错误=错误色。 */
@Composable
private fun logLevelColor(level: String): Color = when (level) {
    "ERROR" -> MaterialTheme.colorScheme.error
    "WARN" -> MaterialTheme.colorScheme.tertiary
    "SUCCESS" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 方向文字，例如「Garmin → Coros」。 */
internal fun directionLabel(job: SyncJobEntity): String {
    val source = Platform.fromKey(job.sourcePlatform)?.displayName ?: job.sourcePlatform
    val target = Platform.fromKey(job.targetPlatform)?.displayName ?: job.targetPlatform
    return "$source → $target"
}

/** 一条失败：活动名 + 原因。 */
internal data class FailureEntry(val name: String, val reason: String)

/** 形如 `[3/12] 骑行活动 失败：网络超时` 的活动级失败。 */
private val ACTIVITY_FAILURE = Regex("""^\[\d+/\d+]\s*(.+?)\s*(?:失败|异常)：(.*)$""")

/** 形如 `同步中断：…` / `导出失败：…` 的任务级失败。 */
private val JOB_FAILURE = Regex("""^(.+?)(?:中断|失败)：(.*)$""")

/**
 * 从日志里挑出失败条目。
 *
 * 日志是这次同步唯一的失败记录来源（数据库里没有单独的失败表），
 * 所以失败列表按 ERROR 行的格式解析活动名与原因。
 */
internal fun failureEntries(logs: List<SyncLogEntity>): List<FailureEntry> =
    logs.filter { it.level == "ERROR" }.map { line ->
        val text = line.message
        ACTIVITY_FAILURE.find(text)?.let { match ->
            return@map FailureEntry(
                name = match.groupValues[1].trim(),
                reason = match.groupValues[2].trim(),
            )
        }
        JOB_FAILURE.find(text)?.let { match ->
            return@map FailureEntry(
                name = match.groupValues[1].trim(),
                reason = match.groupValues[2].trim(),
            )
        }
        FailureEntry(name = "同步失败", reason = text)
    }
