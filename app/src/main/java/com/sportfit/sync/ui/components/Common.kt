package com.sportfit.sync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OutlinedFlag
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.sportfit.sync.domain.Platform

/** A selectable platform pill. */
@Composable
fun PlatformChip(
    platform: Platform,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val accent = platform.accentColor()
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        label = { Text(platform.displayName) },
        leadingIcon = {
            Icon(
                platformIcon(platform),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = accent,
            selectedContainerColor = accent.copy(alpha = 0.16f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
            selectedLeadingIconColor = accent,
        ),
    )
}

fun platformIcon(platform: Platform): ImageVector = when (platform) {
    Platform.GARMIN -> Icons.Filled.OutlinedFlag
    Platform.COROS -> Icons.Filled.Speed
    Platform.STRAVA -> Icons.AutoMirrored.Filled.DirectionsRun
    Platform.IGPSPORT -> Icons.AutoMirrored.Filled.DirectionsBike
    Platform.ONELAP -> Icons.AutoMirrored.Filled.DirectionsBike
    Platform.DEMO -> Icons.Filled.Science
    Platform.LOCAL_FILES -> Icons.Filled.Folder
}

/**
 * Card with a title, used for every grouped block in the app.
 *
 * 用「容器色 + 细边框」而不是阴影：Material3 的阴影在浅色底上很脏，
 * 边框反而更干净，也更符合现在的扁平化趋势。
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                SectionTitle(text = title, icon = icon, trailing = trailing)
            }
            content()
        }
    }
}

/** Label/value row used on the settings and detail screens. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    trailing: @Composable (() -> Unit)? = null,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            trailing?.invoke()
        }
    }
}

/**
 * 状态标签：一颗小圆点 + 文字。
 *
 * 用容器色 + 同色文字，而不是 Material 的 Chip —— Chip 看起来可点，
 * 但这里的标签只是「给人看的状态」，不该有可点的暗示。
 */
@Composable
fun StatusBadge(text: String, tone: StatusTone) {
    val color = tone.accent()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

enum class StatusTone { PRIMARY, OK, WARN, ERROR, NEUTRAL }

/** 状态色：主色=强调数字，成功=辅色(青绿)，提醒=第三色(琥珀)，错误=错误色，其余=中性。 */
@Composable
fun StatusTone.accent(): Color = when (this) {
    StatusTone.PRIMARY -> MaterialTheme.colorScheme.primary
    StatusTone.OK -> MaterialTheme.colorScheme.secondary
    StatusTone.WARN -> MaterialTheme.colorScheme.tertiary
    StatusTone.ERROR -> MaterialTheme.colorScheme.error
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Collects a Garmin verification code mid-login.
 *
 * Shown whenever the auth flow suspends inside the SSO handshake; cancelling
 * resolves the wait with `null` and the sync fails cleanly.
 */
@Composable
fun MfaDialog(
    platform: Platform,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("${platform.displayName} 两步验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "该账号开启了两步验证，请输入验证器 App 或短信收到的验证码。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isLetterOrDigit) },
                    label = { Text("验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(code) },
                enabled = code.isNotBlank(),
            ) { Text("提交") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/** Password-style field with a visibility toggle. */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** Small icon + caption used in empty states. */
@Composable
fun EmptyHint(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
