package com.sportfit.sync.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.BorderedSurface
import com.sportfit.sync.ui.components.HairLine
import com.sportfit.sync.ui.components.SecretField
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SubPageHeader

/**
 * Add or edit one platform account.
 *
 * Secrets are never echoed back: leaving the password field empty keeps the
 * stored value, which is why the field is labelled accordingly when editing.
 */
@Composable
fun AccountEditScreen(
    viewModel: AppViewModel,
    platform: Platform,
    onBack: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(defaultRegion(platform)) }
    var isExisting by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(platform) {
        val existing = viewModel.account(platform)
        isExisting = existing != null
        region = existing?.region ?: defaultRegion(platform)
        loaded = true
    }

    val emailLabel = when (platform) {
        Platform.STRAVA -> "Client ID"
        Platform.IGPSPORT, Platform.ONELAP -> "用户名 / 邮箱"
        else -> "邮箱"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统一用公共的子页面标题栏：返回箭头 + 标题的位置和其它子页面完全一致。
        // 副标题与「账号管理」页保持同一行，标题基线在两页之间也不会跳。
        SubPageHeader(
            title = "${if (isExisting) "修改" else "添加"} ${platform.displayName}",
            onBack = onBack,
            subtitle = "凭据加密保存在本机",
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
            SectionCard(title = "凭据", icon = Icons.Filled.Lock) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(emailLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (platform == Platform.STRAVA) "Client Secret" else "密码",
                )
                if (isExisting) {
                    Text(
                        "留空表示不修改已保存的凭据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            regionOptions(platform)?.let { options ->
                SectionCard(title = "区域", icon = Icons.Filled.Public) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { (value, label) ->
                            FilterChip(
                                selected = region == value,
                                onClick = { region = value },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        regionHint(platform),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (platform == Platform.STRAVA) {
                SectionCard(title = "Strava 授权", icon = Icons.Filled.Link) {
                    Text(
                        "Strava 需要在 strava.com/settings/api 自行创建 API 应用，并把 " +
                            "Authorization Callback Domain 设为 localhost，然后填入 Client ID / Secret。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (platform == Platform.COROS) {
                SectionCard(title = "注意", icon = Icons.Filled.Warning) {
                    Text(
                        "Coros 只允许单设备登录：本机登录后，手机上的 Coros App 会被挤下线。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            InfoNote(
                "保存后会自动登录该账号，并把它的历史活动全量拉取到本地；" +
                    "之后的每次同步只做增量更新。",
            )
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Button(
                onClick = {
                    viewModel.saveAccount(
                        platform = platform,
                        email = email.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        region = region,
                    )
                    onBack()
                },
                enabled = loaded && (isExisting || (email.isNotBlank() && password.isNotBlank())),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("保存")
            }
        }
    }
}

/** 浅色信息条：带图标的一行说明。 */
@Composable
private fun InfoNote(text: String) {
    BorderedSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun defaultRegion(platform: Platform): String = when (platform) {
    Platform.GARMIN -> "COM"
    Platform.COROS -> "2"
    Platform.IGPSPORT -> "cn"
    else -> ""
}

private fun regionOptions(platform: Platform): List<Pair<String, String>>? = when (platform) {
    Platform.GARMIN -> listOf("COM" to "国际区", "CN" to "中国区")
    Platform.COROS -> listOf("1" to "国际区", "2" to "中国区", "3" to "欧洲区")
    Platform.IGPSPORT -> listOf("cn" to "中国站", "global" to "国际站")
    else -> null
}

private fun regionHint(platform: Platform): String = when (platform) {
    Platform.GARMIN -> "选择错误会导致登录失败：国区账号必须选 garmin.cn。"
    Platform.COROS -> "决定活动来源与 OSS 上传通道（中国区走阿里云，其它走 AWS）。"
    Platform.IGPSPORT -> "两个站点账号不通用。"
    else -> ""
}
