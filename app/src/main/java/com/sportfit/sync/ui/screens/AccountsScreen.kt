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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.sportfit.sync.data.local.AccountEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone

@Composable
fun AccountsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onEdit: (Platform) -> Unit,
    onAuthorizeStrava: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsState()
    val activityCounts by viewModel.activityCounts.collectAsState()
    var pendingDelete by remember { mutableStateOf<Platform?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "账号管理",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f).padding(16.dp)) {
            items(Platform.accountPlatforms, key = { it.key }) { platform ->
                val account = accounts.firstOrNull { it.platform == platform.key }
                AccountRow(
                    platform = platform,
                    account = account,
                    activityCount = activityCounts[platform.key] ?: 0,
                    onEdit = { onEdit(platform) },
                    onDelete = { pendingDelete = platform },
                    onAuthorize = onAuthorizeStrava,
                )
            }
        }

        Text(
            "凭据使用 Android Keystore 加密存储在本机；导出/备份不会包含账号信息。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    pendingDelete?.let { platform ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${platform.displayName} 账号？") },
            text = { Text("删除后需要重新登录；已同步的去重记录会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAccount(platform)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AccountRow(
    platform: Platform,
    account: AccountEntity?,
    activityCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAuthorize: () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (account?.status) {
                        AccountEntity.STATUS_CONNECTED ->
                            "已连接 · ${Format.dateTime(account.lastVerifiedAt)}"
                        AccountEntity.STATUS_ERROR -> account.lastError ?: "登录失败"
                        null -> "未配置"
                        else -> "待登录验证"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                account?.region?.takeIf { it.isNotBlank() }?.let { region ->
                    Text(
                        "区域：${regionLabel(platform, region)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (activityCount > 0) {
                    Text(
                        "本地已存 $activityCount 条活动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (account?.status) {
                AccountEntity.STATUS_CONNECTED -> StatusBadge("已连接", StatusTone.OK)
                AccountEntity.STATUS_ERROR -> StatusBadge("异常", StatusTone.ERROR)
                null -> StatusBadge("未配置", StatusTone.NEUTRAL)
                else -> StatusBadge("待验证", StatusTone.WARN)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit) { Text(if (account == null) "添加" else "修改") }
            // Strava needs a second step: credentials first, then OAuth consent.
            if (platform == Platform.STRAVA &&
                account != null &&
                account.status != AccountEntity.STATUS_CONNECTED
            ) {
                TextButton(onClick = onAuthorize) { Text("授权") }
            }
            if (account != null) {
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

internal fun regionLabel(platform: Platform, region: String): String = when (platform) {
    Platform.GARMIN -> if (region.equals("CN", true)) "中国区 (garmin.cn)" else "国际区 (garmin.com)"
    Platform.COROS -> when (region) {
        "1" -> "国际区"
        "3" -> "欧洲区"
        else -> "中国区"
    }
    Platform.IGPSPORT -> if (region == "global") "国际站" else "中国站"
    else -> region
}
