package com.sportfit.sync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.SectionCard

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onOpenAccounts: () -> Unit,
    onOpenCache: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val accounts by viewModel.accounts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        SectionCard(title = "账号") {
            SettingsEntry(
                icon = Icons.Filled.Person,
                title = "账号管理",
                subtitle = if (accounts.isEmpty()) {
                    "尚未配置任何平台账号"
                } else {
                    accounts.joinToString("、") {
                        com.sportfit.sync.domain.Platform.fromKey(it.platform)?.displayName ?: it.platform
                    }
                },
                onClick = onOpenAccounts,
            )
        }

        SectionCard(title = "后台同步") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("定时自动同步", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "默认关闭。开启后按下方间隔在后台运行默认方向，可能消耗流量。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.backgroundSyncEnabled,
                    onCheckedChange = {
                        viewModel.updateBackgroundSync(it, settings.backgroundIntervalHours)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("间隔", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 6, 12, 24).forEach { hours ->
                        Text(
                            "$hours 小时",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.backgroundIntervalHours == hours) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.clickable {
                                viewModel.updateBackgroundSync(settings.backgroundSyncEnabled, hours)
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Coros 同步前提醒", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Coros 为单设备登录，同步会挤掉手机 App",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.warnCorosSingleSession,
                    onCheckedChange = { enabled ->
                        viewModel.setWarnCoros(enabled)
                    },
                )
            }
        }

        SectionCard(title = "存储") {
            SettingsEntry(
                icon = Icons.Filled.Storage,
                title = "本地缓存",
                subtitle = "查看占用、分类清理、危险操作确认",
                onClick = onOpenCache,
            )
        }

        SectionCard(title = "同步行为") {
            Text(
                "每次同步最多处理 ${settings.listLimit} 条活动。账号的历史活动会在配置时全量存到本地，" +
                    "之后只做增量更新。可在「选择活动同步」页调整后再同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 200, 500, 1000).forEach { limit ->
                    Text(
                        "$limit",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.listLimit == limit) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.clickable {
                            viewModel.updateDefaults(
                                settings.defaultSource,
                                settings.defaultTarget,
                                limit,
                            )
                        },
                    )
                }
            }
        }

        SectionCard(title = "关于") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudSync, contentDescription = null)
                Text(
                    "  运动同步 · 把 Garmin / Coros / Strava / iGPSPORT / OneLap 的活动互相搬运",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "凭据使用 Android Keystore 加密保存在本机，不会上传到任何服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
        HorizontalDivider()
    }
}
