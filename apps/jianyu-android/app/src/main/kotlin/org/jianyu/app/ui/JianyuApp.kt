package org.jianyu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Route
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jianyu.app.AppRoute
import org.jianyu.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JianyuApp(
    viewModel: MainViewModel = viewModel(),
    incomingShareText: String? = null,
    onIncomingShareConsumed: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.disclosureSaveRisk) {
        if (!state.disclosureSaveRisk) state.error?.let { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    LaunchedEffect(incomingShareText) {
        incomingShareText?.let {
            viewModel.receiveIncomingShare(it)
            onIncomingShareConsumed()
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (state.vaultOpenFailed) {
        VaultOpenFailureScreen(state.error, viewModel::retryOpenVault)
        return
    }

    if (state.family == null) {
        OnboardingScreen(
            brandName = state.brandName,
            hero = state.brandHero,
            mission = state.brandMission,
            saving = state.onboardingSaving,
            error = state.error,
            onSetup = viewModel::setup,
        )
        return
    }

    Scaffold(
        topBar = { JianyuTopAppBar(state.brandName) },
        bottomBar = {
            JianyuNavigation(
                state.route,
                state.familyFormSaving != null || state.sourceFormSaving != null || state.feedbackSavingChoiceId != null ||
                    state.choiceDeletingId != null ||
                    state.portableImportChecking || state.portableImportSaving,
                viewModel::navigate,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    when (state.route) {
                        AppRoute.TODAY -> TodayScreen(state, viewModel)
                        AppRoute.CHILDREN -> ChildrenScreen(state, viewModel)
                        AppRoute.TIMELINE -> TimelineScreen(
                            family = state.family!!,
                            feedbackSavingChoiceId = state.feedbackSavingChoiceId,
                            choiceDeletingId = state.choiceDeletingId,
                            onCaregiverFeedback = viewModel::feedback,
                            onChildFeedback = viewModel::feedbackFromChild,
                            onCorrectEvidence = viewModel::correctEvidence,
                            onDeleteEvidence = viewModel::deleteEvidence,
                            onDeleteChoice = viewModel::deleteChoice,
                        )
                        AppRoute.SETTINGS -> SettingsScreen(
                            state = state,
                            onSaveAi = viewModel::saveAiProvider,
                            onClearSourceFormError = viewModel::clearSourceFormError,
                            onCheckAi = viewModel::checkAiCapability,
                            onRemoveAi = viewModel::removeAiProvider,
                            onSaveWorldBrief = viewModel::saveWorldBriefProvider,
                            onRemoveWorldBrief = viewModel::removeWorldBriefProvider,
                            onConnectSyncFolder = viewModel::connectSyncFolder,
                            onRetrySyncFolderSettings = viewModel::retryOpenSyncFolderSettings,
                            onSyncFolder = viewModel::synchronizeFolderNow,
                            onDisconnectSyncFolder = viewModel::disconnectSyncFolder,
                            onExportPortable = viewModel::exportPortableBundle,
                            onPreviewPortable = viewModel::previewPortableBundle,
                            onConfirmPortableImport = viewModel::confirmPortableImport,
                            onCancelPortableImport = viewModel::cancelPortableImport,
                            onDismissRecoveryCode = viewModel::dismissRecoveryCode,
                            onErase = viewModel::eraseVault,
                        )
                    }
                }
            }
        }
    }
}

/** A forked brand can be long; the local-encryption boundary must remain visible. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JianyuTopAppBar(brandName: String) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    JianyuMark(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(6.dp).width(24.dp).height(24.dp),
                    )
                }
                Text(
                    brandName,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            Row(
                Modifier.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                Text("本机加密保存", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun JianyuNavigation(current: AppRoute, formBusy: Boolean, navigate: (AppRoute) -> Unit) {
    val items = listOf(
        Triple(AppRoute.TODAY, "此刻", Icons.Outlined.Explore),
        Triple(AppRoute.CHILDREN, "家庭", Icons.Outlined.FamilyRestroom),
        Triple(AppRoute.TIMELINE, "足迹", Icons.Outlined.Timeline),
        Triple(AppRoute.SETTINGS, "设置", Icons.Outlined.Settings),
    )
    NavigationBar(
        modifier = Modifier.testTag("jianyu-navigation"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        items.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = current == route,
                enabled = !formBusy,
                onClick = { navigate(route) },
                icon = { androidx.compose.material3.Icon(icon, contentDescription = null) },
                label = {
                    Text(
                        label,
                        fontWeight = if (current == route) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
internal fun VaultOpenFailureScreen(message: String?, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = JianyuLayout.screenHorizontal, vertical = JianyuLayout.screenVertical),
                verticalArrangement = Arrangement.spacedBy(JianyuLayout.sectionGap),
            ) {
                JianyuPageHeader(
                    section = "资料与设备",
                    title = "家庭资料暂时打不开",
                    description = "为保护原有资料，这里不会新建家庭或覆盖内容。",
                )
                JianyuCard(tone = JianyuCardTone.HUMAN) {
                    Text(message ?: "本机家庭资料没有正常打开。请勿卸载或清除应用。")
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试打开") }
            }
        }
    }
}

@Composable
internal fun OnboardingScreen(
    brandName: String,
    hero: String,
    mission: String,
    saving: Boolean = false,
    error: String? = null,
    onSetup: (String, String, String, String) -> Unit,
) {
    var showSetup by remember { mutableStateOf(false) }
    var familyName by remember { mutableStateOf("") }
    var caregiverName by remember { mutableStateOf("") }
    var childName by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    // The introduction and the form are different pages: entering either starts
    // at its heading instead of inheriting the previous page's scroll position.
    val pageScrollState = remember(showSetup) { ScrollState(0) }
    val valid = familyName.isNotBlank() && caregiverName.isNotBlank() && childName.isNotBlank() &&
        birthDate.isNotBlank()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(pageScrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!showSetup) {
                    Spacer(Modifier.height(20.dp))
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            JianyuMark(tint = MaterialTheme.colorScheme.primary)
                            Text("给家庭的 AI 机会助手", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(brandName, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(hero, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        mission,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "从孩子主动提起的一件事开始。写下一句；连接你选择的 AI 后，它会帮你寻找几扇不同的门。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showSetup = true },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("开始建立家庭资料")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                    }
                    Text(
                        "第 1 步，共 2 步 · 不需要绑定账号",
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "日后每次寻找，都由你决定提供哪些线索。现在不会发送家庭资料。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OnboardingPromise(
                        title = "AI 发现，家庭决定",
                        body = "它提供少量自然入口，不替家长下结论；孩子可以拒绝，你们也可以这次什么都不做。",
                        emphasized = true,
                    )
                    OnboardingPromise(
                        title = "不把兴趣变成指标",
                        body = "没有每日打卡、连续天数、排名或“全面发展”清单。真实体验比完成任务重要。",
                    )
                    OnboardingPromise(
                        title = "资料先留在自己手里",
                        body = "家庭记录进入本机加密保险箱；只有你逐次确认后，最少的当次信息才会发给自己连接的 AI。",
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { showSetup = false }, enabled = !saving) { Text("← 返回上一页") }
                    JianyuPageHeader(
                        section = "第 2 步，共 2 步",
                        title = "先认识你们",
                        description = "记下家庭称呼、成员称呼和生日；资料留在本机。",
                    )
                    JianyuCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(familyName, { familyName = it }, label = { Text("家庭称呼") }, placeholder = { Text("例如：我们的家") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                            OutlinedTextField(caregiverName, { caregiverName = it }, label = { Text("你的称呼") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                            OutlinedTextField(childName, { childName = it }, label = { Text("孩子的称呼") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                            BirthdayField(
                                birthDate = birthDate,
                                onBirthDateChange = { birthDate = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving,
                            )
                        }
                    }
                    error?.let { message ->
                        JianyuInset(tone = JianyuCardTone.HUMAN) {
                            Text(message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(
                        onClick = { onSetup(familyName, caregiverName, childName, birthDate) },
                        enabled = valid && !saving,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text(if (saving) "正在保存…" else "保存家庭资料")
                        if (!saving) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            "资料只在本机。\n两位家长需共用设备。\n恢复包不会自动同步。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OnboardingPromise(title: String, body: String, emphasized: Boolean = false) {
    JianyuCard(
        tone = if (emphasized) JianyuCardTone.DISCOVERY else JianyuCardTone.NEUTRAL,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (emphasized) Icons.Rounded.AutoAwesome else Icons.Rounded.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JianyuCardTitle(title)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
