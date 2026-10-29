package com.sportfit.sync.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.InfoRow
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.StatusBadge

/** Full log for one sync run, streamed from the database. */
@Composable
fun JobDetailScreen(
    viewModel: AppViewModel,
    jobId: Long,
    onBack: () -> Unit,
) {
    val job by viewModel.job(jobId).collectAsState(initial = null)
    val logs by viewModel.jobLogs(jobId).collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "同步详情",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).padding(16.dp),
        ) {
            item {
                job?.let { entity ->
                    SectionCard(title = "概要") {
                        InfoRow(
                            "方向",
                            (Platform.fromKey(entity.sourcePlatform)?.displayName ?: entity.sourcePlatform) +
                                " → " +
                                (Platform.fromKey(entity.targetPlatform)?.displayName ?: entity.targetPlatform),
                        )
                        InfoRow("状态", statusLabel(entity.status))
                        InfoRow("成功 / 重复 / 失败", "${entity.okCount} / ${entity.dupCount} / ${entity.failCount}")
                        InfoRow("开始", Format.dateTime(entity.startedAt))
                        InfoRow("耗时", Format.elapsed(entity.startedAt, entity.finishedAt))
                        entity.message?.let { InfoRow("结果", it) }
                        StatusBadge(statusLabel(entity.status), statusTone(entity.status))
                    }
                }
            }

            item {
                SectionCard(title = "日志（${logs.size} 条）") {
                    if (logs.isEmpty()) {
                        Text("没有日志", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            items(logs, key = { it.id }) { line ->
                Text(
                    text = "[${Format.time(line.ts)}] ${line.message}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (line.level) {
                        "ERROR" -> MaterialTheme.colorScheme.error
                        "WARN" -> MaterialTheme.colorScheme.tertiary
                        "SUCCESS" -> MaterialTheme.colorScheme.secondary
                        else -> Color.Unspecified
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
