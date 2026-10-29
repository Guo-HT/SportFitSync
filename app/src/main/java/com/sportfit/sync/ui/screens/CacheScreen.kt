package com.sportfit.sync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.sportfit.sync.cache.CacheCategory
import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.InfoRow
import com.sportfit.sync.ui.components.SectionCard

/**
 * Local cache screen (the third explicit requirement).
 *
 * Safe categories are clearable in one tap; the de-duplication ledger is
 * isolated in a clearly-marked danger zone with a confirmation dialog, because
 * wiping it makes every activity look unsynced again.
 */
@Composable
fun CacheScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val snapshot by viewModel.cache.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var pendingDangerous by remember { mutableStateOf<CacheCategory?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "本地缓存",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "占用情况") {
                InfoRow("可安全清理", CacheManager.formatBytes(snapshot.safeBytes))
                InfoRow("全部占用", CacheManager.formatBytes(snapshot.totalBytes))
                Button(
                    onClick = { viewModel.clearAllSafeCache() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("一键清理安全项")
                }
                Text(
                    "安全项包含：临时 FIT/ZIP、活动列表缓存、同步日志、同步历史。" +
                        "清理后不影响已同步结果，下次同步会重新拉取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "分类清理") {
                snapshot.safeCategories.forEach { category ->
                    CategoryRow(category = category, onClear = { viewModel.clearCache(category.key) })
                    HorizontalDivider()
                }
            }

            SectionCard(title = "自动清理策略") {
                InfoRow(
                    "同步成功后删除中转文件",
                    if (settings.cachePolicy.deleteTempAfterSuccess) "已开启" else "已关闭",
                )
                InfoRow("已删除账号的活动列表保留", "${settings.cachePolicy.activityRetentionDays} 天")
                InfoRow("同步日志保留", "${settings.cachePolicy.logRetentionDays} 天")
                InfoRow("同步历史保留", "最近 ${settings.cachePolicy.jobHistoryLimit} 次")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.applyRetentionNow() }) {
                        Text("立即按策略清理")
                    }
                }
            }

            SectionCard(title = "危险操作") {
                Text(
                    "以下内容不属于缓存，清理后需要重新配置或会触发重复同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                snapshot.categories.filter { it.dangerous }.forEach { category ->
                    CategoryRow(
                        category = category,
                        onClear = { pendingDangerous = category },
                        danger = true,
                    )
                }
            }

            SectionCard(title = "账号与登录态") {
                Text(
                    "如需清除账号凭据，请到「账号管理」逐个删除；" +
                        "删除后 Keystore 中的密文将不可解密。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingDangerous?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDangerous = null },
            title = { Text("清理「${category.title}」？") },
            text = {
                Text(
                    "${category.description}\n\n" +
                        "清理后，所有活动都会被重新视为未同步，下次同步可能向目标平台重复上传" +
                        "（Garmin / Strava 会识别为重复活动，Coros 可能需要重新导入）。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCache(category.key, force = true)
                    pendingDangerous = null
                }) { Text("确认清理") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDangerous = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CategoryRow(
    category: CacheCategory,
    onClear: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(category.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${CacheManager.formatBytes(category.bytes)} · ${category.itemCount} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                category.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClear) {
            Text("清理", color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
