@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.jianyu.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jianyu.app.MainUiState
import org.jianyu.app.SourceFormKind
import org.jianyu.core.data.AiCapabilityStatus

@Composable
internal fun SettingsScreen(
    state: MainUiState,
    onSaveAi: (String, String, String, String) -> Boolean,
    onClearSourceFormError: (SourceFormKind) -> Unit = {},
    onCheckAi: () -> Unit,
    onRemoveAi: () -> Unit,
    onSaveWorldBrief: (String, String, String) -> Boolean,
    onRemoveWorldBrief: () -> Unit,
    onConnectSyncFolder: (Uri) -> Unit,
    onRetrySyncFolderSettings: () -> Unit,
    onSyncFolder: () -> Unit,
    onDisconnectSyncFolder: () -> Unit,
    onExportPortable: (Uri) -> Unit,
    onPreviewPortable: (Uri, String) -> Unit,
    onConfirmPortableImport: () -> Unit,
    onCancelPortableImport: () -> Unit,
    onDismissRecoveryCode: () -> Unit,
    onErase: () -> Unit,
) {
    if (state.family == null) return
    DisposableEffect(Unit) {
        onDispose { onCancelPortableImport() }
    }
    val initialAiPreset = remember(state.aiProviderName, state.aiBaseUrl, state.aiConfigured) {
        if (state.aiConfigured) matchAiProviderPreset(state.aiProviderName, state.aiBaseUrl)
        else jianyuAiProviderPresets.first()
    }
    var confirmErase by remember { mutableStateOf(false) }
    var confirmDisconnectFolder by remember { mutableStateOf(false) }
    var pendingSourceDisconnect by remember { mutableStateOf<SourceFormKind?>(null) }
    var selectedAiPresetId by remember { mutableStateOf(initialAiPreset.id) }
    var providerName by remember { mutableStateOf(state.aiProviderName ?: initialAiPreset.providerName) }
    var baseUrl by remember { mutableStateOf(state.aiBaseUrl ?: initialAiPreset.baseUrl) }
    var model by remember { mutableStateOf(state.aiModel ?: initialAiPreset.exampleModel) }
    var apiKey by remember { mutableStateOf("") }
    var worldBriefName by remember { mutableStateOf(state.worldBriefProviderName ?: "") }
    var worldBriefEndpoint by remember { mutableStateOf(state.worldBriefEndpoint ?: "") }
    var worldBriefApiKey by remember { mutableStateOf("") }
    var showAiForm by remember { mutableStateOf(false) }
    var showAiAdvanced by remember { mutableStateOf(initialAiPreset.custom) }
    var showAiCheck by remember(state.aiConfigured) { mutableStateOf(false) }
    var showWorldBriefForm by remember { mutableStateOf(false) }
    var showWorldBoundary by remember(state.worldBriefConfigured) { mutableStateOf(false) }
    var pendingSourceSave by remember { mutableStateOf<SourceFormKind?>(null) }
    var pendingSourceFromRevision by remember { mutableStateOf(0L) }
    val sourceFormBusy = state.sourceFormSaving != null
    LaunchedEffect(state.sourceFormSaveRevision, state.lastSavedSourceForm, pendingSourceSave, pendingSourceFromRevision) {
        if (state.sourceFormSaveRevision > pendingSourceFromRevision && state.lastSavedSourceForm == pendingSourceSave) {
            when (pendingSourceSave) {
                SourceFormKind.AI -> {
                    showAiForm = false
                    apiKey = ""
                }
                SourceFormKind.WORLD -> {
                    showWorldBriefForm = false
                    worldBriefApiKey = ""
                }
                null -> Unit
            }
            pendingSourceSave = null
        }
    }
    var showPrivacyDetails by remember { mutableStateOf(false) }
    var showAdvancedDataTools by remember(state.folderSyncConfigured, state.folderSyncSettingsUnreadable) {
        mutableStateOf(state.folderSyncConfigured || state.folderSyncSettingsUnreadable)
    }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importRecoveryCode by remember { mutableStateOf("") }
    LaunchedEffect(state.portableImportPreview) {
        if (state.portableImportPreview != null) importRecoveryCode = ""
    }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(onExportPortable) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onCancelPortableImport()
            pendingImportUri = uri
            importRecoveryCode = ""
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(onConnectSyncFolder) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(
            horizontal = JianyuLayout.screenHorizontal,
            vertical = JianyuLayout.screenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(JianyuLayout.sectionGap),
    ) {
        item {
            JianyuPageHeader(
                section = "设置",
                title = "机会与资料",
                description = "设置寻找入口的来源，管理留在本机的家庭资料。",
            )
        }
        item {
            JianyuSectionHeader(
                title = "机会来源",
                description = "AI 与世界信息由你自行设置；不设置也能查看离线演示。",
            )
        }
        item {
            JianyuCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    JianyuCardTitle("AI 发现")
                    if (state.aiConfigured) {
                        JianyuSavedConnection(state.aiProviderName, state.aiModel ?: "名称待确认")
                    } else {
                        Text("尚未设置 AI 服务。想正式寻找入口，先连接你选择的 AI；每次发送前仍由你确认。")
                    }
                    if (!showAiForm) {
                        if (state.aiConfigured) {
                            JianyuSupportingText("保存不代表服务可用；正式寻找入口前仍要确认发送。")
                            SourceMaintenanceActions(
                                onReplace = { onClearSourceFormError(SourceFormKind.AI); showAiForm = true },
                                onDisconnect = { pendingSourceDisconnect = SourceFormKind.AI },
                                enabled = !sourceFormBusy,
                            )
                            JianyuDisclosureToggle(
                                expanded = showAiCheck,
                                onClick = { showAiCheck = !showAiCheck },
                                collapsedLabel = "查看连接检查",
                                expandedLabel = "收起连接检查",
                            )
                            if (showAiCheck) {
                                JianyuInset {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            JianyuSupportingText("仅发虚构样例，不含家庭资料。")
                                            JianyuSupportingText("服务商可能收费。")
                                            JianyuSupportingText("只检查一次回复。")
                                            JianyuSupportingText("不评测入口质量或安全性。")
                                        }
                                        TextButton(onClick = onCheckAi, enabled = !state.aiCapabilityChecking && !sourceFormBusy) {
                                            Text(if (state.aiCapabilityChecking) "正在检查虚构样例…" else "试一次虚构样例")
                                        }
                                        state.aiCapabilityResult?.let { result ->
                                            JianyuInset(
                                                tone = if (result.status == AiCapabilityStatus.SAMPLE_PASSED ||
                                                    result.status == AiCapabilityStatus.VALID_NOTHING) JianyuCardTone.NEUTRAL
                                                    else JianyuCardTone.HUMAN,
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("这次虚构样例的结果", style = MaterialTheme.typography.labelLarge)
                                                    Text(
                                                        when (result.status) {
                                                            AiCapabilityStatus.SAMPLE_PASSED -> "这次虚构样例有 ${result.acceptedCount} 个经过本机筛选、可展示的入口；仍需家庭判断。"
                                                            AiCapabilityStatus.VALID_NOTHING -> "没有硬凑入口。这次样例无法判断生成入口的能力。"
                                                            AiCapabilityStatus.FORMAT_INCOMPATIBLE -> "服务有响应，但入口格式无法读取；请换模型或核对接口设置。"
                                                            AiCapabilityStatus.RESPONSE_INCOMPLETE -> "服务返回的内容未完整结束；这次虚构样例不能判断模型是否合适，请稍后重试。"
                                                            AiCapabilityStatus.RESPONSE_TIMED_OUT -> "等待服务响应超时；这次虚构样例不能判断模型是否合适。重试可能再次计费。"
                                                            AiCapabilityStatus.LOCAL_CHECK_REJECTED -> "返回了入口，但都没通过这次虚构样例的本机检查。"
                                                            AiCapabilityStatus.NO_DISPLAYABLE_DOORS -> "样例返回了可读入口，但本机没有可展示的孩子入口；这次不能据此判断模型适合。"
                                                            AiCapabilityStatus.CONNECTION_FAILED -> when (result.httpStatus) {
                                                                401, 403 -> "服务拒绝了访问。请检查密钥和模型权限。"
                                                                429 -> "服务暂时限制了请求。请检查额度或稍后再试。"
                                                                400, 404, 413, 422 -> "服务未接受这次请求。请核对模型名称、服务地址与接口兼容性。"
                                                                else -> "未能完成请求。请检查网络、服务地址、模型 ID 和密钥。"
                                                            }
                                                            AiCapabilityStatus.SETTINGS_UNAVAILABLE -> "本机保存的连接无法读取。请更换连接并重新填写密钥。"
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Button(onClick = { onClearSourceFormError(SourceFormKind.AI); showAiForm = true }, enabled = !sourceFormBusy) { Text("连接 AI 服务") }
                        }
                    } else {
                        Text("选择服务", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            jianyuAiProviderPresets.forEach { preset ->
                                JianyuChoiceChip(
                                    selected = preset.id == selectedAiPresetId,
                                    onClick = {
                                        selectedAiPresetId = preset.id
                                        providerName = preset.providerName
                                        baseUrl = preset.baseUrl
                                        model = preset.exampleModel
                                        apiKey = ""
                                        showAiAdvanced = preset.custom
                                    },
                                    label = preset.label,
                                    enabled = !sourceFormBusy,
                                )
                            }
                        }
                        val selectedPreset = jianyuAiProviderPresets.first { it.id == selectedAiPresetId }
                        if (selectedPreset.custom) {
                            Text(
                                selectedPreset.guidance,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            model,
                            { model = it },
                            label = { Text(if (selectedPreset.custom) "模型名称或 ID" else "使用的模型") },
                            supportingText = {
                                if (!selectedPreset.custom) Text("示例模型，可修改；尚未经本项目质量评测。")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !sourceFormBusy,
                        )
                        OutlinedTextField(
                            apiKey,
                            { apiKey = it },
                            label = { Text(if (state.aiConfigured) "新的 API 密钥" else "API 密钥") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !sourceFormBusy,
                        )
                        if (!selectedPreset.custom) {
                            JianyuDisclosureToggle(
                                expanded = showAiAdvanced,
                                onClick = { showAiAdvanced = !showAiAdvanced },
                                collapsedLabel = "查看高级连接设置",
                                expandedLabel = "收起高级连接设置",
                                enabled = !sourceFormBusy,
                            )
                        }
                        if (selectedPreset.custom || showAiAdvanced) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                            OutlinedTextField(
                                providerName,
                                { providerName = it },
                                label = { Text("显示名称") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !sourceFormBusy,
                            )
                            OutlinedTextField(
                                baseUrl,
                                { baseUrl = it },
                                label = { Text("服务地址（HTTPS）") },
                                supportingText = { Text("系统会在地址末尾调用 /chat/completions") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !sourceFormBusy,
                            )
                        }
                        Text("密钥会单独加密保存在本机，不进入家庭保险箱或同步资料。返回的入口仍需经过本机检查。", style = MaterialTheme.typography.bodySmall)
                        if (state.sourceFormError == SourceFormKind.AI) {
                            JianyuInset(tone = JianyuCardTone.HUMAN) {
                                Text(state.error ?: "本机保存没有完成。请检查设备存储状态后重试。")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    if (onSaveAi(providerName, baseUrl, model, apiKey)) {
                                        pendingSourceFromRevision = state.sourceFormSaveRevision
                                        pendingSourceSave = SourceFormKind.AI
                                    }
                                },
                                enabled = providerName.isNotBlank() && baseUrl.startsWith("https://") && model.isNotBlank() && apiKey.isNotBlank() && !sourceFormBusy,
                            ) { Text(if (state.sourceFormSaving == SourceFormKind.AI) "正在保存…" else if (state.aiConfigured) "保存新连接" else "保存连接") }
                            TextButton(onClick = {
                                showAiForm = false
                                apiKey = ""
                                pendingSourceSave = null
                                onClearSourceFormError(SourceFormKind.AI)
                            }, enabled = !sourceFormBusy) { Text("取消") }
                        }
                    }
                    if (state.aiConfigured || showAiForm) {
                        val policyPreset = if (state.aiConfigured && !showAiForm) {
                            matchAiProviderPreset(state.aiProviderName, state.aiBaseUrl)
                        } else {
                            jianyuAiProviderPresets.first { it.id == selectedAiPresetId }
                        }
                        if (policyPreset.custom) {
                            Text(
                                "使用自定义服务前，请到服务商官网核对条款、数据处理方式和费用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                policyPreset.termsUrl?.let { url ->
                                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, enabled = !sourceFormBusy) { Text("服务商条款 ↗") }
                                }
                                policyPreset.privacyUrl?.let { url ->
                                    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, enabled = !sourceFormBusy) { Text("数据处理说明 ↗") }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            JianyuCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    JianyuCardTitle("世界信息")
                    if (state.worldBriefConfigured) {
                        JianyuSavedConnection(state.worldBriefProviderName)
                    } else {
                        Text("可选。连接你选择的服务，寻找正在发生的展览、赛事和城市活动。官方当前不运营此类服务。")
                    }
                    if (!showWorldBriefForm) {
                        if (state.worldBriefConfigured) {
                            JianyuSupportingText("保存不代表服务可用。找入口时，填写的地区可能原样发送；请勿填精确地址或个人信息。")
                            SourceMaintenanceActions(
                                onReplace = { onClearSourceFormError(SourceFormKind.WORLD); showWorldBriefForm = true },
                                onDisconnect = { pendingSourceDisconnect = SourceFormKind.WORLD },
                                enabled = !sourceFormBusy,
                            )
                            JianyuDisclosureToggle(
                                expanded = showWorldBoundary,
                                onClick = { showWorldBoundary = !showWorldBoundary },
                                collapsedLabel = "查看信息边界",
                                expandedLabel = "收起信息边界",
                            )
                            if (showWorldBoundary) {
                                JianyuInset {
                                    JianyuSupportingText("只获取公共信息，兴趣匹配在本机；不会额外附上孩子姓名、兴趣或家庭安排。官方当前不运营此类服务。")
                                }
                            }
                        } else {
                            OutlinedButton(onClick = {
                                onClearSourceFormError(SourceFormKind.WORLD)
                                showWorldBriefForm = true
                            }, enabled = !sourceFormBusy) { Text("连接世界信息服务") }
                        }
                    } else {
                        OutlinedTextField(
                            worldBriefName,
                            { worldBriefName = it },
                            label = { Text("显示名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !sourceFormBusy,
                        )
                        OutlinedTextField(
                            worldBriefEndpoint,
                            { worldBriefEndpoint = it },
                            label = { Text("HTTPS 信息源地址") },
                            supportingText = { Text("填写兼容世界信息接口的服务地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !sourceFormBusy,
                        )
                        OutlinedTextField(
                            worldBriefApiKey,
                            { worldBriefApiKey = it },
                            label = { Text(if (state.worldBriefConfigured) "新的访问密钥（可留空）" else "访问密钥（公开服务可留空）") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !sourceFormBusy,
                        )
                        if (state.sourceFormError == SourceFormKind.WORLD) {
                            JianyuInset(tone = JianyuCardTone.HUMAN) {
                                Text(state.error ?: "本机保存没有完成。请检查设备存储状态后重试。")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    if (onSaveWorldBrief(worldBriefName, worldBriefEndpoint, worldBriefApiKey)) {
                                        pendingSourceFromRevision = state.sourceFormSaveRevision
                                        pendingSourceSave = SourceFormKind.WORLD
                                    }
                                },
                                enabled = worldBriefName.isNotBlank() && worldBriefEndpoint.startsWith("https://") && !sourceFormBusy,
                            ) { Text(if (state.sourceFormSaving == SourceFormKind.WORLD) "正在保存…" else if (state.worldBriefConfigured) "保存新连接" else "保存连接") }
                            TextButton(onClick = {
                                showWorldBriefForm = false
                                worldBriefApiKey = ""
                                pendingSourceSave = null
                                onClearSourceFormError(SourceFormKind.WORLD)
                            }, enabled = !sourceFormBusy) { Text("取消") }
                        }
                    }
                }
            }
        }
        item {
            JianyuSectionHeader(
                title = "资料与设备",
                description = "家庭资料默认留在本机；恢复、传输与删除都由你主动决定。",
            )
        }
        item {
            JianyuCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JianyuCardTitle("你的数据，边界清楚")
                    Text("核心记录留在本机；AI 每次单独授权；世界信息与家庭资料分开。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JianyuDisclosureToggle(
                        expanded = showPrivacyDetails,
                        onClick = { showPrivacyDetails = !showPrivacyDetails },
                        collapsedLabel = "查看保存、加密与同步边界",
                        expandedLabel = "收起保存、加密与同步边界",
                    )
                }
            }
        }
        if (showPrivacyDetails) {
            item {
                SettingsCard("本机优先", "目前只在这台设备保存和查看这份家庭资料；两位家长若要看同一份资料，需要共用这台设备。手动恢复包可用于换机，不会自动合并或同步。官方当前不运营家庭服务器。")
            }
            item {
                SettingsCard("设备级加密", "家庭保险箱使用手机系统生成且不可导出的密钥加密；系统应用备份已关闭。")
            }
            item {
                SettingsCard("发送前最小化", "应用按这次的用途控制发送内容，但不会可靠地从你填写的文字中自动删去姓名或地址。世界信息服务不能读取家庭保险箱。")
            }
            item {
                SettingsCard("同步边界", "规划中的局域网同步只在同一网络交换资料，异地不能及时同步。NAS、WebDAV、S3 和第三方托管需要兼容服务，资料离开手机前必须先加密；现在还不能自动让多台设备同步。")
            }
        }
        item {
            JianyuCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JianyuCardTitle("加密传输与恢复")
                    Text(
                        if (state.folderSyncSettingsUnreadable) "先前的文件夹连接信息暂时无法读取；不会继续传输。"
                        else if (state.folderSyncConfigured) "已选择文件夹；不会自动同步。\n手动传输；恢复包另存。"
                        else "平时不需要维护。需要换设备或保存副本时，再查看这些开发预览功能。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    JianyuDisclosureToggle(
                        expanded = showAdvancedDataTools,
                        onClick = { showAdvancedDataTools = !showAdvancedDataTools },
                        collapsedLabel = "查看开发预览功能",
                        expandedLabel = "收起开发预览功能",
                    )
                }
            }
        }
        if (showAdvancedDataTools) {
            item {
                JianyuCard(tone = JianyuCardTone.PREVIEW) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        JianyuCardTitle("加密文件夹传输 · 开发预览")
                        if (state.folderSyncSettingsUnreadable) {
                            Text("本机暂时无法读取先前保存的文件夹连接信息。旧密文没有因此被删除；重新选择文件夹不能找回原来的传输密钥。")
                            JianyuSupportingText("先重试读取。家庭资料仍以这台设备的家庭保险箱为准；换机请使用单独保管的恢复包。")
                            OutlinedButton(onClick = onRetrySyncFolderSettings, enabled = !sourceFormBusy) {
                                Text("重试读取")
                            }
                            TextButton(onClick = { confirmDisconnectFolder = true }, enabled = !sourceFormBusy) {
                                Text("放弃旧连接")
                            }
                        } else if (state.folderSyncConfigured) {
                            Text("已选择文件夹：${state.folderSyncName}")
                            JianyuSupportingText("本机加密后，写入随机命名的文件。")
                            JianyuSupportingText("文件夹服务看不到家庭内容，但能看到文件大小和操作时间。")
                            Text(
                                "现在不能安全地多设备共用，也不会整理旧副本。它不是完整同步，请另存恢复包。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = onSyncFolder, enabled = !state.syncingFolder && !sourceFormBusy) {
                                    Text(if (state.syncingFolder) "正在核对密文…" else "立即传输一次")
                                }
                                OutlinedButton(onClick = { folderLauncher.launch(null) }, enabled = !state.syncingFolder && !sourceFormBusy) {
                                    Text("更换文件夹")
                                }
                                TextButton(onClick = { confirmDisconnectFolder = true }, enabled = !state.syncingFolder && !sourceFormBusy) { Text("断开") }
                            }
                        } else {
                            Text("先选择一个你控制的本地文件夹、NAS 挂载目录或系统支持的文档目录。选择时不会上传家庭资料。")
                            Text(
                                "选择后仍要由你主动传输，资料会先在本机加密；现在还不能安全地让多台设备共用，也不支持直接连接 WebDAV 或 S3。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { folderLauncher.launch(null) }, enabled = !sourceFormBusy) { Text("选择文件夹") }
                        }
                    }
                }
            }
            item {
                JianyuCard(tone = JianyuCardTone.PREVIEW) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        JianyuCardTitle("加密恢复包 · 开发预览")
                        Text("把家庭保险箱手动带到另一台设备。恢复包和恢复码必须分开保管；这不是自动同步，也不是普通明文导出。")
                        Text(
                            "存储服务仍可能看到文件大小和传输时间。该格式目前只完成了合成数据与自动测试，尚未经过独立安全审计。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { exportLauncher.launch("family-opportunity-recovery.foe") }, enabled = !sourceFormBusy) {
                                Text("导出恢复包")
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*")) },
                                enabled = !sourceFormBusy,
                            ) {
                                Text("导入恢复包")
                            }
                        }
                    }
                }
            }
        }
        item {
            JianyuCard(tone = JianyuCardTone.DANGER) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    JianyuCardTitle("删除家庭保险箱")
                    Text("删除本机密文并销毁手机系统保存的密钥。该操作无法撤销。")
                    JianyuDangerOutlinedButton(onClick = { confirmErase = true }, enabled = !sourceFormBusy) { Text("永久删除") }
                }
            }
        }
        item {
            Text("${state.brandName} 0.1.0 · Apache-2.0\n${state.brandMission}", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("确认永久删除？") },
            text = { Text("家庭记录、本机保险箱密钥以及所选文件夹使用的本机传输密钥都会从这台设备移除，无法恢复。文件夹里的既有密文不会被自动删除。") },
            confirmButton = { JianyuDangerTextButton(onClick = { confirmErase = false; onErase() }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("取消") } },
        )
    }
    if (confirmDisconnectFolder) {
        AlertDialog(
            onDismissRequest = { confirmDisconnectFolder = false },
            title = { Text(if (state.folderSyncSettingsUnreadable) "放弃旧连接信息？" else "断开并销毁传输密钥？") },
            text = {
                Text(
                    if (state.folderSyncSettingsUnreadable) {
                        "会清除本机无法读取的旧连接信息和传输密钥；旧文件夹里的密文不会删除，且可能再也无法读取。由于旧文件夹位置也读不出，系统授予的访问权限可能需要你另行移除。"
                    } else {
                        "断开后，本应用会撤销文件夹授权并销毁保存在本机的传输密钥，但不会删除文件夹里的既有密文。当前预览尚不能从恢复包找回这把密钥；那些密文可能无法再次读取。"
                    },
                )
            },
            confirmButton = {
                JianyuDangerTextButton(onClick = {
                    confirmDisconnectFolder = false
                    onDisconnectSyncFolder()
                }) { Text(if (state.folderSyncSettingsUnreadable) "确认放弃" else "确认断开") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnectFolder = false }) { Text("保留连接") } },
        )
    }
    pendingSourceDisconnect?.let { kind ->
        AlertDialog(
            onDismissRequest = { pendingSourceDisconnect = null },
            title = { Text(if (kind == SourceFormKind.AI) "断开 AI 服务？" else "断开世界信息服务？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (kind == SourceFormKind.AI) {
                        Text("AI 连接和 API 密钥会从本机删除。")
                    } else {
                        Text("世界信息连接会从本机删除。")
                        Text("保存的 API 密钥（如有）也会删除。")
                    }
                    Text("家庭保险箱不受影响。")
                    Text(if (kind == SourceFormKind.AI) "已发送内容无法撤回。" else "已发送的查询无法撤回。")
                    Text("再次使用需重新连接。")
                }
            },
            confirmButton = {
                JianyuDangerTextButton(onClick = {
                    pendingSourceDisconnect = null
                    if (kind == SourceFormKind.AI) onRemoveAi() else onRemoveWorldBrief()
                }) { Text("确认断开") }
            },
            dismissButton = { TextButton(onClick = { pendingSourceDisconnect = null }) { Text("保留连接") } },
        )
    }
    state.portableRecoveryCode?.let { recoveryCode ->
        AlertDialog(
            onDismissRequest = onDismissRecoveryCode,
            title = { Text("单独保存恢复码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("恢复码不会写入恢复包，也不会再次显示。请把它与恢复包分开保存。任何同时得到两者的人都能读取其中的数据；丢失恢复码后将无法找回。")
                    JianyuCard {
                        SelectionContainer {
                            Text(
                                recoveryCode,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { clipboard.setText(AnnotatedString(recoveryCode)) }) {
                    Text("复制恢复码")
                }
            },
            dismissButton = { TextButton(onClick = onDismissRecoveryCode) { Text("我已单独保存") } },
        )
    }
    pendingImportUri?.let { source ->
        PortableImportDialog(
            source = source,
            state = state,
            recoveryCode = importRecoveryCode,
            onRecoveryCode = { importRecoveryCode = it.trim() },
            onPreview = onPreviewPortable,
            onConfirm = onConfirmPortableImport,
            onDismiss = {
                onCancelPortableImport()
                pendingImportUri = null
                importRecoveryCode = ""
            },
        )
    }
}

@Composable
internal fun PortableImportDialog(
    source: Uri,
    state: MainUiState,
    recoveryCode: String,
    onRecoveryCode: (String) -> Unit,
    onPreview: (Uri, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = state.portableImportChecking || state.portableImportSaving
    val preview = state.portableImportPreview
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (preview == null) "先检查恢复包" else "确认替换家庭保险箱？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (preview == null) {
                    Text("先用恢复码验证所选文件。检查不会改动这台设备的家庭资料；验证后再决定是否替换。")
                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = onRecoveryCode,
                        label = { Text("恢复码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                    )
                } else {
                    JianyuInset(tone = JianyuCardTone.HUMAN) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("这台设备：${preview.currentFamilyName}")
                            Text("恢复包：${preview.incomingFamilyName}")
                        }
                    }
                    Text("确认后会用恢复包替换本机资料，不会合并。即使两个家庭称呼相同，也请先确认现有资料已经另行备份。")
                }
                state.portableImportError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (preview == null) {
                Button(
                    enabled = recoveryCode.isNotBlank() && !busy,
                    onClick = { onPreview(source, recoveryCode) },
                ) { Text(if (state.portableImportChecking) "正在检查…" else "检查恢复包") }
            } else {
                JianyuDangerTextButton(enabled = !busy, onClick = onConfirm) {
                    Text(if (state.portableImportSaving) "正在替换…" else "确认替换本机资料")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun SourceMaintenanceActions(
    onReplace: () -> Unit,
    onDisconnect: () -> Unit,
    enabled: Boolean,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = onReplace, enabled = enabled) { Text("更换连接") }
        TextButton(onClick = onDisconnect, enabled = enabled) { Text("断开") }
    }
}

@Composable
private fun SettingsCard(title: String, body: String) {
    JianyuCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            JianyuCardTitle(title)
            Text(body)
        }
    }
}

@Composable
internal fun EmptyState(title: String, body: String) {
    JianyuCard(contentPadding = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            JianyuCardTitle(title)
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
