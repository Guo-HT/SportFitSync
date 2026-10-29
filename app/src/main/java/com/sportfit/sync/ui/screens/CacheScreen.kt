package com.sportfit.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sportfit.sync.cache.CacheCategory
import com.sportfit.sync.cache.CacheManager
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SubPageHeader
import com.sportfit.sync.ui.components.SoftChip
import com.sportfit.sync.ui.theme.NumericTextStyle

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
        // 标题栏统一用公共组件：返回箭头 + 标题的起点和其它子页面完全一致，
        // 翻页面时标题不再「跳」。
        SubPageHeader(
            title = "缓存与存储",
            onBack = onBack,
            subtitle = "占用、分类清理与危险操作",
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        HairLine()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "占用情况") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text(
                            CacheManager.formatBytes(snapshot.totalBytes),
                            // 两个数字用同一档字号 —— 一大一小并排会像两套排版。
                            style = NumericTextStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "全部占用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            CacheManager.formatBytes(snapshot.safeBytes),
                            style = NumericTextStyle,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "可安全清理",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                snapshot.categories.forEach { category ->
                    UsageBar(
                        category = category,
                        totalBytes = snapshot.totalBytes,
                    )
                }
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
                    if (category != snapshot.safeCategories.last()) {
                        HairLine()
                    }
                }
            }

            SectionCard(title = "自动清理策略") {
                PolicyRow(
                    label = "同步成功后删除中转文件",
                    value = if (settings.cachePolicy.deleteTempAfterSuccess) "已开启" else "已关闭",
                    enabled = settings.cachePolicy.deleteTempAfterSuccess,
                )
                PolicyRow(
                    label = "已删除账号的活动列表保留",
                    value = "${settings.cachePolicy.activityRetentionDays} 天",
                )
                PolicyRow(
                    label = "同步日志保留",
                    value = "${settings.cachePolicy.logRetentionDays} 天",
                )
                PolicyRow(
                    label = "同步历史保留",
                    value = "最近 ${settings.cachePolicy.jobHistoryLimit} 次",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.applyRetentionNow() }) {
                        Text("立即按策略清理")
                    }
                }
            }

            SectionCard(title = "危险操作", icon = Icons.Filled.WarningAmber) {
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

/** 某个分类占用的条形图：名称 + 大小 + 进度条。 */
@Composable
private fun UsageBar(category: CacheCategory, totalBytes: Long) {
    val ratio = if (totalBytes > 0L) {
        (category.bytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    // 数值与左侧标签同一档字号 —— 小两号会像脚注。
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                CacheManager.formatBytes(category.bytes),
                style = valueStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            // 主色但压一层透明度：100% 时也只是「浅轨道 + 一条柔和的主色」，
            // 而不是一整块高饱和蓝压在白卡片上。
            color = if (category.dangerous) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

/** 策略项：左边说明，右边一个数值标签。 */
@Composable
private fun PolicyRow(label: String, value: String, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SoftChip(
            text = value,
            color = if (enabled) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 分类行：图标 + 名称 + 条数/大小 + 清理按钮。 */
@Composable
private fun CategoryRow(
    category: CacheCategory,
    onClear: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (danger) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                categoryIcon(category.key),
                contentDescription = null,
                tint = if (danger) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp),
            )
        }
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
        TextButton(
            onClick = onClear,
            colors = if (danger) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
        ) {
            Text("清理")
        }
    }
}

/** 分类图标：按缓存分类的 key 取一个语义相符的图标。 */
private fun categoryIcon(key: String): ImageVector = when (key) {
    CacheManager.KEY_TEMP_FILES -> Icons.Filled.FolderZip
    CacheManager.KEY_ACTIVITIES -> Icons.Filled.FitnessCenter
    CacheManager.KEY_LOGS -> Icons.Filled.Description
    CacheManager.KEY_HISTORY -> Icons.Filled.History
    CacheManager.KEY_EXCLUSIONS -> Icons.Filled.Block
    CacheManager.KEY_SYNC_RECORDS -> Icons.AutoMirrored.Filled.FactCheck
    else -> Icons.Filled.Folder
}
