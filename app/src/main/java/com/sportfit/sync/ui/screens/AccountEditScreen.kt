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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.ui.AppViewModel
import com.sportfit.sync.ui.components.SectionCard
import com.sportfit.sync.ui.components.SecretField

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
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "${if (isExisting) "修改" else "添加"} ${platform.displayName}",
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
            SectionCard(title = "凭据") {
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
                SectionCard(title = "区域") {
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
                SectionCard(title = "Strava 授权") {
                    Text(
                        "Strava 需要在 strava.com/settings/api 自行创建 API 应用，并把 " +
                            "Authorization Callback Domain 设为 localhost，然后填入 Client ID / Secret。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (platform == Platform.COROS) {
                SectionCard(title = "注意") {
                    Text(
                        "Coros 只允许单设备登录：本机登录后，手机上的 Coros App 会被挤下线。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "保存后会自动登录该账号，并把它的历史活动全量拉取到本地；" +
                    "之后的每次同步只做增量更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                .padding(16.dp),
        ) {
            Text("保存")
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
