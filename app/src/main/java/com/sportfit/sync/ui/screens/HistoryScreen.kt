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
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.EmptyHint
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone

@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    onOpenJob: (Long) -> Unit,
) {
    val jobs by viewModel.jobs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "同步记录",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()

        if (jobs.isEmpty()) {
            EmptyHint(Icons.Filled.History, "还没有同步记录，完成一次同步后会显示在这里")
            return@Column
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(jobs, key = { it.id }) { job ->
                JobRow(job = job, onClick = { onOpenJob(job.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun JobRow(job: SyncJobEntity, onClick: () -> Unit) {
    val source = Platform.fromKey(job.sourcePlatform)?.displayName ?: job.sourcePlatform
    val target = Platform.fromKey(job.targetPlatform)?.displayName ?: job.targetPlatform

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$source → $target", style = MaterialTheme.typography.titleMedium)
            Text(
                "${Format.dateTime(job.startedAt)} · 耗时 ${Format.elapsed(job.startedAt, job.finishedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                job.message ?: "成功 ${job.okCount} · 重复 ${job.dupCount} · 失败 ${job.failCount}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        StatusBadge(text = statusLabel(job.status), tone = statusTone(job.status))
    }
}

internal fun statusLabel(status: String): String = when (status) {
    SyncJobEntity.STATUS_SUCCESS -> "成功"
    SyncJobEntity.STATUS_PARTIAL -> "部分成功"
    SyncJobEntity.STATUS_RUNNING -> "进行中"
    SyncJobEntity.STATUS_CANCELLED -> "已取消"
    else -> "失败"
}

internal fun statusTone(status: String): StatusTone = when (status) {
    SyncJobEntity.STATUS_SUCCESS -> StatusTone.OK
    SyncJobEntity.STATUS_PARTIAL -> StatusTone.WARN
    SyncJobEntity.STATUS_RUNNING -> StatusTone.NEUTRAL
    else -> StatusTone.ERROR
}
