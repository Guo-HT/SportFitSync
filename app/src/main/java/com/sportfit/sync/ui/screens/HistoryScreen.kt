package com.sportfit.sync.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sportfit.sync.R
import com.sportfit.sync.data.local.SyncJobEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.BorderedSurface
import com.sportfit.sync.ui.components.EmptyState
import com.sportfit.sync.ui.components.PlatformAvatar
import com.sportfit.sync.ui.components.ScreenHeader
import com.sportfit.sync.ui.components.SoftChip
import com.sportfit.sync.ui.components.StatRow
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import com.sportfit.sync.ui.components.jobStatusLabel
import com.sportfit.sync.ui.components.jobStatusTone

/**
 * 同步记录（历史）页。
 *
 * 每条记录一张卡：方向（源 → 目标）、时间与耗时、成功/重复/失败三个计数。
 * 整张卡可点，点开就是这次同步的详情页。
 */
@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    onOpenJob: (Long) -> Unit,
) {
    val jobs by viewModel.jobs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "同步记录",
            subtitle = "每一次同步的方向、时间与结果",
            icon = Icons.Filled.History,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        )

        if (jobs.isEmpty()) {
            EmptyState(
                illustration = R.drawable.ic_empty_history,
                title = "还没有同步记录",
                message = "完成一次同步后会显示在这里",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(jobs, key = { it.id }) { job ->
                JobCard(job = job, onClick = { onOpenJob(job.id) })
            }
        }
    }
}

/** 一条同步记录。整卡可点。 */
@Composable
private fun JobCard(job: SyncJobEntity, onClick: () -> Unit) {
    BorderedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 第一行：方向 + 状态 ------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 方向占掉除状态标签外的全部宽度，平台名放不下时省略而不是把标签挤出屏幕。
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

            // ---- 第二行：开始时间 + 耗时 --------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${Format.dateTime(job.startedAt)} · " +
                        "耗时 ${Format.elapsed(job.startedAt, job.finishedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- 第三行：成功 / 重复 / 失败 -----------------------------------
            StatRow(
                tiles = listOf(
                    "成功" to job.okCount.toString(),
                    "重复" to job.dupCount.toString(),
                    "失败" to job.failCount.toString(),
                ),
                // 三个数字跟这次同步的整体状态一个颜色：一眼扫过去就知道哪次有问题。
                tone = jobStatusTone(job.status),
            )

            // message 里如果只是三个计数的重复摘要，就不再重复一遍；
            // 「已取消」「已导出 3 个文件」这类信息才值得单独一行。
            job.extraNote()?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 平台徽章 + 名称。
 *
 * 平台认不出来（历史里存了已下线的平台 key）时退化成普通标签，保证信息不丢。
 * 名称过长时省略号收尾 —— 徽章大小固定，宽度不够就压缩名字。
 */
@Composable
internal fun PlatformIdentity(
    platform: Platform?,
    rawKey: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (platform != null) {
            PlatformAvatar(platform = platform, size = 26.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = platform.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            SoftChip(
                text = rawKey,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 这条记录还有没有「值得单独说一句」的结果说明。
 *
 * 计数已经用三张小卡展示过了，message 若是「成功 1，重复 0，失败 0」这种摘要就跳过，
 * 免得同一句话说两遍；其它情况（「已取消」「已导出 3 个文件」、中断原因）照常显示。
 */
internal fun SyncJobEntity.extraNote(): String? {
    val note = message?.trim().orEmpty()
    if (note.isEmpty()) return null
    val canonical = "成功 $okCount，重复 $dupCount，失败 $failCount"
    return if (note == canonical) null else note
}


