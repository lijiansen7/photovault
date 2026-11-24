package com.photovault.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
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
    var showDeviceIdDialog by remember { mutableStateOf(false) }
    var deviceIdDraft by remember { mutableStateOf("") }
    LaunchedEffect(stored.loggedIn) { if (stored.loggedIn) vm.refreshTrash() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "内网优先，内网连不上会自动切到外网地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("NAS 服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                // 两个地址框单独一组、间距 16：浮动 label 会骑在边框上，
                // 间距太小看起来像两个框叠在一起
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = vm.draft.lanUrl,
                        onValueChange = { vm.edit { c -> c.copy(lanUrl = it) } },
                        label = { Text("内网地址（优先）") },
                        placeholder = { Text("192.168.1.10:8766") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = vm.draft.wanUrl,
                        onValueChange = { vm.edit { c -> c.copy(wanUrl = it) } },
                        label = { Text("外网地址（备用）") },
                        placeholder = { Text("https://photo.example.com:8765") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    "只填一个也可以。两个都填时先连内网，内网 2 秒内没响应就自动走外网，不会卡住备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Text("记住密码（后台备份 token 失效时自动重登）", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = vm.rememberPw, onCheckedChange = vm::toggleRemember)
                    }
                    Button(onClick = { vm.login() }, enabled = !vm.loggingIn) {
                        Text(if (vm.loggingIn) "登录中…" else "登录")
                    }
                    vm.loginError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "内网填 NAS 局域网地址，外网填域名地址；两边是同一台 NAS 时 token 通用。" +
                            "端口不填默认 8765，走 443 反代的域名才不用写端口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.test() },
                        enabled = !vm.testing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (vm.testing) "测试中…" else "测试连接")
                    }
                    // 保存是主操作：占同样宽度、用实心按钮，而不是挤在左边
                    Button(onClick = { vm.save() }, modifier = Modifier.weight(1f)) { Text("保存") }
                }

                vm.testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (vm.testOk) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
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
                ToggleRow("手机删除后，NAS 也移入回收站", vm.draft.syncDeletions) {
                    vm.editBackup { c -> c.copy(syncDeletions = it) }
                }
                Text(
                    "只处理这台手机备份过、且本机相册里已经消失的照片：NAS 上会进回收站，可恢复，" +
                        "「一键还原」也不会再把它们写回来。若本机相册读取不完整（SD 卡未挂载、" +
                        "只授权了部分照片），本轮会自动跳过，避免误删。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("关于", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("PhotoVault", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                AboutRow("版本", BuildConfig.VERSION_NAME)
                AboutRow("构建时间", BuildConfig.BUILD_TIME)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("设备 ID", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (stored.deviceIdCustom) "自定义" else "自动",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        SelectionContainer {
                            Text(
                                vm.deviceId.ifBlank { "读取中…" },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    val clipboard = LocalClipboardManager.current
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(vm.deviceId)) },
                        enabled = vm.deviceId.isNotBlank(),
                    ) { Text("复制") }
                    TextButton(onClick = {
                        deviceIdDraft = vm.deviceId
                        showDeviceIdDialog = true
                    }) { Text("修改") }
                }
                vm.deviceIdMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("修改失败")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "默认由本机 Android ID 派生，卸载重装不会变，适合长期使用。" +
                        "点「修改」可以自定义 ID；自定义后卸载重装时，要先填回同一个 ID 再同步，" +
                        "否则 NAS 会把它当成一台新设备。ID 必须唯一，只能包含字母、数字、点、下划线、短横线，长度 4~64。" +
                        "恢复出厂设置或更换签名包时，自动 ID 也会变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }

    if (showDeviceIdDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceIdDialog = false },
            title = { Text("自定义设备 ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "修改后会把 NAS 上旧 ID 的照片迁到新 ID。以后卸载重装时，重新填同一个 ID 即可继续删除同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = deviceIdDraft,
                        onValueChange = { deviceIdDraft = it },
                        label = { Text("设备 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "只能包含字母、数字、点、下划线、短横线，长度 4~64。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    vm.deviceIdMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("修改失败")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.changeDeviceId(deviceIdDraft)
                    showDeviceIdDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeviceIdDialog = false }) { Text("取消") }
            },
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
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
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
