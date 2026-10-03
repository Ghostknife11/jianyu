@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.jianyu.app.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jianyu.app.AppRoute
import org.jianyu.app.MainUiState
import org.jianyu.app.MainViewModel
import org.jianyu.app.DiscoveryComposerDraft
import org.jianyu.app.canAuthorCaregiverActions
import org.jianyu.core.domain.lifecycleStage
import org.jianyu.core.domain.lifecycleAuthority
import org.jianyu.core.domain.ageAt
import org.jianyu.core.domain.currentInterestHasNonRefusalClue
import org.jianyu.app.currentInterestClueMessage
import org.jianyu.core.domain.externalSourceHost
import org.jianyu.core.domain.projectRecentRecommendationContext
import org.jianyu.core.domain.DiscoverySourceIssue
import org.jianyu.core.domain.SourceFailureReason
import org.jianyu.core.domain.GraduationRetentionMode
import org.jianyu.core.model.Child
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.EvaluatedOpportunity
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.TombstoneTarget
import java.util.Locale

@Composable
internal fun TodayScreen(state: MainUiState, viewModel: MainViewModel) {
    val family = state.family ?: return
    val focusManager = LocalFocusManager.current
    if (family.children.isEmpty()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = JianyuLayout.screenHorizontal,
                vertical = JianyuLayout.screenVertical,
            ),
            verticalArrangement = Arrangement.spacedBy(JianyuLayout.sectionGap),
        ) {
            item {
                JianyuPageHeader(
                    section = "此刻",
                    title = "此刻无需安排",
                    description = "家庭资料仍由你掌握，不需要为了使用应用而新增孩子资料。",
                )
            }
            item {
                EmptyState(
                    "当前没有孩子资料",
                    "成年交接后的删除不会被当作异常，也不会要求重新添加。你仍可以在家庭页添加新的家庭成员资料。",
                )
            }
        }
        return
    }
    val selectedChild = family.children.firstOrNull { it.id == state.selectedChildId } ?: family.children.first()
    val belowMinimumAge = selectedChild.ageAt() < 4
    val stage = lifecycleStage(selectedChild)
    val composerCopy = opportunityComposerUiModel(stage, selectedChild.displayName)
    val listState = rememberLazyListState()
    val draft = state.composerDraft?.takeIf { it.childId == selectedChild.id && it.stage == stage }
        ?: DiscoveryComposerDraft(selectedChild.id, stage, persistContext = composerCopy.persistContextByDefault)
    val expression = draft.expression
    val caregiverGoal = draft.caregiverGoal
    val sharedGoal = draft.sharedGoal
    val schoolWindow = draft.schoolWindow
    val lifeContext = draft.lifeContext
    val region = draft.region
    val timeMinutes = draft.timeMinutes
    val travelMinutes = draft.travelMinutes
    val cost = draft.cost
    val energy = draft.energy
    val persistContext = draft.persistContext
    val privateContext = draft.privateContext
    fun changeDraft(change: (DiscoveryComposerDraft) -> DiscoveryComposerDraft) =
        viewModel.updateComposerDraft(selectedChild.id, stage, composerCopy.persistContextByDefault, change)
    var childConfirmed by remember(selectedChild.id) { mutableStateOf(false) }
    var includeRecentContext by remember(selectedChild.id) { mutableStateOf(false) }
    var disclosureApproved by remember(selectedChild.id) { mutableStateOf(false) }
    var useOfflineDemo by remember(selectedChild.id) { mutableStateOf(false) }
    fun invalidateApprovals() {
        childConfirmed = false
        disclosureApproved = false
    }
    var showContextDetails by remember(selectedChild.id) {
        mutableStateOf(
            caregiverGoal.isNotBlank() || sharedGoal.isNotBlank() || schoolWindow.isNotBlank() ||
                lifeContext.isNotBlank() || region.isNotBlank() || timeMinutes != 90f ||
                travelMinutes != 30f || cost != CostBand.LOW || energy != EnergyBand.MEDIUM,
        )
    }
    var showResultDataBoundary by remember(state.sourceEventId) { mutableStateOf(false) }
    var demoPreview by remember(state.sourceEventId) { mutableStateOf<Pair<Opportunity, Boolean>?>(null) }
    var showComposer by remember(selectedChild.id) { mutableStateOf(state.opportunities == null) }
    var showChildSelector by remember(selectedChild.id) { mutableStateOf(false) }
    var sharedDraftNotice by remember(selectedChild.id) { mutableStateOf(false) }
    var graduationConfirmed by remember(selectedChild.id) { mutableStateOf(false) }
    var showGraduationChoices by remember(selectedChild.id) { mutableStateOf(false) }
    var pendingGraduationMode by remember(selectedChild.id) { mutableStateOf<GraduationRetentionMode?>(null) }
    var graduationDeleteConfirmed by remember(selectedChild.id) { mutableStateOf(false) }
    var graduationDeletePhrase by remember(selectedChild.id) { mutableStateOf("") }
    val graduationContentCleared = family.tombstones.any {
        it.targetType == TombstoneTarget.SUBJECT_CONTENT && it.targetId == selectedChild.id
    }
    val graduationExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportGraduationBundle(uri, selectedChild.id, graduationConfirmed)
            graduationConfirmed = false
        }
    }
    val authority = lifecycleAuthority(stage)
    val canSwitchViewedChild = !state.discovering && !state.choiceSaving &&
        state.feedbackSavingChoiceId == null && state.choiceDeletingId == null && !state.disclosureSaveRisk
    val recentContextPreview = remember(family.evidence, family.choices, family.events, selectedChild.id) {
        projectRecentRecommendationContext(
            evidence = family.evidence,
            choices = family.choices,
            childId = selectedChild.id,
            events = family.events,
        ).summaries
    }
    val acknowledgedChoice = state.choiceAcknowledgementId?.let { id ->
        family.choices.firstOrNull { it.id == id }
    }

    LaunchedEffect(selectedChild.id, state.opportunities) {
        if (state.opportunities != null) {
            showComposer = false
            invalidateApprovals()
            includeRecentContext = false
            useOfflineDemo = false
        }
        listState.scrollToItem(0)
    }

    LaunchedEffect(state.incomingShareText) {
        state.incomingShareText?.let { sharedText ->
            changeDraft { it.copy(expression = sharedText.take(800)) }
            showComposer = true
            sharedDraftNotice = true
            invalidateApprovals()
            includeRecentContext = false
            viewModel.consumeIncomingShare()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("today-list"),
        contentPadding = PaddingValues(
            horizontal = JianyuLayout.screenHorizontal,
            vertical = JianyuLayout.screenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(JianyuLayout.sectionGap),
    ) {
        item {
            JianyuPageHeader(
                section = "此刻",
                title = when {
                    belowMinimumAge -> "暂不寻找入口"
                    stage == LifecycleStage.GRADUATION -> "成年交接"
                    state.opportunities != null && !showComposer ->
                        if (state.discoveryMode == "offline-demo") "离线演示" else "这次的发现"
                    else -> composerCopy.title
                },
                description = when {
                    belowMinimumAge -> "这位家庭成员还没满 4 岁。资料可以留在本机，不需要现在安排或记录。"
                    stage == LifecycleStage.GRADUATION -> "由本人决定资料如何带走或留在家庭中。"
                    state.opportunities != null && !showComposer -> {
                        val count = state.opportunities.selected.size
                        if (count == 0) emptyDiscoveryDescriptionFor(state, stage)
                        else if (state.discoveryMode == "offline-demo") "这里有 ${if (count == 1) "一张" else "$count 张"}固定演示模板，并非 AI 找到的入口；留白也可以预览。"
                        else if (count == 1) "这次有一个入口，也可以留白。"
                        else "这次有 $count 个入口，也可以留白。"
                    }
                    useOfflineDemo -> composerCopy.demoDescription
                    else -> composerCopy.description
                },
            )
        }
        if (state.disclosureSaveRisk && state.error != null) {
            item {
                DisclosureSaveRiskCard(
                    message = state.error,
                    onDismiss = viewModel::dismissDisclosureSaveRisk,
                )
            }
        }
        if (family.children.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        JianyuTextPill(
                            text = "当前查看：${selectedChild.displayName}",
                            tone = JianyuPillTone.DISCOVERY,
                        )
                        TextButton(
                            onClick = {
                                focusManager.clearFocus()
                                showChildSelector = !showChildSelector
                            },
                            enabled = canSwitchViewedChild,
                        ) {
                            Text(if (showChildSelector) "收起名单" else subjectSwitchLabel(stage))
                        }
                    }
                    val switchPauseReason = when {
                        state.disclosureSaveRisk -> "请先看完上方这次请求提示，再切换。"
                        state.discovering -> "正在整理这次的入口，结束后可切换。"
                        state.choiceSaving -> "正在保存这次选择，完成后可切换。"
                        state.feedbackSavingChoiceId != null -> "正在保存看法，完成后可切换。"
                        else -> null
                    }
                    switchPauseReason?.let { reason ->
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showChildSelector) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            family.children.forEach { child ->
                                JianyuChoiceChip(
                                    selected = child.id == selectedChild.id,
                                    onClick = {
                                        focusManager.clearFocus()
                                        if (child.id == selectedChild.id) showChildSelector = false
                                        else viewModel.selectChild(child.id)
                                    },
                                    label = child.displayName,
                                    enabled = canSwitchViewedChild,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (belowMinimumAge) {
            item {
                EmptyState(
                    "还没到适用年龄",
                    "当前版本从 4 岁开始寻找入口。生日到了以后可以再用；现在不用为此做任何准备。",
                )
            }
        }
        if (!belowMinimumAge) acknowledgedChoice?.let { choice ->
            item {
                ChoiceAcknowledgementCard(
                    choice = choice,
                    stage = stage,
                    busy = state.feedbackSavingChoiceId != null,
                    onFeedback = if (stage == LifecycleStage.HAND_OVER) viewModel::feedbackFromChild else viewModel::feedback,
                    onDismiss = viewModel::dismissChoiceAcknowledgement,
                )
            }
        }
        if (!belowMinimumAge && stage == LifecycleStage.GRADUATION) {
            item {
                JianyuCard(contentPadding = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        JianyuTextPill(text = "成年交接 · 本人掌控", tone = JianyuPillTone.DISCOVERY)
                        JianyuCardTitle("带走我的资料")
                        Text("这里不再新增家长侧记录。你可以导出只含本人资料的加密包。")
                        Text(
                            "不含兄弟姐妹记录。导出不删除家庭副本；后续注册和迁移需由本人另行决定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = graduationConfirmed, onCheckedChange = { graduationConfirmed = it })
                            Text("我是本人，并确认现在导出关于我的资料。")
                        }
                        Button(
                            enabled = graduationConfirmed,
                            onClick = { graduationExportLauncher.launch("family-opportunity-graduation.foe") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("导出我的加密资料包")
                        }
                        JianyuInset(tone = JianyuCardTone.PREVIEW) {
                            Text(
                                "开发预览中的导出：共享设备上的勾选不是身份认证；正式迁移仍需要本人私钥与独立安全审查。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                JianyuCard(contentPadding = PaddingValues(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        JianyuCardTitle("家庭副本")
                        Text(
                            if (graduationContentCleared) "当前只保留最小家庭成员关系；过去的经历已经清空。"
                            else "当前按只读方式保留；这里不会再新增家长侧儿童记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        JianyuDisclosureToggle(
                            expanded = showGraduationChoices,
                            onClick = { showGraduationChoices = !showGraduationChoices },
                            collapsedLabel = "查看家庭副本选项",
                            expandedLabel = "收起家庭副本选项",
                        )
                        if (showGraduationChoices) {
                            Text(
                                "不操作就是继续只读保留。删除权不以先导出为条件，但建议先确认本人是否需要资料包。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!graduationContentCleared) {
                                JianyuDangerOutlinedButton(
                                    onClick = { pendingGraduationMode = GraduationRetentionMode.RELATIONSHIP_ONLY },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("清空经历，只保留家庭关系") }
                            }
                            JianyuDangerTextButton(
                                onClick = { pendingGraduationMode = GraduationRetentionMode.ERASE_SUBJECT },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("从家庭副本删除我的资料") }
                        }
                    }
                }
            }
        }
        if (!belowMinimumAge) discoveryResultItems(
            state = state,
            stage = stage,
            showDataBoundary = showResultDataBoundary,
            onToggleDataBoundary = { showResultDataBoundary = !showResultDataBoundary },
            onNewDiscovery = {
                viewModel.startNewDiscovery(keepDraft = state.discoverySourceIssues.isNotEmpty())
                showComposer = true
                sharedDraftNotice = false
                invalidateApprovals()
                includeRecentContext = false
                useOfflineDemo = false
            },
            onPreviewChoice = { opportunity, vetoed -> demoPreview = opportunity to vetoed },
            onChoose = viewModel::choose,
        )
        if (!belowMinimumAge && authority.allowsNewObservation && (state.opportunities == null || showComposer)) {
            item {
                OpportunityComposer(
                    stage = stage,
                    copy = composerCopy,
                    expression = expression,
                    onExpression = {
                        changeDraft { current -> current.copy(expression = it) }
                        invalidateApprovals()
                    },
                    configured = state.aiConfigured,
                    providerName = state.aiProviderName,
                    worldBriefProviderName = state.worldBriefProviderName,
                    useOfflineDemo = useOfflineDemo,
                    onUseAi = {
                        useOfflineDemo = false
                        invalidateApprovals()
                        includeRecentContext = false
                    },
                    onUseDemo = {
                        useOfflineDemo = true
                        invalidateApprovals()
                        includeRecentContext = false
                    },
                    onOpenSettings = { viewModel.navigate(AppRoute.SETTINGS) },
                    showContextDetails = showContextDetails,
                    onToggleContext = { showContextDetails = !showContextDetails },
                    sharedDraftNotice = sharedDraftNotice,
                )
            }
            if (showContextDetails) {
                item {
                    GoalCard(
                        stage = stage,
                        caregiverGoal = caregiverGoal,
                        sharedGoal = sharedGoal,
                        schoolWindow = schoolWindow,
                        lifeContext = lifeContext,
                        region = region,
                        onCaregiverGoal = {
                            changeDraft { current -> current.copy(caregiverGoal = it) }
                            invalidateApprovals()
                        },
                        onSharedGoal = {
                            changeDraft { current -> current.copy(sharedGoal = it) }
                            invalidateApprovals()
                        },
                        onSchoolWindow = {
                            changeDraft { current -> current.copy(schoolWindow = it) }
                            invalidateApprovals()
                        },
                        onLifeContext = {
                            changeDraft { current -> current.copy(lifeContext = it) }
                            invalidateApprovals()
                        },
                        onRegion = {
                            changeDraft { current -> current.copy(region = it) }
                            invalidateApprovals()
                        },
                    )
                }
                item {
                    ConstraintCard(
                        timeMinutes = timeMinutes,
                        travelMinutes = travelMinutes,
                        cost = cost,
                        energy = energy,
                        onTime = {
                            changeDraft { current -> current.copy(timeMinutes = it) }
                            invalidateApprovals()
                        },
                        onTravel = {
                            changeDraft { current -> current.copy(travelMinutes = it) }
                            invalidateApprovals()
                        },
                        onCost = {
                            changeDraft { current -> current.copy(cost = it) }
                            invalidateApprovals()
                        },
                        onEnergy = {
                            changeDraft { current -> current.copy(energy = it) }
                            invalidateApprovals()
                        },
                    )
                }
            }
            if (expression.isNotBlank() && showContextDetails) {
                item {
                    RequestContextPreview(
                        stage = stage,
                        useOfflineDemo = useOfflineDemo,
                        expression = expression,
                        caregiverGoal = caregiverGoal,
                        sharedGoal = sharedGoal,
                        schoolWindow = schoolWindow,
                        lifeContext = lifeContext,
                        region = region,
                        timeMinutes = timeMinutes.toInt(),
                        travelMinutes = travelMinutes.toInt(),
                        cost = cost,
                        energy = energy,
                        worldBriefProviderName = state.worldBriefProviderName,
                    )
                }
            }
            if (!useOfflineDemo) {
                item {
                    TodayContextRibbon(
                        child = selectedChild,
                        caregiverMembers = family.members.filter { it.canAuthorCaregiverActions() },
                        activeMemberId = state.activeMemberId,
                        onSelectMember = viewModel::selectActiveMember,
                    )
                }
            }
            if (!useOfflineDemo && selectedChild.ageAt() >= 7) {
                item {
                    PersistenceChoice(
                        stage = stage,
                        persistContext = persistContext,
                        privateContext = privateContext,
                        onChange = { persist, privateOnly ->
                            changeDraft { current -> current.copy(
                                persistContext = persist,
                                privateContext = persist && privateOnly,
                            ) }
                            invalidateApprovals()
                        },
                    )
                }
            }
            if (!useOfflineDemo && state.aiConfigured) {
                item {
                    DisclosureCard(
                        approved = disclosureApproved,
                        onApproved = {
                            disclosureApproved = it
                            childConfirmed = false
                        },
                        providerName = state.aiProviderName ?: "已连接的 AI",
                        worldBriefProviderName = state.worldBriefProviderName,
                        childFacing = stage == LifecycleStage.HAND_OVER,
                        recentEvidence = recentContextPreview,
                        includeRecentEvidence = includeRecentContext,
                        onIncludeRecentEvidence = {
                            includeRecentContext = it
                            invalidateApprovals()
                        },
                    )
                }
            }
            if (authority.requiresChildConfirmation) {
                item {
                    JianyuCard(tone = JianyuCardTone.HUMAN, contentPadding = PaddingValues(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = childConfirmed, onCheckedChange = { childConfirmed = it })
                            Text(
                                if (useOfflineDemo) "我知道这只是本机演示，不发送或保存输入；由我决定是否继续预览。"
                                else "我已看过这次的数据选择，同意寻找入口；最后由我决定是否选择。",
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        viewModel.discover(
                            selectedChild.id,
                            expression,
                            caregiverGoal,
                            sharedGoal,
                            schoolWindow,
                            lifeContext,
                            region,
                            FamilyConstraints(timeMinutes.toInt(), cost, energy, travelMinutes.toInt()),
                            childConfirmed,
                            persistContext,
                            includeRecentContext,
                            disclosureApproved,
                            useOfflineDemo,
                            contextVisibility = if (stage == LifecycleStage.HAND_OVER && privateContext) {
                                EvidenceVisibility.CHILD_PRIVATE
                            } else {
                                null
                            },
                        )
                        invalidateApprovals()
                    },
                    enabled = expression.isNotBlank() && currentInterestHasNonRefusalClue(expression) &&
                        (!authority.requiresChildConfirmation || childConfirmed) &&
                        !state.discovering && (useOfflineDemo || state.aiConfigured) &&
                        (useOfflineDemo || disclosureApproved),
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("discover-action"),
                ) {
                    if (state.discovering) {
                        CircularProgressIndicator(Modifier.height(22.dp).width(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (useOfflineDemo) "正在整理离线演示…" else "AI 正在寻找这次的入口…")
                    } else {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (useOfflineDemo) "运行离线演示" else composerCopy.actionLabel)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
    demoPreview?.let { (opportunity, vetoed) ->
        AlertDialog(
            onDismissRequest = { demoPreview = null },
            title = { Text("仅作演示") },
            text = {
                Text(
                    when {
                        vetoed -> "已预览拒绝：${opportunity.title}。这不是孩子的真实意见，没有写入家庭足迹。"
                        opportunity.isNothing -> "已预览留白。没有创建正式选择或家庭足迹。"
                        else -> "已预览选择：${opportunity.title}。没有创建正式选择或家庭足迹。"
                    },
                )
            },
            confirmButton = { TextButton(onClick = { demoPreview = null }) { Text("继续查看") } },
        )
    }
    state.graduationRecoveryCode?.let { recoveryCode ->
        GraduationRecoveryCodeDialog(
            subjectName = state.graduationSubjectName ?: selectedChild.displayName,
            recoveryCode = recoveryCode,
            onDismiss = viewModel::dismissGraduationRecoveryCode,
        )
    }
    pendingGraduationMode?.let { mode ->
        val eraseSubject = mode == GraduationRetentionMode.ERASE_SUBJECT
        AlertDialog(
            onDismissRequest = {
                pendingGraduationMode = null
                graduationDeleteConfirmed = false
                graduationDeletePhrase = ""
            },
            title = { Text(if (eraseSubject) "从家庭副本删除本人资料？" else "清空本人的经历？") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (eraseSubject) {
                            "将删除本人的家庭成员关联、观察、假设、选择和历史事件。只留下不含原文的最小删除标记，防止旧同步副本把资料带回来。"
                        } else {
                            "将删除本人的观察、假设、选择和历史事件，只保留姓名、出生日期及家庭成员关系，以及防止旧同步副本复活内容的最小删除标记。"
                        },
                    )
                    Text("本机活动副本无法撤销此操作；已经单独导出的资料包、AI 服务中的内容和远端旧密文不会被物理擦除。删除标记只会阻止旧密文重新显示。", color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = graduationDeleteConfirmed,
                            onCheckedChange = { graduationDeleteConfirmed = it },
                        )
                        Text("我是本人，并确认这是我的留存决定。")
                    }
                    OutlinedTextField(
                        value = graduationDeletePhrase,
                        onValueChange = { graduationDeletePhrase = it.take(2) },
                        label = { Text("输入“删除”确认") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                JianyuDangerButton(
                    enabled = graduationDeleteConfirmed && graduationDeletePhrase == "删除",
                    onClick = {
                        viewModel.applyGraduationRetentionChoice(selectedChild.id, mode, true)
                        pendingGraduationMode = null
                        graduationDeleteConfirmed = false
                        graduationDeletePhrase = ""
                    },
                ) { Text(if (eraseSubject) "确认删除家庭副本" else "确认清空经历") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingGraduationMode = null
                    graduationDeleteConfirmed = false
                    graduationDeletePhrase = ""
                }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun DisclosureSaveRiskCard(message: String, onDismiss: () -> Unit) {
    JianyuCard(tone = JianyuCardTone.HUMAN) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JianyuCardTitle("这次请求可能已送出")
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

/** The same result surface is rendered in the App and in isolated synthetic UI acceptance. */
internal fun LazyListScope.discoveryResultItems(
    state: MainUiState,
    stage: LifecycleStage,
    showDataBoundary: Boolean,
    onToggleDataBoundary: () -> Unit,
    onNewDiscovery: () -> Unit,
    onPreviewChoice: (Opportunity, Boolean) -> Unit,
    onChoose: (Opportunity, Boolean) -> Unit,
) {
    val set = state.opportunities ?: return
    val isOfflineDemo = state.discoveryMode == "offline-demo"
    val aiFailed = state.discoverySourceIssues.any { it.sourceKind == "byok-llm" }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onNewDiscovery,
                enabled = !state.choiceSaving,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text(if (state.discoverySourceIssues.isNotEmpty()) "调整后再试" else "换个线索")
            }
            JianyuInset(tone = JianyuCardTone.NEUTRAL) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            isOfflineDemo -> "离线演示：未调用 AI，也未发送资料。"
                            aiFailed -> "AI 这次没有完成寻找；下方若有入口，来自其他来源并经过本机检查。"
                            else -> "AI 找入口，本机筛选；${if (stage == LifecycleStage.HAND_OVER) "你" else "你们"}来选。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isOfflineDemo) {
                        Text(
                            "固定模板只替换关键词，本机按现实限制筛选；输入和点选不保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.contextPersisted?.takeUnless { isOfflineDemo }?.let { persisted ->
                        Text(
                            when {
                                !persisted -> "描述不存入长期足迹；外部 AI 可能按其条款处理已发送内容。"
                                state.contextRestricted == true -> "这次描述已保存在本机，但不在共享足迹显示；当前家庭密钥或恢复包持有人仍可能读取。"
                                else -> "这次描述已保留到家庭足迹。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.disclosureIncluded.isNotEmpty()) {
                JianyuDisclosureToggle(
                    expanded = showDataBoundary,
                    onClick = onToggleDataBoundary,
                    collapsedLabel = "查看这次的数据边界",
                    expandedLabel = "收起这次的数据边界",
                )
                if (showDataBoundary) {
                    JianyuInset {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            JianyuCardTitle(
                                if (isOfflineDemo) "这次本机可使用的线索" else "这次 AI 服务可接收的线索",
                            )
                            Text(
                                state.disclosureIncluded.joinToString(" · ") { it.asDisclosureLabel() },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                (if (isOfflineDemo) "没有作为独立资料进入这次发现流程：" else "没有作为独立资料提供给 AI：") +
                                    state.disclosureExcluded.joinToString(" · ") { it.asDisclosureLabel() },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!isOfflineDemo) {
                                Text(
                                    "自由输入和已同意发送的近期摘要可能包含你写入的姓名、地址等信息；这些内容不会被可靠地自动识别并删去。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!isOfflineDemo && state.worldBriefConfigured) {
                                Text(
                                    "世界信息服务另按公共查询边界运行，不接收孩子兴趣或家庭生活描述。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (set.selected.any { "recent-intervention-load" in it.warnings }) {
        item { RecentSelectionsNotice(childFacing = stage == LifecycleStage.HAND_OVER) }
    }
    if (set.selected.size > 1) {
        item {
            OpportunityRouteMap(
                set.selected.map { it.opportunity },
                set.nothing,
                stage = stage,
                previewOnly = isOfflineDemo,
            )
        }
    }
    if (state.discoverySourceIssues.isNotEmpty()) {
        item { DiscoverySourceIssuesCard(state.discoverySourceIssues) }
    }
    items(
        items = set.selected,
        key = { evaluated -> evaluated.opportunity.opportunityId },
    ) { evaluated ->
        OpportunityCard(
            evaluated = evaluated,
            stage = stage,
            busy = state.choiceSaving,
            previewOnly = isOfflineDemo,
            choose = { opportunity, vetoed ->
                if (isOfflineDemo) onPreviewChoice(opportunity, vetoed)
                else onChoose(opportunity, vetoed)
            },
        )
    }
    item {
        NothingCard(set.nothing, busy = state.choiceSaving, previewOnly = isOfflineDemo, hasOtherDoors = set.selected.isNotEmpty()) {
            if (isOfflineDemo) onPreviewChoice(set.nothing, false)
            else onChoose(set.nothing, false)
        }
    }
    if (set.rejected.isNotEmpty()) {
        item { GateSummary(set.rejected) }
    }
}

@Composable
private fun RecentSelectionsNotice(childFacing: Boolean) {
    JianyuInset(tone = JianyuCardTone.HUMAN) {
        Text(
            "近 7 天曾选过几个入口，但点选不代表真的参与。${if (childFacing) "你" else "你们"}可以继续看，也可以这次留白。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GraduationRecoveryCodeDialog(
    subjectName: String,
    recoveryCode: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("请由 $subjectName 单独保存恢复码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("恢复码不会写入资料包，也不会再次显示。资料包与恢复码要分开保管；任何同时得到两者的人都能读取其中的个人经历。")
                JianyuCard(contentPadding = PaddingValues(14.dp)) {
                    SelectionContainer {
                        Text(
                            recoveryCode,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    "这次导出没有删除家庭设备上的原记录，也没有授权任何第三方继续使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { clipboard.setText(AnnotatedString(recoveryCode)) }) { Text("复制恢复码") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("我已单独保存") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayContextRibbon(
    child: Child,
    caregiverMembers: List<org.jianyu.core.model.FamilyMember>,
    activeMemberId: String?,
    onSelectMember: (String) -> Unit,
) {
    val age = child.ageAt()
    val stage = lifecycleStage(child)
    JianyuInset(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${child.displayName} · $age 周岁", style = MaterialTheme.typography.labelLarge)
                Text(
                    "· " + when (stage) {
                        LifecycleStage.CO_PLAY -> "共玩 · 一起体验"
                        LifecycleStage.ACCOMPANY -> "陪伴 · 孩子可拒绝"
                        LifecycleStage.CO_SELECT -> "共选 · 共同决定"
                        LifecycleStage.HAND_OVER -> "放权 · 你来决定"
                        LifecycleStage.GRADUATION -> "成年交接 · 本人掌控"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
            if (stage == LifecycleStage.HAND_OVER) {
                Text(
                    "如果选择保存，这句话会以你本人署名；共享设备不能核验实际操作者。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("这次由谁记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        caregiverMembers.forEachIndexed { index, member ->
                            JianyuChoiceChip(
                                selected = member.id == activeMemberId || (activeMemberId == null && index == 0),
                                onClick = { onSelectMember(member.id) },
                                label = member.displayName,
                            )
                        }
                    }
                    if (caregiverMembers.size > 1) {
                        Text(
                            "这里只决定署名，不是共享手机上的身份认证。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceAcknowledgementCard(
    choice: FamilyChoice,
    stage: LifecycleStage,
    busy: Boolean,
    onFeedback: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    JianyuCard(
        tone = JianyuCardTone.HUMAN,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JianyuCardTitle(
                when (choice.status) {
                    "child-vetoed" -> if (stage == LifecycleStage.HAND_OVER) "你说不想要——这也是答案" else "孩子说不想要——这也是答案"
                    "nothing" -> if (stage == LifecycleStage.HAND_OVER) "你选择这次留白" else "这次选择留白"
                    else -> if (stage == LifecycleStage.HAND_OVER) "你选了这个入口" else "这次选了一个入口"
                },
            )
            Text(
                when (choice.status) {
                    "child-vetoed" -> childVetoAcknowledgement(stage)
                    "nothing" -> nothingChoiceAcknowledgement(stage)
                    else -> if (stage == LifecycleStage.HAND_OVER) {
                        "${choice.opportunity.title}。不必现在评价；有了明确的看法再说。点选不代表已经参与。"
                    } else {
                        "${choice.opportunity.title}。不必现在评价；等孩子表达了看法，再由家长代记。点选不代表已经参与。"
                    }
                },
            )
            if (choice.status == "chosen") {
                if (busy) Text("正在保存看法…", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (stage == LifecycleStage.HAND_OVER) "我想留个看法（可选）" else "代记孩子的看法（可选）",
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("喜欢", "一般", "不合适").forEach { value ->
                        OutlinedButton(
                            onClick = { onFeedback(choice.id, value) },
                            enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text(value) }
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("以后再说") }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
            }
        }
    }
}

internal fun nothingChoiceAcknowledgement(stage: LifecycleStage): String =
    if (stage == LifecycleStage.HAND_OVER) {
        "这次不安排也可以。等你想继续探索时再说。"
    } else {
        "这次不安排也可以。等孩子有新想法时再说。"
    }

internal fun interestSupportingText(length: Int): String =
    if (length >= 700) "$length/800 · 快到字数上限" else "有线索时写一句就够了"

internal fun childVetoAcknowledgement(stage: LifecycleStage): String {
    val ending = if (stage == LifecycleStage.HAND_OVER) {
        "如果仍出现相似入口，你可以再次说不要。"
    } else {
        "若仍出现相似入口，孩子仍可以说不要。"
    }
    return "这次拒绝会留在家庭足迹里。下次若允许 AI 参考近期足迹，这条记录可作为线索；$ending"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpportunityComposer(
    stage: LifecycleStage,
    copy: OpportunityComposerUiModel,
    expression: String,
    onExpression: (String) -> Unit,
    configured: Boolean,
    providerName: String?,
    worldBriefProviderName: String?,
    useOfflineDemo: Boolean,
    onUseAi: () -> Unit,
    onUseDemo: () -> Unit,
    onOpenSettings: () -> Unit,
    showContextDetails: Boolean,
    onToggleContext: () -> Unit,
    sharedDraftNotice: Boolean,
) {
    var showVoiceDisclosure by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { onExpression(it.take(800)) }
        }
    }
    JianyuCard(contentPadding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            JianyuTextPill(
                text = copy.stageLabel,
                tone = JianyuPillTone.DISCOVERY,
            )
            OutlinedTextField(
                expression,
                onExpression,
                label = { Text(copy.inputLabel) },
                placeholder = { Text(copy.placeholder) },
                supportingText = { Text(interestSupportingText(expression.length)) },
                minLines = 1,
                modifier = Modifier.fillMaxWidth().testTag("interest-input"),
            )
            if (expression.isNotBlank() && !currentInterestHasNonRefusalClue(expression)) {
                JianyuInset(tone = JianyuCardTone.HUMAN) {
                    Text(
                        currentInterestClueMessage(stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (sharedDraftNotice) {
                JianyuInset(tone = JianyuCardTone.HUMAN) {
                    Text(
                        "来自系统分享 · 现在只是可编辑草稿，尚未保存，也尚未发送给 AI。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            ProviderStatus(
                stage = stage,
                configured = configured,
                providerName = providerName,
                worldBriefProviderName = worldBriefProviderName,
                useOfflineDemo = useOfflineDemo,
                onUseAi = onUseAi,
                onUseDemo = onUseDemo,
                onOpenSettings = onOpenSettings,
            )
            TextButton(
                onClick = {
                    voiceError = null
                    showVoiceDisclosure = true
                },
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("语音转文字")
            }
            voiceError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (stage == LifecycleStage.HAND_OVER) {
                Text(
                    "这一步请由你本人参与。共享设备不能证明现在拿着手机的是谁；真正独立的私密空间仍在建设。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            JianyuDisclosureToggle(
                expanded = showContextDetails,
                onClick = onToggleContext,
                collapsedLabel = "查看现实条件",
                expandedLabel = "收起现实条件",
            )
        }
    }
    if (showVoiceDisclosure) {
        AlertDialog(
            onDismissRequest = { showVoiceDisclosure = false },
            title = { Text("使用系统语音输入？") },
            text = {
                Text(
                    "接下来会打开手机提供的语音识别服务。该服务可能按照系统或服务商的设置处理录音；本应用不保存音频。识别文字回来后只是可编辑草稿，不会自动写入家庭足迹，也不会自动发送给 AI。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVoiceDisclosure = false
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
                            putExtra(RecognizerIntent.EXTRA_PROMPT, copy.inputLabel)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        }
                        runCatching { voiceLauncher.launch(intent) }
                            .onFailure { voiceError = "这台设备没有可用的系统语音识别服务，你仍可以直接输入或从其他应用分享文字。" }
                    },
                ) { Text("打开系统语音") }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceDisclosure = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProviderStatus(
    stage: LifecycleStage,
    configured: Boolean,
    providerName: String?,
    worldBriefProviderName: String?,
    useOfflineDemo: Boolean,
    onUseAi: () -> Unit,
    onUseDemo: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!configured) {
        val setupCopy = aiSourceSetupUiModel(stage)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            JianyuTextPill(
                text = if (useOfflineDemo) "离线演示已选" else "AI 服务尚未设置",
                tone = if (useOfflineDemo) JianyuPillTone.DISCOVERY else JianyuPillTone.NEUTRAL,
            )
            Text(
                if (useOfflineDemo) "这次只在本机查看固定模板，不调用 AI，也不保存演示输入或点选。"
                else setupCopy.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (useOfflineDemo) {
                    OutlinedButton(onClick = onOpenSettings) { Text(setupCopy.actionLabel) }
                } else {
                    Button(onClick = onOpenSettings) { Text(setupCopy.actionLabel) }
                }
                TextButton(onClick = if (useOfflineDemo) onUseAi else onUseDemo) {
                    Text(if (useOfflineDemo) "退出离线演示" else "选择离线演示")
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (useOfflineDemo) {
                JianyuTextPill("离线演示已选", tone = JianyuPillTone.DISCOVERY)
                JianyuSupportingText("固定模板不是 AI 发现，输入和点选不保存")
            } else {
                JianyuSavedConnection(providerName)
                JianyuSupportingText("你确认后才会尝试发送这次需要的内容")
                if (worldBriefProviderName != null) {
                    JianyuSupportingText("世界信息已设置：$worldBriefProviderName")
                }
            }
        }
        TextButton(
            onClick = if (useOfflineDemo) onUseAi else onUseDemo,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(if (useOfflineDemo) "改用 AI" else "选择离线演示")
        }
    }
}

@Composable
private fun RequestContextPreview(
    stage: LifecycleStage,
    useOfflineDemo: Boolean,
    expression: String,
    caregiverGoal: String,
    sharedGoal: String,
    schoolWindow: String,
    lifeContext: String,
    region: String,
    timeMinutes: Int,
    travelMinutes: Int,
    cost: CostBand,
    energy: EnergyBand,
    worldBriefProviderName: String?,
) {
    JianyuCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                JianyuCardTitle("这次会用到哪些信息")
                Text(
                    "这是本机为这一次临时整理的信息，不会变成对孩子的固定结论。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RequestStreamRow(
                    stream = "孩子",
                    value = expression.compactForPreview(),
                    active = true,
                )
                RequestStreamRow(
                    stream = if (stage == LifecycleStage.CO_PLAY) "老师观察" else "学校",
                    value = schoolWindow.compactForPreview().ifBlank {
                        if (stage == LifecycleStage.CO_PLAY) "这次没有补充老师观察" else "这次没有补充学校情况"
                    },
                    active = schoolWindow.isNotBlank(),
                )
                RequestStreamRow(
                    stream = "生活",
                    value = buildString {
                        if (lifeContext.isNotBlank()) append(lifeContext.compactForPreview()).append(" · ")
                        append("$timeMinutes 分钟 · ${cost.asCostLabel()} · ${energy.asEnergyLabel()}")
                        if (travelMinutes > 0) append(" · 最远 $travelMinutes 分钟")
                    },
                    active = true,
                )
                RequestStreamRow(
                    stream = "世界",
                    value = when {
                        useOfflineDemo -> "离线演示不连接世界信息服务"
                        worldBriefProviderName == null -> "世界信息尚未设置；仍可使用 AI 与本机来源"
                        region.isBlank() -> "已设置 $worldBriefProviderName；这次不发送地区"
                        else -> "已设置 $worldBriefProviderName；这次会发送你填写的地区：${region.trim()}"
                    },
                    active = !useOfflineDemo && worldBriefProviderName != null,
                )
            }
            HorizontalDivider()
            Text("这次主要回应谁", style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GoalPreviewRow("孩子", "以上面的主动线索为起点", true)
                GoalPreviewRow("家长", caregiverGoal.compactForPreview().ifBlank { "这次没有添加家长期待" }, caregiverGoal.isNotBlank())
                GoalPreviewRow("共同", sharedGoal.compactForPreview().ifBlank { "这次没有添加共同目标" }, sharedGoal.isNotBlank())
            }
            Text(
                requestContextBoundaryCopy(
                    useOfflineDemo = useOfflineDemo,
                    worldBriefConfigured = worldBriefProviderName != null,
                    regionProvided = region.isNotBlank(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun requestContextBoundaryCopy(
    useOfflineDemo: Boolean,
    worldBriefConfigured: Boolean,
    regionProvided: Boolean,
): String {
    if (useOfflineDemo) {
        return "这次只在本机演示：固定模板只替换关键词，现实条件仍由本机检查；不调用 AI 或世界信息服务，也不保存演示输入或点选。"
    }
    val aiBoundary = "你在下方确认后，才会尝试向 AI 发送这次允许的内容。"
    if (!worldBriefConfigured) return aiBoundary
    val worldBoundary = if (regionProvided) {
        "世界信息请求会带上你填写的地区原文（可能含姓名或地址）、未来 14 天范围、语言和公开类别"
    } else {
        "世界信息请求不带地区，只带未来 14 天范围、语言和公开类别"
    }
    return "$aiBoundary $worldBoundary；不带孩子兴趣或家庭生活描述。"
}

@Composable
private fun RequestStreamRow(stream: String, value: String, active: Boolean) {
    JianyuInset(
        tone = if (active) JianyuCardTone.DISCOVERY else JianyuCardTone.NEUTRAL,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stream,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalPreviewRow(owner: String, value: String, active: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        JianyuTextPill(
            text = owner,
            tone = if (active) JianyuPillTone.HUMAN else JianyuPillTone.NEUTRAL,
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun String.compactForPreview(limit: Int = 72): String {
    val normalized = trim().replace(Regex("\\s+"), " ")
    return if (normalized.length <= limit) normalized else normalized.take(limit - 1).trimEnd() + "…"
}

@Composable
private fun OpportunityRouteMap(
    opportunities: List<Opportunity>,
    nothing: Opportunity,
    stage: LifecycleStage,
    previewOnly: Boolean,
) {
    JianyuCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                JianyuCardTitle(if (previewOnly) "演示中的入口" else "这次看见的门")
                Text(
                    if (previewOnly) "先看几种入口和留白，再读每项细节。"
                    else "先看几种不同入口，再读每项细节；留白同样有效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            opportunities.forEach { item ->
                RouteOverviewEntry(
                    label = item.ecosystem.asEcosystemLabel(),
                    title = item.title,
                    goalLabel = item.primaryGoal.asGoalLabel(stage),
                    tone = JianyuPillTone.DISCOVERY,
                )
            }
            RouteOverviewEntry(label = "留白", title = nothing.title, tone = JianyuPillTone.HUMAN)
        }
    }
}

@Composable
private fun RouteOverviewEntry(label: String, title: String, tone: JianyuPillTone, goalLabel: String? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        JianyuTextPill(text = label, tone = tone)
        Text(title, style = MaterialTheme.typography.bodyMedium)
        goalLabel?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiscoverySourceIssuesCard(issues: List<DiscoverySourceIssue>) {
    val messages = discoverySourceIssueMessages(issues)
    JianyuCard(tone = JianyuCardTone.HUMAN, contentPadding = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            JianyuCardTitle("这次有来源未完成")
            messages.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal fun discoverySourceIssueMessages(issues: List<DiscoverySourceIssue>): List<String> = buildList {
    if (issues.any { it.reasonCode == "outside-approved-scope" }) {
        add("有来源要求的信息超出这次确认的范围，本机已拦下；请检查数据选择后重试。")
    }
    val unavailable = issues.filterNot { it.reasonCode == "outside-approved-scope" }
    unavailable.filter { it.sourceKind == "byok-llm" }.map { it.reasonCode }.distinct().forEach { reason ->
        add(when (reason) {
            SourceFailureReason.RESPONSE_INCOMPLETE.code ->
                "这次 AI 返回的内容没有完整结束，不能作为入口展示；可调整后再试。"
            SourceFailureReason.RESPONSE_TIMED_OUT.code ->
                "等待 AI 服务响应超时，无法确认服务是否已处理请求；重试可能再次计费。这次没有 AI 入口。"
            SourceFailureReason.AUTHENTICATION_REJECTED.code ->
                "AI 服务拒绝了这次请求。请检查设置中的 API 密钥或账户权限；这次没有 AI 入口。"
            SourceFailureReason.RATE_LIMITED.code ->
                "AI 服务暂时限制请求。请稍后再试；这次没有 AI 入口。"
            SourceFailureReason.REQUEST_REJECTED.code ->
                "AI 服务未接受这次请求。请核对模型名称、服务地址与接口兼容性；这次没有 AI 入口。"
            SourceFailureReason.INVALID_RESPONSE.code ->
                "AI 服务有响应，但入口格式无法读取。可换模型或核对接口设置。"
            else -> "这次 AI 服务未完成：可能是网络或服务问题。没有用模板冒充 AI 结果。"
        })
    }
    val worldReasons = unavailable.filter { it.sourceKind == "world-brief" }.map { it.reasonCode }.distinct()
    val actionableWorldReasons = worldReasons.filter { it in setOf(
        SourceFailureReason.RESPONSE_TIMED_OUT.code,
        SourceFailureReason.AUTHENTICATION_REJECTED.code,
        SourceFailureReason.RATE_LIMITED.code,
        SourceFailureReason.REQUEST_REJECTED.code,
        SourceFailureReason.INVALID_RESPONSE.code,
    ) }
    (actionableWorldReasons.ifEmpty { worldReasons.take(1) }).forEach { reason ->
        add(when (reason) {
            SourceFailureReason.RESPONSE_TIMED_OUT.code ->
                "等待世界信息服务响应超时，无法确认服务是否已处理请求；可稍后重试。"
            SourceFailureReason.AUTHENTICATION_REJECTED.code ->
                "世界信息服务拒绝了请求。请检查 API 密钥或服务权限。"
            SourceFailureReason.RATE_LIMITED.code ->
                "世界信息服务暂时限制请求。请稍后再试。"
            SourceFailureReason.REQUEST_REJECTED.code ->
                "世界信息服务未接受这次请求。请核对设置中的服务地址与接口格式。"
            SourceFailureReason.INVALID_RESPONSE.code ->
                "世界信息服务有响应，但信息格式无法读取。请核对接口格式，或更换服务。"
            else -> "这次世界信息服务未完成，可能看不到近期活动；可稍后重试。"
        })
    }
    if (unavailable.any { it.sourceKind == "pack" }) {
        add("这次有一个本地内容包无法读取，可能少了部分入口。")
    }
    if (unavailable.any { it.sourceKind !in setOf("byok-llm", "world-brief", "pack") }) {
        add("这次还有一个扩展来源不可用。")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersistenceChoice(
    stage: LifecycleStage,
    persistContext: Boolean,
    privateContext: Boolean,
    onChange: (persist: Boolean, privateOnly: Boolean) -> Unit,
) {
    if (stage == LifecycleStage.HAND_OVER) {
        JianyuCard(tone = JianyuCardTone.HUMAN) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                JianyuCardTitle("这句话以后要不要保留？")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    JianyuChoiceChip(
                        selected = !persistContext,
                        onClick = { onChange(false, false) },
                        label = "仅用于这次",
                    )
                    JianyuChoiceChip(
                        selected = persistContext && privateContext,
                        onClick = { onChange(true, true) },
                        label = "保存，不在共享足迹显示",
                    )
                    JianyuChoiceChip(
                        selected = persistContext && !privateContext,
                        onClick = { onChange(true, false) },
                        label = "保存到共享足迹",
                    )
                }
                Text(
                    when {
                        !persistContext -> "只参与这次发现，不进入长期记录。"
                        privateContext -> "会保存到家庭保险箱，但不在共享足迹显示。持有家庭密钥或恢复包的人仍可能读取；这不是只有你能解密的私密空间。"
                        else -> "保存作者、时间和来源，并显示在共享足迹。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        JianyuCard(tone = JianyuCardTone.HUMAN, contentPadding = PaddingValues(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = persistContext, onCheckedChange = { onChange(it, false) })
                Column {
                    Text("把这句话留在家庭足迹")
                    Text(
                        if (persistContext) "保存作者、时间和来源，供以后理解变化。" else "只参与这次发现，不进入长期记录。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalCard(
    stage: LifecycleStage,
    caregiverGoal: String,
    sharedGoal: String,
    schoolWindow: String,
    lifeContext: String,
    region: String,
    onCaregiverGoal: (String) -> Unit,
    onSharedGoal: (String) -> Unit,
    onSchoolWindow: (String) -> Unit,
    onLifeContext: (String) -> Unit,
    onRegion: (String) -> Unit,
) {
    JianyuCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            JianyuCardTitle("把不同人的目标分开")
            Text("上面的兴趣属于孩子。下面两项都可留空。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(caregiverGoal, onCaregiverGoal, label = { Text("家长的期待（可选）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sharedGoal, onSharedGoal, label = { Text("共同想做的事（可选）") }, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            JianyuCardTitle("还可以补充三类信息")
            OutlinedTextField(
                schoolWindow,
                onSchoolWindow,
                label = {
                    Text(if (stage == LifecycleStage.CO_PLAY) "老师观察（可选）" else "学校情况（可选）")
                },
                placeholder = {
                    Text(if (stage == LifecycleStage.CO_PLAY) "例如：老师实际看到孩子主动做了什么" else "例如：最近正在学什么")
                },
                supportingText = if (stage == LifecycleStage.CO_PLAY) {
                    { Text("只写老师实际观察到的事，不填分数或发展评分") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(lifeContext, onLifeContext, label = { Text("家庭安排（可选）") }, placeholder = { Text("例如：周末半天、正好要保养汽车") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(region, onRegion, label = { Text("世界 · 地区（可选）") }, supportingText = { Text("正式寻找时，地区可能原样发给 AI 和已设置的世界信息服务；请勿填写姓名或精确地址。") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DisclosureCard(
    approved: Boolean,
    onApproved: (Boolean) -> Unit,
    providerName: String,
    worldBriefProviderName: String?,
    childFacing: Boolean,
    recentEvidence: List<String>,
    includeRecentEvidence: Boolean,
    onIncludeRecentEvidence: (Boolean) -> Unit,
) {
    var showDetails by remember(providerName, worldBriefProviderName) { mutableStateOf(false) }
    JianyuCard(tone = JianyuCardTone.HUMAN, contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = approved, onCheckedChange = onApproved, modifier = Modifier.testTag("ai-disclosure-consent"))
                Column {
                    Text("确认这次可发给 $providerName 的信息", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (worldBriefProviderName == null) {
                            "不会额外附上姓名、精确地址、完整家庭历史或密钥；自由输入中的个人信息仍可能发送，请先检查。"
                        } else {
                            "已设置的世界信息服务也可能收到你填写的地区。不会额外附上姓名、精确地址、完整家庭历史或密钥；自由输入中的个人信息仍可能发送，请先检查。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                "调用你设置的 AI 服务可能产生费用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (recentEvidence.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeRecentEvidence, onCheckedChange = onIncludeRecentEvidence)
                    Column {
                        Text("让 AI 参考最近 ${recentEvidence.size} 条共享足迹")
                        Text(
                            "包括${if (childFacing) "你" else "孩子"}明确拒绝或后来表达的看法；如有近 7 天已选入口次数，也会一并发送，但点选不代表实际参与。默认关闭。不在共享足迹显示的记录和 AI 推测不会进入。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (includeRecentEvidence) {
                    JianyuInset {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("这次将发送的足迹与结果摘要", style = MaterialTheme.typography.labelLarge)
                            recentEvidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            JianyuDisclosureToggle(
                expanded = showDetails,
                onClick = { showDetails = !showDetails },
                collapsedLabel = "查看这次的数据清单",
                expandedLabel = "收起这次的数据清单",
            )
            if (showDetails) {
                Text(
                    if (includeRecentEvidence) {
                        "会发送：上方可见摘要、如有则包括近 7 天已选入口次数、年龄段、这次的兴趣描述、孩子/家长/共同目标、可选学校情况与家庭安排、填写的地区和现实条件。不会额外附上姓名、家庭及成员编号、精确地址、完整历史或密钥；自由输入和摘要中的个人信息仍可能发送。"
                    } else {
                        "会发送：年龄段、这次的兴趣描述、孩子/家长/共同目标、可选学校情况与家庭安排、填写的地区和现实条件。不会额外附上姓名、家庭及成员编号、历史足迹、精确地址、完整家庭历史或密钥；自由输入中的个人信息仍可能发送。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (worldBriefProviderName != null) {
                    Text(
                        "$worldBriefProviderName 只接收填写的地区（请勿填精确地址）、固定未来 14 天窗口、语言和公共类别；它收不到${if (childFacing) "你的" else "孩子的"}兴趣或家庭生活描述，兴趣匹配在本机完成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConstraintCard(
    timeMinutes: Float,
    travelMinutes: Float,
    cost: CostBand,
    energy: EnergyBand,
    onTime: (Float) -> Unit,
    onTravel: (Float) -> Unit,
    onCost: (CostBand) -> Unit,
    onEnergy: (EnergyBand) -> Unit,
) {
    JianyuCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            JianyuCardTitle("这次的现实条件")
            Text("可用时间约 ${timeMinutes.toInt()} 分钟")
            Slider(timeMinutes, onTime, valueRange = 15f..180f, steps = 10)
            Text("最多顺路 ${travelMinutes.toInt()} 分钟")
            Slider(travelMinutes, onTravel, valueRange = 0f..90f, steps = 5)
            Text("预算")
            ChoiceRow(
                values = listOf(CostBand.FREE_EXISTING, CostBand.FREE, CostBand.LOW, CostBand.MEDIUM),
                selected = cost,
                label = { mapOf(CostBand.FREE_EXISTING to "用现有", CostBand.FREE to "免费", CostBand.LOW to "少量", CostBand.MEDIUM to "适中")[it] ?: it.name },
                onSelected = onCost,
            )
            Text("家长精力")
            ChoiceRow(
                values = EnergyBand.entries,
                selected = energy,
                label = { mapOf(EnergyBand.NONE to "没有", EnergyBand.LOW to "较少", EnergyBand.MEDIUM to "一般", EnergyBand.HIGH to "充足")[it]!! },
                onSelected = onEnergy,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelected: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        values.forEach { value ->
            JianyuChoiceChip(selected == value, { onSelected(value) }, label(value))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpportunityCard(
    evaluated: EvaluatedOpportunity,
    stage: LifecycleStage,
    busy: Boolean,
    previewOnly: Boolean,
    choose: (Opportunity, Boolean) -> Unit,
) {
    val item = evaluated.opportunity
    val uriHandler = LocalUriHandler.current
    var showDecisionDetails by remember(item.opportunityId) { mutableStateOf(false) }
    var confirmChildVeto by remember(item.opportunityId) { mutableStateOf(false) }
    var confirmExternalSource by remember(item.opportunityId) { mutableStateOf(false) }
    val sourceHost = externalSourceHost(item.sourceUrl)
    var showFullExplanation by remember(item.opportunityId, item.explanation) { mutableStateOf(false) }
    var explanationOverflows by remember(item.opportunityId, item.explanation) { mutableStateOf(false) }
    JianyuCard(
        contentPadding = PaddingValues(18.dp),
        emphasized = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JianyuTextPill(
                text = if (previewOnly) "演示 · ${item.ecosystem.asEcosystemLabel()}" else "入口 · ${item.ecosystem.asEcosystemLabel()}",
                tone = JianyuPillTone.DISCOVERY,
                emphasized = true,
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            )
            JianyuOpportunityTitle(item.title)
            Text(
                item.explanation,
                maxLines = if (showFullExplanation) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    if (!showFullExplanation) explanationOverflows = layout.didOverflowHeight || layout.didOverflowWidth
                },
            )
            if (explanationOverflows) {
                JianyuDisclosureToggle(
                    expanded = showFullExplanation,
                    onClick = { showFullExplanation = !showFullExplanation },
                    collapsedLabel = "查看完整说明",
                    expandedLabel = "收起说明",
                )
            }
            if (previewOnly) {
                Text(
                    "下面的时间与条件只是模板示意，不要据此安排真实活动。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                JianyuTextPill("约 ${item.requirements.timeMinutes} 分钟")
                JianyuTextPill(item.requirements.costBand.asCostLabel())
                JianyuTextPill(item.requirements.caregiverEnergy.asEnergyLabel())
                if (item.requirements.travelMinutes > 0) JianyuTextPill("路程 ${item.requirements.travelMinutes} 分")
            }
            if (item.bookingRequired) {
                Text(
                    "需要预约或报名，请先在原始来源确认名额。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(item.primaryGoal.asGoalLabel(stage), style = MaterialTheme.typography.labelMedium)
            if (!item.sponsorship.isNullOrBlank() || item.trackingWarning) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (!item.sponsorship.isNullOrBlank()) JianyuTextPill("含赞助披露", JianyuPillTone.DANGER)
                    if (item.trackingWarning) JianyuTextPill("原始链接可能含追踪", JianyuPillTone.DANGER)
                }
            }
            JianyuInset(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        item.verification.asVerificationLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        sourceDisplayLabel(item.sourceKind, item.sourceTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    JianyuDisclosureToggle(
                        expanded = showDecisionDetails,
                        onClick = { showDecisionDetails = !showDecisionDetails },
                        collapsedLabel = "查看判断与来源",
                        expandedLabel = "收起判断与来源",
                    )
                    if (showDecisionDetails) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                        Text("为什么这次可能合适", style = MaterialTheme.typography.labelLarge)
                        Text(item.whyNow, style = MaterialTheme.typography.bodySmall)
                        Text("适合 ${item.minAge}–${item.maxAge} 岁", style = MaterialTheme.typography.bodySmall)
                        if (evaluated.warnings.isNotEmpty()) {
                            Text(
                                "请留意：${evaluated.warnings.joinToString(" · ") { it.asGateLabel() }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        item.sponsorship?.let {
                            Text("赞助披露：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        item.verificationNotes.forEach { note ->
                            Text("需确认：$note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item.retrievedAt?.let {
                            Text(
                                "获取 ${formatInstant(it)}${item.expiresAt?.let { expiry -> " · 有效至 ${formatInstant(expiry)}" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (sourceHost != null) {
                            TextButton(onClick = { confirmExternalSource = true }, contentPadding = PaddingValues(0.dp)) {
                                Text("打开原始来源（外部网页）")
                            }
                        }
                        if (sourceHost == null) {
                            Text(
                                "没有可打开的原始链接，请把这项当作灵感，不要假设具体地点、时间或名额已经核实。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { choose(item, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (previewOnly) "预览选择这个入口"
                        else if (busy) "正在保存这次选择…" else when (stage) {
                            LifecycleStage.CO_PLAY, LifecycleStage.ACCOMPANY -> "一起试试看"
                            LifecycleStage.CO_SELECT -> "一起选这个"
                            LifecycleStage.HAND_OVER -> "我选这个"
                            LifecycleStage.GRADUATION -> "选择"
                        },
                    )
                }
                OutlinedButton(onClick = { confirmChildVeto = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (previewOnly) "预览拒绝这个入口" else if (stage == LifecycleStage.HAND_OVER) "我不想要" else "孩子不想要")
                }
            }
        }
    }
    if (confirmExternalSource && sourceHost != null) {
        AlertDialog(
            onDismissRequest = { confirmExternalSource = false },
            title = { Text("打开外部网页？") },
            text = {
                Text("将离开当前应用，访问 $sourceHost。目标网站会收到你的网络连接信息，也可能使用追踪技术。活动时间、费用和名额仍需到原始页面核实。")
            },
            confirmButton = {
                Button(onClick = {
                    confirmExternalSource = false
                    item.sourceUrl?.let { runCatching { uriHandler.openUri(it) } }
                }) { Text("继续打开") }
            },
            dismissButton = { TextButton(onClick = { confirmExternalSource = false }) { Text("暂不打开") } },
        )
    }
    if (confirmChildVeto) {
        AlertDialog(
            onDismissRequest = { confirmChildVeto = false },
            title = { Text(if (previewOnly) "预览拒绝这个入口？" else if (stage == LifecycleStage.HAND_OVER) "确认不想要这个入口？" else "孩子明确不要这个入口吗？") },
            text = {
                Text(
                    if (previewOnly) {
                        "这里只预览拒绝的操作。不会把它当作孩子的真实意见，也不会写入家庭足迹。"
                    } else if (stage == LifecycleStage.HAND_OVER) {
                        "这是你的拒绝，会留在家庭足迹里；只有下次逐次允许 AI 参考近期足迹时才会参与发现。"
                    } else {
                        "只有孩子明确拒绝时才记为孩子的意见。如果只是家长不想选，可以返回并选择其他入口或留白。拒绝会留在家庭足迹里；下次仍需单独允许 AI 参考。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = { confirmChildVeto = false; choose(item, true) }, enabled = !busy) { Text(if (previewOnly) "预览拒绝" else "确认不要") }
            },
            dismissButton = { TextButton(onClick = { confirmChildVeto = false }) { Text("返回看看") } },
        )
    }
}

@Composable
private fun GateSummary(rejected: List<EvaluatedOpportunity>) {
    var expanded by remember(rejected) { mutableStateOf(false) }
    JianyuCard(contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            JianyuCardTitle("本机排除了 ${rejected.size} 个不合适的入口")
            Text("它们不会出现在上面的选择里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            JianyuDisclosureToggle(
                expanded = expanded,
                onClick = { expanded = !expanded },
                collapsedLabel = "查看原因",
                expandedLabel = "收起原因",
            )
            if (expanded) {
                rejected.take(4).forEach { evaluated ->
                    Text(
                        "• ${evaluated.opportunity.title}：${evaluated.reasons.joinToString("、") { it.asGateLabel() }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NothingCard(item: Opportunity, busy: Boolean, previewOnly: Boolean, hasOtherDoors: Boolean, onChoose: () -> Unit) {
    JianyuCard(
        contentPadding = PaddingValues(18.dp),
        emphasized = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JianyuTextPill(
                text = if (previewOnly) "演示 · 留白" else if (hasOtherDoors) "另一扇门 · 留白" else "留白",
                tone = JianyuPillTone.HUMAN,
                emphasized = true,
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
            )
            JianyuOpportunityTitle(item.title)
            Text(item.explanation)
            Text("不用花钱，也不用额外安排时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onChoose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (previewOnly) "预览留白" else if (busy) "正在保存这次选择…" else "这次就留白")
            }
        }
    }
}

private fun CostBand.asCostLabel() = when (this) {
    CostBand.FREE_EXISTING -> "使用现有物品"
    CostBand.FREE -> "免费"
    CostBand.LOW -> "少量花费"
    CostBand.MEDIUM -> "适中花费"
    CostBand.HIGH -> "较高花费"
}

private fun EnergyBand.asEnergyLabel() = when (this) {
    EnergyBand.NONE -> "不需要家长投入"
    EnergyBand.LOW -> "家长少量参与"
    EnergyBand.MEDIUM -> "家长适度参与"
    EnergyBand.HIGH -> "需要家长充分参与"
}
