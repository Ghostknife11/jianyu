@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.jianyu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.jianyu.app.MainUiState
import org.jianyu.app.MainViewModel
import org.jianyu.app.FamilyFormKind
import org.jianyu.app.canAuthorCaregiverActions
import org.jianyu.core.domain.ageAt
import org.jianyu.core.domain.canRecordScoredAssessment
import org.jianyu.core.domain.lifecycleStage
import org.jianyu.core.domain.FeedbackProvenance
import org.jianyu.core.domain.feedbackProvenance
import org.jianyu.core.domain.isLegacyDemoChoice
import org.jianyu.core.domain.isLegacyDemoEvidence
import org.jianyu.core.domain.isLegacyDemoHypothesis
import org.jianyu.core.domain.normalizeAssessmentEntry
import org.jianyu.core.domain.projectSharedTimeline
import org.jianyu.core.model.Child
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Hypothesis
import org.jianyu.core.model.LifecycleStage
import java.time.LocalDate

@Composable
internal fun ChildrenScreen(state: MainUiState, viewModel: MainViewModel) {
    val family = state.family ?: return
    var name by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var showAddChild by remember { mutableStateOf(false) }
    var caregiverName by remember { mutableStateOf("") }
    var showAddCaregiver by remember { mutableStateOf(false) }
    var showAssessment by remember { mutableStateOf(false) }
    var pendingFormSave by remember { mutableStateOf<FamilyFormKind?>(null) }
    var pendingFromRevision by remember { mutableStateOf(0L) }
    val familyFormBusy = state.familyFormSaving != null
    var expandedChildId by remember(family.household.id) { mutableStateOf<String?>(null) }
    val caregiverMembers = family.members.filter { it.canAuthorCaregiverActions() }
    LaunchedEffect(state.familyFormSaveRevision, state.lastSavedFamilyForm, pendingFormSave, pendingFromRevision) {
        if (state.familyFormSaveRevision > pendingFromRevision && state.lastSavedFamilyForm == pendingFormSave) {
            when (pendingFormSave) {
                FamilyFormKind.CHILD -> {
                    showAddChild = false
                    name = ""
                    birthDate = ""
                }
                FamilyFormKind.CAREGIVER -> {
                    showAddCaregiver = false
                    caregiverName = ""
                }
                FamilyFormKind.ASSESSMENT -> showAssessment = false
                null -> Unit
            }
            pendingFormSave = null
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = JianyuLayout.screenHorizontal,
            vertical = JianyuLayout.screenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(JianyuLayout.sectionGap),
    ) {
        item {
            JianyuPageHeader(
                section = "家庭",
                title = "家庭中的人",
                description = "记下各自的说法，先不下结论。",
            )
        }
        item {
            JianyuSectionHeader(
                title = "阶段与决定权",
                description = "年龄影响安全边界和决定权，不评能力。",
            )
        }
        items(family.children, key = { it.id }) { child ->
            ChildStageCard(
                child = child,
                selected = child.id == state.selectedChildId,
                expanded = child.id == expandedChildId,
                onToggle = { expandedChildId = if (expandedChildId == child.id) null else child.id },
            )
        }
        if (!showAddChild) item {
            OutlinedButton(onClick = { showAddChild = true }, enabled = !familyFormBusy, modifier = Modifier.fillMaxWidth()) {
                Text("添加孩子")
            }
        }
        if (showAddChild) item {
            val saving = state.familyFormSaving == FamilyFormKind.CHILD
            JianyuCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        JianyuCardTitle("添加孩子")
                        TextButton(
                            onClick = { showAddChild = false; name = ""; birthDate = ""; pendingFormSave = null },
                            enabled = !familyFormBusy,
                        ) { Text("取消") }
                    }
                    OutlinedTextField(
                        name, { name = it.take(80) },
                        label = { Text("称呼") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !familyFormBusy,
                    )
                    BirthdayField(
                        birthDate = birthDate,
                        onBirthDateChange = { birthDate = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !familyFormBusy,
                    )
                    Button(
                        onClick = {
                            if (viewModel.addChild(name, birthDate)) {
                                pendingFromRevision = state.familyFormSaveRevision
                                pendingFormSave = FamilyFormKind.CHILD
                            }
                        },
                        enabled = name.isNotBlank() && birthDate.isNotBlank() && !familyFormBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (saving) "正在保存…" else "添加孩子") }
                }
            }
        }
        item {
            JianyuCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    JianyuCardTitle("新记录由谁署名")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        caregiverMembers.forEach { member ->
                            JianyuChoiceChip(
                                selected = member.id == state.activeMemberId,
                                onClick = { viewModel.selectActiveMember(member.id) },
                                label = member.displayName,
                                enabled = !familyFormBusy,
                            )
                        }
                    }
                    Text(
                        "只决定新记录的署名；不验证共享设备上的身份，也不改动过去的记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!showAddCaregiver) item {
            OutlinedButton(onClick = { showAddCaregiver = true }, enabled = !familyFormBusy, modifier = Modifier.fillMaxWidth()) {
                Text("添加家长或监护人")
            }
        }
        if (showAddCaregiver) item {
            val saving = state.familyFormSaving == FamilyFormKind.CAREGIVER
            JianyuCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        JianyuCardTitle("添加家长或监护人")
                        TextButton(
                            onClick = { showAddCaregiver = false; caregiverName = ""; pendingFormSave = null },
                            enabled = !familyFormBusy,
                        ) { Text("取消") }
                    }
                    OutlinedTextField(
                        caregiverName,
                        { caregiverName = it.take(80) },
                        label = { Text("称呼") },
                        supportingText = { Text("家长和监护人当前都按家长角色记录；这里只决定新记录的署名，不验证身份。") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !familyFormBusy,
                    )
                    Button(
                        onClick = {
                            if (viewModel.addCaregiver(caregiverName)) {
                                pendingFromRevision = state.familyFormSaveRevision
                                pendingFormSave = FamilyFormKind.CAREGIVER
                            }
                        },
                        enabled = caregiverName.isNotBlank() && !familyFormBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (saving) "正在保存…" else "添加家长或监护人") }
                }
            }
        }
        val assessmentChildren = family.children.filter { canRecordScoredAssessment(lifecycleStage(it)) }
        if (assessmentChildren.isNotEmpty()) {
            item {
                JianyuSectionHeader(
                    title = "学校记录（可选）",
                    description = "7–15 岁可记录一次考试或测验；分数不代表孩子能力，也不能证明此前的选择带来了变化。",
                )
            }
            if (!showAssessment) item {
                OutlinedButton(onClick = { showAssessment = true }, enabled = !familyFormBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("记录一次考试或测验")
                }
            } else item {
                AssessmentEntryForm(
                    children = assessmentChildren,
                    initialChildId = state.selectedChildId,
                    saving = state.familyFormSaving == FamilyFormKind.ASSESSMENT,
                    busy = familyFormBusy,
                    onCancel = { showAssessment = false; pendingFormSave = null },
                    onSave = { draft ->
                        val started = viewModel.recordAssessment(
                            childId = draft.childId,
                            subject = draft.subject,
                            assessmentKind = draft.assessmentKind,
                            score = draft.score,
                            maximum = draft.maximum,
                            occurredOn = draft.occurredOn,
                            classAverage = draft.classAverage,
                            percentile = draft.percentile,
                            topics = draft.topics,
                            notes = draft.notes,
                            childConfirmed = draft.childConfirmed,
                        )
                        if (started) {
                            pendingFromRevision = state.familyFormSaveRevision
                            pendingFormSave = FamilyFormKind.ASSESSMENT
                        }
                        started
                    },
                )
            }
        }
    }
}

internal data class AssessmentFormDraft(
    val childId: String,
    val subject: String,
    val assessmentKind: String,
    val score: String,
    val maximum: String,
    val occurredOn: String,
    val classAverage: String,
    val percentile: String,
    val topics: String,
    val notes: String,
    val childConfirmed: Boolean,
)

internal fun assessmentDraftValidationMessage(
    child: Child,
    draft: AssessmentFormDraft,
    today: LocalDate = LocalDate.now(),
): String? {
    val earliestDate = child.birthDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.of(child.birthYear, 1, 1)
    return runCatching {
        normalizeAssessmentEntry(
            subject = draft.subject,
            assessmentKind = draft.assessmentKind,
            score = draft.score,
            maximum = draft.maximum,
            occurredOn = draft.occurredOn,
            classAverage = draft.classAverage,
            percentile = draft.percentile,
            topics = draft.topics,
            notes = draft.notes,
            earliestDate = earliestDate,
            today = today,
        )
    }.exceptionOrNull()?.let { error ->
        if (error is IllegalArgumentException) error.message ?: "请检查这次学校记录"
        else "请检查这次学校记录"
    }
}

@Composable
private fun AssessmentEntryForm(
    children: List<Child>,
    initialChildId: String?,
    saving: Boolean,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: (AssessmentFormDraft) -> Boolean,
) {
    var childId by remember(children.map { it.id }) {
        mutableStateOf(initialChildId?.takeIf { candidate -> children.any { it.id == candidate } } ?: children.first().id)
    }
    var subject by remember { mutableStateOf("") }
    var assessmentKind by remember { mutableStateOf("") }
    var score by remember { mutableStateOf("") }
    var maximum by remember { mutableStateOf("100") }
    var occurredOn by remember { mutableStateOf("") }
    var classAverage by remember { mutableStateOf("") }
    var percentile by remember { mutableStateOf("") }
    var topics by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showOptional by remember { mutableStateOf(false) }
    var childConfirmed by remember { mutableStateOf(false) }
    val child = children.first { it.id == childId }
    val stage = lifecycleStage(child)
    val draft = AssessmentFormDraft(
        childId, subject, assessmentKind, score, maximum, occurredOn,
        classAverage, percentile, topics, notes, childConfirmed,
    )
    val requiredComplete = subject.isNotBlank() && assessmentKind.isNotBlank() && score.isNotBlank() &&
        maximum.isNotBlank() && occurredOn.isNotBlank()
    val validationMessage = if (requiredComplete) assessmentDraftValidationMessage(child, draft) else null
    val canSave = requiredComplete && validationMessage == null && (stage != LifecycleStage.HAND_OVER || childConfirmed)

    JianyuCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                JianyuCardTitle("记录一次考试或测验")
                TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            }
            if (children.size > 1) {
                Text("关于谁", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    children.forEach { candidate ->
                        JianyuChoiceChip(
                            selected = candidate.id == childId,
                            onClick = { childId = candidate.id; childConfirmed = false },
                            label = candidate.displayName,
                            enabled = !busy,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it.take(80) },
                label = { Text("科目") },
                placeholder = { Text("例如：数学") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                value = assessmentKind,
                onValueChange = { assessmentKind = it.take(80) },
                label = { Text("考试或测验名称") },
                placeholder = { Text("例如：期中考试、单元测验") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
            )
            AssessmentDateField(
                occurredOn = occurredOn,
                onOccurredOnChange = { occurredOn = it },
                earliestDate = child.birthDate,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = score,
                    onValueChange = { score = it.take(12) },
                    label = { Text("得分") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = maximum,
                    onValueChange = { maximum = it.take(12) },
                    label = { Text("满分") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !busy,
                )
            }
            JianyuDisclosureToggle(
                expanded = showOptional,
                onClick = { showOptional = !showOptional },
                collapsedLabel = "查看可选学校信息",
                expandedLabel = "收起可选学校信息",
                enabled = !busy,
            )
            if (showOptional) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = classAverage,
                        onValueChange = { classAverage = it.take(12) },
                        label = { Text("班级平均分") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !busy,
                    )
                    OutlinedTextField(
                        value = percentile,
                        onValueChange = { percentile = it.take(12) },
                        label = { Text("百分位") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                OutlinedTextField(
                    value = topics,
                    onValueChange = { topics = it.take(300) },
                    label = { Text("涉及知识点") },
                    supportingText = { Text("多个知识点可用逗号分开") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(500) },
                    label = { Text("备注") },
                    placeholder = { Text("例如：试卷难度、教师反馈或当时情况") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
            if (stage == LifecycleStage.HAND_OVER) {
                JianyuCard(tone = JianyuCardTone.HUMAN) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = childConfirmed, onCheckedChange = { childConfirmed = it }, enabled = !busy)
                        Text("孩子本人同意把这次学校记录留进家庭足迹。", modifier = Modifier.weight(1f))
                    }
                }
            }
            Text(
                "分数只是一次来源明确的学校记录，不代表孩子能力，也不会被合成为“成长分”或用于跨孩子比较。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            validationMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onSave(draft) },
                enabled = canSave && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saving) "正在保存…" else "保存这次学校记录") }
        }
    }
}

@Composable
internal fun TimelineScreen(
    family: FamilyState,
    feedbackSavingChoiceId: String?,
    choiceDeletingId: String? = null,
    onCaregiverFeedback: (String, String) -> Unit,
    onChildFeedback: (String, String) -> Unit,
    onCorrectEvidence: (String, String) -> Unit,
    onDeleteEvidence: (String) -> Unit,
    onDeleteChoice: (String, Boolean) -> Unit = { _, _ -> },
) {
    val timeline = remember(family) { projectSharedTimeline(family) }
    val realChoices = remember(timeline, family.events) { timeline.choices.filterNot { isLegacyDemoChoice(it, family.events) } }
    val demoChoices = remember(timeline, family.events) { timeline.choices.filter { isLegacyDemoChoice(it, family.events) } }
    val realEvidence = remember(timeline, family.events) { timeline.evidence.filterNot { isLegacyDemoEvidence(it, family.events) } }
    val demoEvidence = remember(timeline, family.events) { timeline.evidence.filter { isLegacyDemoEvidence(it, family.events) } }
    val realHypotheses = remember(timeline, family.events) {
        timeline.hypotheses.filterNot { isLegacyDemoHypothesis(it, family.evidence, family.events) }
    }
    val hasVisibleRealContent = realChoices.isNotEmpty() || realEvidence.isNotEmpty() || realHypotheses.isNotEmpty()
    val demoHypotheses = remember(timeline, family.events) {
        timeline.hypotheses.filter { isLegacyDemoHypothesis(it, family.evidence, family.events) }
    }
    val childNames = family.children.associate { it.id to it.displayName }
    val adultSubjectIds = family.children
        .filter { lifecycleStage(it) == LifecycleStage.GRADUATION }
        .mapTo(mutableSetOf()) { it.id }
    val adultMemberIds = family.children
        .filter { it.id in adultSubjectIds }
        .mapTo(mutableSetOf()) { it.memberId }
    val memberLabels = family.members.associate { member ->
        member.id to "${member.displayName} · ${member.role.asMemberRoleLabel(member.id in adultMemberIds)}"
    }
    val choiceAuthorLabels = timeline.events
        .filter { event -> event.eventType in setOf("opportunity.chosen", "opportunity.child-vetoed", "opportunity.nothing-chosen") }
        .mapNotNull { event -> event.payload["choiceId"]?.let { choiceId -> choiceId to (memberLabels[event.authorId] ?: "来源作者未知") } }
        .toMap()
    var showAuditEvents by remember { mutableStateOf(false) }
    var showLegacyDemoRecords by remember { mutableStateOf(false) }
    var showPrivacyBoundary by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("timeline-list"),
        contentPadding = PaddingValues(
            horizontal = JianyuLayout.screenHorizontal,
            vertical = JianyuLayout.screenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(JianyuLayout.sectionGap),
    ) {
        item {
            JianyuPageHeader(
                section = "足迹",
                title = "走过的路",
                description = "回看选择和线索，不打卡、不评分。",
            )
        }
        if (timeline.hasRestrictedRecords) {
            item {
                JianyuCard {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        JianyuCardTitle("共享足迹不显示部分记录")
                        Text(
                            "这些记录不在共享页面显示；持有家庭密钥或恢复包的人仍可能读取。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        JianyuDisclosureToggle(
                            expanded = showPrivacyBoundary,
                            onClick = { showPrivacyBoundary = !showPrivacyBoundary },
                            collapsedLabel = "查看隐私边界",
                            expandedLabel = "收起隐私边界",
                        )
                        if (showPrivacyBoundary) {
                            Text(
                                "当前只做到界面隔离，还没有给每个人独立的身份与密钥，因此不等于只有本人能解密。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (!hasVisibleRealContent && !timeline.hasRestrictedRecords) {
            item {
                EmptyState(
                    if (timeline.events.isNotEmpty()) "还没有留下选择或线索" else "这里还没有家庭足迹",
                    when {
                        timeline.events.isNotEmpty() && family.children.any { lifecycleStage(it) != LifecycleStage.GRADUATION } ->
                            "不必专门留下记录。\n本机操作记录可在下方查看。"
                        timeline.events.isNotEmpty() ->
                            "资料由本人决定去留。\n本机操作记录可在下方查看。"
                        family.children.any { lifecycleStage(it) != LifecycleStage.GRADUATION } ->
                            "不必专门留下记录。\n孩子有兴趣时，再来看看。"
                        else -> "不必专门留下记录。\n资料由本人决定去留。"
                    },
                )
            }
        }
        if (realChoices.isNotEmpty()) {
            item {
                JianyuSectionHeader("选择与拒绝")
            }
            items(realChoices.asReversed(), key = FamilyChoice::id) { choice ->
                val stage = family.children.firstOrNull { it.id == choice.childId }?.let(::lifecycleStage)
                ChoiceCard(
                    choice = choice,
                    childName = childNames[choice.childId] ?: "孩子",
                    authorLabel = choiceAuthorLabels[choice.id] ?: "早期记录未保留署名",
                    onFeedback = if (stage == LifecycleStage.HAND_OVER) onChildFeedback else onCaregiverFeedback,
                    feedbackStage = stage,
                    feedbackProvenance = feedbackProvenance(choice, family.events),
                    feedbackBusy = feedbackSavingChoiceId != null || choiceDeletingId != null,
                    savingThisChoice = feedbackSavingChoiceId == choice.id,
                    deletingThisChoice = choiceDeletingId == choice.id,
                    onDeleteChoice = onDeleteChoice,
                )
            }
        }
        if (realEvidence.isNotEmpty()) {
            item {
                JianyuSectionHeader(
                    title = "留下的线索",
                    description = "来自孩子、学校、生活与世界；观察与解释不会被压成固定标签。",
                )
            }
            items(realEvidence.asReversed(), key = Evidence::id) { evidence ->
                EvidenceRow(
                    evidence = evidence,
                    child = family.children.firstOrNull { it.id == evidence.childId },
                    authorLabel = memberLabels[evidence.authorId] ?: "来源作者未知",
                    onCorrect = onCorrectEvidence,
                    onDelete = onDeleteEvidence,
                )
            }
        }
        if (realHypotheses.isNotEmpty()) {
            item {
                JianyuSectionHeader(
                    title = "暂时的理解",
                    description = "这是根据线索形成、会随时间变旧的推测，不是对任何人的固定结论，也不会覆盖原话。",
                )
            }
            items(realHypotheses.asReversed(), key = Hypothesis::id) { hypothesis ->
                HypothesisCard(
                    hypothesis = hypothesis,
                    childName = childNames[hypothesis.childId] ?: "孩子",
                    adultSubject = family.children.firstOrNull { it.id == hypothesis.childId }
                        ?.let(::lifecycleStage) == LifecycleStage.GRADUATION,
                )
            }
        }
        if (demoChoices.isNotEmpty() || demoEvidence.isNotEmpty() || demoHypotheses.isNotEmpty()) {
            item {
                JianyuSectionHeader(
                    title = "旧版演示记录",
                    description = "这些只是旧版演示，不代表真实想法；以后 AI 找入口也不会参考。",
                )
            }
            item {
                JianyuDisclosureToggle(
                    expanded = showLegacyDemoRecords,
                    onClick = { showLegacyDemoRecords = !showLegacyDemoRecords },
                    collapsedLabel = "查看旧版演示记录",
                    expandedLabel = "收起旧版演示记录",
                )
            }
            if (showLegacyDemoRecords) {
                items(demoChoices.asReversed(), key = { "demo-choice-${it.id}" }) { choice ->
                    ChoiceCard(
                        choice = choice,
                        childName = childNames[choice.childId] ?: "孩子",
                        authorLabel = choiceAuthorLabels[choice.id] ?: "早期记录未保留署名",
                        onFeedback = onCaregiverFeedback,
                        feedbackStage = family.children.firstOrNull { it.id == choice.childId }?.let(::lifecycleStage),
                        feedbackProvenance = FeedbackProvenance.UNKNOWN,
                        feedbackBusy = feedbackSavingChoiceId != null || choiceDeletingId != null,
                        savingThisChoice = false,
                        deletingThisChoice = choiceDeletingId == choice.id,
                        onDeleteChoice = onDeleteChoice,
                        legacyDemo = true,
                    )
                }
                items(demoEvidence.asReversed(), key = { "demo-evidence-${it.id}" }) { evidence ->
                    EvidenceRow(
                        evidence = evidence,
                        child = family.children.firstOrNull { it.id == evidence.childId },
                        authorLabel = memberLabels[evidence.authorId] ?: "来源作者未知",
                        onCorrect = onCorrectEvidence,
                        onDelete = onDeleteEvidence,
                        legacyDemo = true,
                    )
                }
                items(demoHypotheses.asReversed(), key = { "demo-hypothesis-${it.id}" }) { hypothesis ->
                    HypothesisCard(
                        hypothesis = hypothesis,
                        childName = childNames[hypothesis.childId] ?: "孩子",
                        adultSubject = family.children.firstOrNull { it.id == hypothesis.childId }
                            ?.let(::lifecycleStage) == LifecycleStage.GRADUATION,
                        legacyDemo = true,
                    )
                }
            }
        }
        if (timeline.events.isNotEmpty()) {
            item {
                JianyuDisclosureToggle(
                    expanded = showAuditEvents,
                    onClick = { showAuditEvents = !showAuditEvents },
                    collapsedLabel = "查看本机操作记录",
                    expandedLabel = "收起本机操作记录",
                )
            }
            if (showAuditEvents) {
                items(timeline.events.asReversed(), key = FamilyEvent::eventId) { event ->
                    EventRow(event, adultSubject = event.subjectId in adultSubjectIds)
                }
            }
        }
    }
}

@Composable
private fun EvidenceRow(
    evidence: Evidence,
    child: Child?,
    authorLabel: String,
    onCorrect: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    legacyDemo: Boolean = false,
) {
    var showCorrection by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf("") }
    val adultSubject = child?.let(::lifecycleStage) == LifecycleStage.GRADUATION
    JianyuCard(contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (legacyDemo) {
                Text("旧版离线演示输入", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(evidence.stream.asStreamLabel(adultSubject), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(evidence.kind.asEvidenceKindLabel(adultSubject), style = MaterialTheme.typography.labelSmall)
            }
            Text(evidence.expression)
            Text(
                "关于 ${child?.displayName ?: "孩子"} · $authorLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val occurredLabel = if (evidence.kind == EvidenceKind.ASSESSMENT) {
                formatCalendarDate(evidence.occurredAt)
            } else {
                formatInstant(evidence.occurredAt)
            }
            JianyuSupportingText(occurredLabel)
            JianyuSupportingText("${evidence.visibility.asVisibilityLabel()} · ${evidence.source.asSourceLabel(adultSubject)}")
            if (evidence.source.startsWith("child-correction:")) {
                Text(
                    if (adultSubject) "本人的纠正 · 原记录仍保留" else "孩子的纠正 · 原记录仍保留",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!legacyDemo && !evidence.source.startsWith("child-correction:") && child != null && child.ageAt() >= 7) {
                    TextButton(onClick = { showCorrection = true }) {
                        Text(if (adultSubject) "本人说：这条不对" else "孩子说：这条不对")
                    }
                }
                JianyuDangerTextButton(onClick = { showDelete = true }) { Text("删除记录") }
            }
        }
    }
    if (showCorrection) {
        AlertDialog(
            onDismissRequest = { showCorrection = false },
            title = { Text(if (adultSubject) "保留本人的说法" else "保留孩子自己的说法") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "共享设备不能验证是谁在输入，请先让${if (adultSubject) "本人" else "孩子"}确认。原记录会保留，两种说法都有作者和时间。",
                    )
                    OutlinedTextField(
                        value = correction,
                        onValueChange = { correction = it },
                        label = { Text(if (adultSubject) "本人怎么说？" else "孩子怎么说？") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = correction.isNotBlank(),
                    onClick = {
                        onCorrect(evidence.id, correction)
                        correction = ""
                        showCorrection = false
                    },
                ) { Text("保留这次纠正") }
            },
            dismissButton = { TextButton(onClick = { showCorrection = false }) { Text("取消") } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("原文会立即从家庭足迹中移除，并留下一个不含原文的删除标记，防止未来同步时被旧副本重新带回。") },
            confirmButton = {
                JianyuDangerTextButton(onClick = {
                    onDelete(evidence.id)
                    showDelete = false
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun HypothesisCard(
    hypothesis: Hypothesis,
    childName: String,
    adultSubject: Boolean,
    legacyDemo: Boolean = false,
) {
    JianyuCard(tone = JianyuCardTone.NEUTRAL) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    if (legacyDemo) "旧版演示相关推测" else "AI 的暂时推测",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!legacyDemo) Text(hypothesis.confidence.asHypothesisConfidenceLabel(), style = MaterialTheme.typography.labelMedium)
            }
            JianyuCardTitle(hypothesis.statement)
            JianyuSupportingText("关于 $childName")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                JianyuSupportingText("${hypothesis.supports.size} 条支持线索")
                JianyuSupportingText("${hypothesis.contradicts.size} 条相反线索")
            }
            Text(
                if (legacyDemo) "关联过演示输入，仅供查看旧记录，不应视为对${if (adultSubject) "本人" else "孩子"}的真实判断。"
                else "这条理解会随新证据和时间变化；约 ${hypothesis.halfLifeDays} 天后，旧线索的影响应明显减弱。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "形成于 ${formatInstant(hypothesis.derivedAt)} · 生成来源和规则版本已保存在本机记录中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    choice: FamilyChoice,
    childName: String,
    authorLabel: String,
    onFeedback: (String, String) -> Unit,
    feedbackStage: LifecycleStage?,
    feedbackProvenance: FeedbackProvenance,
    feedbackBusy: Boolean,
    savingThisChoice: Boolean,
    deletingThisChoice: Boolean,
    onDeleteChoice: (String, Boolean) -> Unit,
    legacyDemo: Boolean = false,
) {
    var showRecordActions by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val subjectDecides = feedbackStage == LifecycleStage.HAND_OVER || feedbackStage == LifecycleStage.GRADUATION
    JianyuCard {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(childName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (legacyDemo) "演示点选" else choice.status.asStatus(feedbackStage == LifecycleStage.GRADUATION), style = MaterialTheme.typography.labelMedium)
            }
            JianyuCardTitle(choice.opportunity.title)
            JianyuSupportingText("记录署名：$authorLabel")
            JianyuSupportingText(formatInstant(choice.chosenAt))
            if (legacyDemo) {
                Text(
                    "这是旧版演示操作，不代表当事人真实表态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (choice.feedback != null) Text("演示时填写：${choice.feedback}")
            } else if (choice.feedback == null && choice.status == "chosen") {
                if (feedbackStage == LifecycleStage.GRADUATION || feedbackStage == null) {
                    Text("成年交接后不再新增这位成员的看法。", style = MaterialTheme.typography.bodySmall)
                } else {
                    if (savingThisChoice) Text("正在保存看法…", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (feedbackStage == LifecycleStage.HAND_OVER)
                            "如果你已有看法，可以自己补充；共用设备上的署名不是身份认证。点选不代表已参与。"
                        else "如果孩子已表达看法，家长可代记；不知道就跳过。点选不代表已参与。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("喜欢", "一般", "不合适").forEach { value ->
                            OutlinedButton(
                                onClick = { onFeedback(choice.id, value) },
                                enabled = !feedbackBusy,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) { Text(value) }
                        }
                    }
                }
            } else if (choice.feedback != null) {
                val sourceLabel = when (feedbackProvenance) {
                    FeedbackProvenance.CHILD_SIGNED -> if (feedbackStage == LifecycleStage.GRADUATION) "本人当时署名" else "孩子署名"
                    FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW -> "家长代记"
                    FeedbackProvenance.UNKNOWN -> "旧记录，来源未确认"
                }
                Text("后来的看法（$sourceLabel）：${choice.feedback}")
                Text(
                    if (feedbackProvenance == FeedbackProvenance.UNKNOWN)
                        "来源未确认的旧看法不会进入下一次 AI 摘要。"
                    else "如果下次允许 AI 参考近期足迹，这条有来源的看法可作为线索；相似入口仍可能出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            JianyuDisclosureToggle(
                expanded = showRecordActions,
                onClick = { showRecordActions = !showRecordActions },
                collapsedLabel = "查看记录操作",
                expandedLabel = "收起记录操作",
                enabled = !feedbackBusy,
            )
            if (showRecordActions) {
                if (deletingThisChoice) JianyuSupportingText("正在删除这条选择…")
                JianyuDangerTextButton(
                    onClick = { showDelete = true },
                    enabled = !feedbackBusy && feedbackStage != null,
                ) { Text("删除这条选择") }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条选择？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("会移除这条选择、相关看法及含标题的操作记录，并留下不含内容的删除标记，防止旧副本重新显示。")
                    Text("原来的兴趣线索、已发送给外部服务的信息和旧加密文件副本不会因此清除。")
                    if (subjectDecides) Text("这由本人决定；共用设备不能验证身份。")
                }
            },
            confirmButton = {
                JianyuDangerTextButton(
                    onClick = { onDeleteChoice(choice.id, subjectDecides); showDelete = false },
                    enabled = !feedbackBusy,
                ) { Text(if (subjectDecides) "由我确认删除" else "确认删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

private fun Double.asHypothesisConfidenceLabel() = when {
    this >= 0.8 -> "依据相对多，仍不是事实"
    this >= 0.55 -> "有一些依据"
    else -> "依据较弱"
}

@Composable
private fun EventRow(event: FamilyEvent, adultSubject: Boolean) {
    JianyuInset {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(event.eventType.asEventLabel(adultSubject), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            event.disclosureAuditLines().forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (event.eventType == "assessment.recorded") formatCalendarDate(event.occurredAt) else formatInstant(event.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
