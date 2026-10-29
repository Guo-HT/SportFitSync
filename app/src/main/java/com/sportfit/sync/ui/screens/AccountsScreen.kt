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
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sportfit.sync.R
import com.sportfit.sync.data.local.AccountEntity
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.Format
import com.sportfit.sync.ui.components.EmptyState
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.PlatformAvatar
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SoftChip
import com.sportfit.sync.ui.components.StatusBadge
import com.sportfit.sync.ui.components.StatusTone
import com.sportfit.sync.ui.components.SubPageHeader

/**
 * 账号管理页。
 *
 * 每个平台一张卡：平台徽章 + 账号信息 + 状态徽章，下面是操作按钮。
 * 凭据只在本机解密（Keystore），卡片上显示的是脱敏后的邮箱。
 */
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

    // 脱敏邮箱要先解密才能拿到，所以放在 state 里跟着账号列表一起刷新。
    val maskedEmails by produceState(initialValue = emptyMap<String, String>(), accounts) {
        value = accounts.associate { row -> row.platform to viewModel.maskedEmail(row) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统一用公共的子页面标题栏：返回箭头 + 标题的位置和其它子页面完全一致。
        SubPageHeader(
            title = "账号管理",
            onBack = onBack,
            subtitle = "凭据加密保存在本机",
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )

        HairLine()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        illustration = R.drawable.ic_empty_accounts,
                        title = "还没有配置账号",
                        message = "在下面选择平台开始添加；保存后会自动登录并拉取历史活动。",
                    )
                }
            }
            items(Platform.accountPlatforms, key = { it.key }) { platform ->
                val account = accounts.firstOrNull { it.platform == platform.key }
                AccountCard(
                    platform = platform,
                    account = account,
                    maskedEmail = maskedEmails[platform.key],
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
            // 与卡片里的文字同一条基准线：页边距 16dp + 卡片内边距 16dp = 32dp。
            modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        )
    }

    pendingDelete?.let { platform ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("删除 ${platform.displayName} 账号？") },
            text = { Text("删除后需要重新登录；已同步的去重记录会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAccount(platform)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 一个平台的账号卡。
 *
 * 上半部分是信息（点整块都能进编辑页），下半部分是操作按钮：
 * 左侧是危险的「删除」（错误色文字按钮），右侧是主操作「添加/修改」（实心按钮）
 * 与 Strava 的「授权」（描边按钮）。
 */
@Composable
private fun AccountCard(
    platform: Platform,
    account: AccountEntity?,
    maskedEmail: String?,
    activityCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAuthorize: () -> Unit,
) {
    val statusTone = when (account?.status) {
        AccountEntity.STATUS_CONNECTED -> StatusTone.OK
        AccountEntity.STATUS_ERROR -> StatusTone.ERROR
        // 未配置与待验证都还不是「已连接」，用中性色区分于成功/失败。
        else -> StatusTone.NEUTRAL
    }
    val statusLabel = when (account?.status) {
        AccountEntity.STATUS_CONNECTED -> "已连接"
        AccountEntity.STATUS_ERROR -> "异常"
        null -> "未配置"
        else -> "待验证"
    }
    val statusDetail = when (account?.status) {
        AccountEntity.STATUS_CONNECTED -> "已连接 · ${Format.dateTime(account.lastVerifiedAt)}"
        AccountEntity.STATUS_ERROR -> account.lastError ?: "登录失败"
        null -> "未配置"
        else -> "待登录验证"
    }

    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformAvatar(platform)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(statusLabel, statusTone)
                }
                Text(
                    statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (account?.status == AccountEntity.STATUS_ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (account != null && !maskedEmail.isNullOrBlank()) {
                    Text(
                        maskedEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            account?.region?.takeIf { it.isNotBlank() }?.let { region ->
                SoftChip(
                    text = "区域：${regionLabel(platform, region)}",
                    // 中性色更耐看：主色蓝配 12% 淡底在浅色主题下对比度偏紧。
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Filled.Public,
                )
            }
            if (activityCount > 0) {
                SoftChip(
                    text = "本地已存 $activityCount 条活动",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                )
            }
        }

        HairLine()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 危险操作靠左，主操作靠右，中间用弹性空隙撑开，
            // 按钮组才不会挤在卡片左边、右边空一大片。
            if (account != null) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            }
            Spacer(Modifier.weight(1f))
            // Strava needs a second step: credentials first, then OAuth consent.
            if (platform == Platform.STRAVA &&
                account != null &&
                account.status != AccountEntity.STATUS_CONNECTED
            ) {
                OutlinedButton(onClick = onAuthorize) { Text("授权") }
            }
            Button(onClick = onEdit) {
                Icon(
                    if (account == null) Icons.Filled.Add else Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (account == null) "添加" else "修改")
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
