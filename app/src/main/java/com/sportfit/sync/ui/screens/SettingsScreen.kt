package com.sportfit.sync.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sportfit.sync.data.local.AccountEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.PlatformAvatar
import com.sportfit.sync.ui.components.ScreenHeader
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SoftChip
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import kotlin.math.abs
import kotlin.math.roundToInt

/** 处理条数只有这几档，用滑杆在档位之间切换。 */
private val LIST_LIMIT_OPTIONS = listOf(100, 200, 500, 1000)

/** 后台同步间隔（小时）只有这几档。 */
private val INTERVAL_OPTIONS = listOf(1, 3, 6, 12, 24)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onOpenAccounts: () -> Unit,
    onOpenCache: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val activityCounts by viewModel.activityCounts.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("设置", icon = Icons.Filled.Settings)

        SectionCard(
            title = "账号",
            trailing = { SoftChip("${accounts.size} 个已配置") },
        ) {
            Platform.accountPlatforms.forEach { platform ->
                AccountRow(
                    platform = platform,
                    account = accounts.firstOrNull { it.platform == platform.key },
                    activityCount = activityCounts[platform.key] ?: 0,
                    onClick = onOpenAccounts,
                    viewModel = viewModel,
                )
                if (platform != Platform.accountPlatforms.last()) {
                    HairLine()
                }
            }
            Text(
                "点按任意平台可进入账号管理，添加或修改凭据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "后台同步") {
            SwitchRow(
                title = "定时自动同步",
                subtitle = "默认关闭。开启后按下方间隔在后台运行默认方向，可能消耗流量。",
                checked = settings.backgroundSyncEnabled,
                onCheckedChange = {
                    viewModel.updateBackgroundSync(it, settings.backgroundIntervalHours)
                },
            )
            HairLine()
            // 当前值由下面那排 chip 自己表达，这里不再重复一个「N 小时」标签。
            Text("间隔", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVAL_OPTIONS.forEach { hours ->
                    FilterChip(
                        selected = settings.backgroundIntervalHours == hours,
                        onClick = {
                            viewModel.updateBackgroundSync(settings.backgroundSyncEnabled, hours)
                        },
                        label = { Text("$hours 小时") },
                    )
                }
            }
            HairLine()
            SwitchRow(
                title = "Coros 同步前提醒",
                subtitle = "Coros 为单设备登录，同步会挤掉手机 App",
                checked = settings.warnCorosSingleSession,
                onCheckedChange = { enabled ->
                    viewModel.setWarnCoros(enabled)
                },
            )
        }

        SectionCard(title = "同步行为") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("每次同步处理条数", style = MaterialTheme.typography.bodyMedium)
                SoftChip("${settings.listLimit} 条")
            }
            ListLimitSlider(
                current = settings.listLimit,
                onSelect = { limit ->
                    viewModel.updateDefaults(
                        settings.defaultSource,
                        settings.defaultTarget,
                        limit,
                    )
                },
            )
            InfoBar(
                "每次同步最多处理 ${settings.listLimit} 条活动。账号的历史活动会在配置时全量存到本地，" +
                    "之后只做增量更新。可在「选择活动同步」页调整后再同步。",
            )
        }

        SectionCard(title = "存储") {
            SettingsEntry(
                icon = Icons.Filled.Storage,
                title = "本地缓存",
                subtitle = "查看占用、分类清理、危险操作确认",
                onClick = onOpenCache,
            )
        }

        SectionCard(title = "危险操作", icon = Icons.Filled.WarningAmber) {
            Text(
                "账本与凭据不属于普通缓存：清理账本会让所有活动重新变成未同步，" +
                    "清理凭据要到「账号管理」逐个删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = onOpenCache,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text("前往缓存与存储")
            }
            Text(
                "危险项需要在缓存页二次确认后才会清理。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "关于") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "运动同步 · 把 Garmin / Coros / Strava / iGPSPORT / OneLap 的活动互相搬运",
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

/**
 * 账号分组里的一行：平台头像 + 名称 + 状态 + 本地活动条数。
 *
 * 整行可点，进账号管理页。
 *
 * 状态徽章是**右对齐的固定列**：它跟在有弹性的文字列后面，右侧边缘统一落在内容区右缘，
 * 平台名长短不会把它顶来顶去（否则五行的徽章起点能差出 100 多像素，看着像在抖）。
 */
@Composable
private fun AccountRow(
    platform: Platform,
    account: AccountEntity?,
    activityCount: Int,
    onClick: () -> Unit,
    viewModel: AppViewModel,
) {
    // 邮箱是加密存的，展示时用仓库给出的脱敏形式。
    val email by produceState<String?>(initialValue = null, key1 = account) {
        value = account?.let { viewModel.maskedEmail(it) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        // 顶对齐：徽章与平台名同在第一行，几行的徽章连成一条整齐的横线。
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PlatformAvatar(platform = platform, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                accountDetailText(account, email),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 只有真的存了活动才多这一行，没有数据时整行就两行，节奏一致。
            if (activityCount > 0) {
                Text(
                    "本地已存 $activityCount 条活动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusBadge(accountBadgeText(account), accountBadgeTone(account))
    }
}

private fun accountBadgeText(account: AccountEntity?): String = when (account?.status) {
    AccountEntity.STATUS_CONNECTED -> "已连接"
    AccountEntity.STATUS_ERROR -> "异常"
    null -> "未配置"
    else -> "待验证"
}

private fun accountBadgeTone(account: AccountEntity?): StatusTone = when (account?.status) {
    AccountEntity.STATUS_CONNECTED -> StatusTone.OK
    AccountEntity.STATUS_ERROR -> StatusTone.ERROR
    null -> StatusTone.NEUTRAL
    else -> StatusTone.WARN
}

/**
 * 徽章下方的详情行。
 *
 * 徽章已经写了状态，详情行就**不再重复同一句话**：
 * 有邮箱显示邮箱，否则给一句有信息量的补充说明。
 */
private fun accountDetailText(account: AccountEntity?, email: String?): String {
    if (!email.isNullOrBlank() && email != "未配置") return email
    return when (account?.status) {
        AccountEntity.STATUS_CONNECTED -> "上次验证 ${Format.dateTime(account.lastVerifiedAt)}"
        AccountEntity.STATUS_ERROR -> account.lastError ?: "登录失败，请重新登录"
        null -> "尚未添加凭据"
        else -> "尚未完成登录验证"
    }
}

/**
 * 处理条数的滑杆。
 *
 * 仍然只有 100 / 200 / 500 / 1000 四档 —— 滑杆走的是档位，不是连续值，
 * 落到哪一档就调用一次与原来点数字完全相同的 ViewModel 方法。
 */
@Composable
private fun ListLimitSlider(current: Int, onSelect: (Int) -> Unit) {
    val currentIndex = LIST_LIMIT_OPTIONS.indexOf(current).takeIf { it >= 0 }
        ?: LIST_LIMIT_OPTIONS.indices.minByOrNull { abs(LIST_LIMIT_OPTIONS[it] - current) }
        ?: 0

    Slider(
        value = currentIndex.toFloat(),
        onValueChange = { raw ->
            val snapped = raw.roundToInt().coerceIn(0, LIST_LIMIT_OPTIONS.lastIndex)
            if (snapped != currentIndex) {
                onSelect(LIST_LIMIT_OPTIONS[snapped])
            }
        },
        valueRange = 0f..LIST_LIMIT_OPTIONS.lastIndex.toFloat(),
        steps = LIST_LIMIT_OPTIONS.lastIndex - 1,
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        LIST_LIMIT_OPTIONS.forEach { limit ->
            Text(
                "$limit",
                style = MaterialTheme.typography.labelSmall,
                color = if (current == limit) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 开关行：左边标题 + 说明，右边开关。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 浅色提示条：图标 + 说明文字。 */
@Composable
private fun InfoBar(text: String, icon: ImageVector = Icons.Filled.Info) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 可点的条目行：图标底座 + 标题 + 说明 + 箭头。 */
@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
