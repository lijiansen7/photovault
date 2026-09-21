package com.photovault.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.photovault.app.BuildConfig

@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    onOpenTrash: () -> Unit = {},
) {
    val stored by vm.stored.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(stored.loggedIn) { if (stored.loggedIn) vm.refreshTrash() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NAS 服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                OutlinedTextField(
                    value = vm.draft.baseUrl,
                    onValueChange = { vm.edit { c -> c.copy(baseUrl = it) } },
                    label = { Text("NAS 地址") },
                    placeholder = { Text("192.168.1.10  或  http://nas.home:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (stored.loggedIn) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "已登录：${stored.who}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (stored.isAdmin) "管理员 · 可看到所有人的备份" else "普通用户 · 只看得到自己备份的照片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { vm.logout() }) { Text("退出") }
                    }
                } else {
                    OutlinedTextField(
                        value = vm.loginUser,
                        onValueChange = vm::typeUser,
                        label = { Text("账号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = vm.loginPass,
                        onValueChange = vm::typePass,
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("记住密码（下次自动重登）", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = vm.rememberPw, onCheckedChange = vm::toggleRemember)
                    }
                    Button(onClick = { vm.login() }, enabled = !vm.loggingIn) {
                        Text(if (vm.loggingIn) "登录中…" else "登录")
                    }
                    vm.loginError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "账号由 NAS 网页端「用户」页创建。端口不填默认 8765。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.test() }, enabled = !vm.testing) {
                        Text(if (vm.testing) "测试中…" else "测试连接")
                    }
                    Button(onClick = { vm.save() }) { Text("保存") }
                }

                vm.testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("自动备份", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                ToggleRow("自动备份总开关", vm.draft.backupEnabled) { vm.editBackup { c -> c.copy(backupEnabled = it) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ToggleRow("仅在 WiFi 下上传", vm.draft.wifiOnly) { vm.editBackup { c -> c.copy(wifiOnly = it) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ToggleRow("仅在充电时上传", vm.draft.chargingOnly) { vm.editBackup { c -> c.copy(chargingOnly = it) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ToggleRow("包含视频", vm.draft.includeVideo) { vm.editBackup { c -> c.copy(includeVideo = it) } }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "检查周期：每 ${vm.draft.periodMinutes} 分钟（系统最短 15 分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15L, 60L, 360L, 1440L).forEach { m ->
                        OutlinedButton(onClick = { vm.editBackup { c -> c.copy(periodMinutes = m) } }) {
                            Text(when (m) { 15L -> "15分"; 60L -> "1时"; 360L -> "6时"; else -> "1天" })
                        }
                    }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("高级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "「重置同步位置」会让 App 重新扫描整个本机相册并逐张比对，"
                        + "已经备份过的不会重复上传（按内容指纹去重），只是扫描慢一点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { vm.resetSyncPoint() }) { Text("重置同步位置") }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("还原时，手机里已经有同一张照片", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "skip" to "跳过",
                        "duplicate" to "留两份",
                        "overwrite" to "覆盖",
                    ).forEach { (k, label) ->
                        FilterChip(
                            selected = vm.draft.restoreConflict == k,
                            onClick = { vm.editBackup { c -> c.copy(restoreConflict = k) } },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    when (vm.draft.restoreConflict) {
                        "duplicate" -> "每次还原都新增一份副本，重复点会越堆越多。"
                        "overwrite" -> "只能覆盖自己写入过的文件；系统相机、微信保存的照片改不了，会自动降级成留两份。"
                        else -> "手机里已有的不动，只补缺的。重复点「一键还原」不会产生副本。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("回收站", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (vm.trashCount == 0) {
                    Text(
                        "回收站是空的。删除的照片会先进这里、可随时恢复；只有「清空回收站」才会真正删文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "回收站里有 ${vm.trashCount} 张照片。普通删除不会进这里——能看到的，都是被「删除」或网页端移除的。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenTrash) { Text("管理回收站") }
                        OutlinedButton(
                            onClick = { showClearConfirm = true },
                            enabled = !vm.emptying,
                        ) { Text(if (vm.emptying) "清空中…" else "清空回收站") }
                    }
                }
                vm.trashMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.startsWith("已清空")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            "PhotoVault ${BuildConfig.VERSION_NAME}\n构建于 ${BuildConfig.BUILD_TIME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 12.dp),
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空回收站？") },
            text = {
                Text(
                    "将永久删除回收站里的 ${vm.trashCount} 张照片，包括它们在本机 NAS 上的原文件。" +
                        "此操作不可恢复。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        vm.emptyTrash()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("永久删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}
