@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.jianyu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jianyu.core.domain.lifecycleStage
import org.jianyu.core.domain.ageAt
import org.jianyu.core.model.Child
import org.jianyu.core.model.LifecycleStage

internal data class LifecycleUiModel(
    val ageRange: String,
    val relationship: String,
    val decision: String,
    val childRight: String,
    val unfinishedBoundary: String? = null,
)

internal data class OpportunityComposerUiModel(
    val stageLabel: String,
    val title: String,
    val description: String,
    val demoDescription: String,
    val inputLabel: String,
    val placeholder: String,
    val actionLabel: String,
    val persistContextByDefault: Boolean,
)

internal data class AiSourceSetupUiModel(val description: String, val actionLabel: String)

internal fun aiSourceSetupUiModel(stage: LifecycleStage): AiSourceSetupUiModel =
    if (stage == LifecycleStage.HAND_OVER) {
        AiSourceSetupUiModel(
            description = "这台共享设备需由家长连接 AI；离线演示不发送或保存你的输入。",
            actionLabel = "请家长设置 AI",
        )
    } else {
        AiSourceSetupUiModel(
            description = "正式寻找入口需先连接 AI。",
            actionLabel = "连接 AI 服务",
        )
    }

internal fun subjectSwitchLabel(stage: LifecycleStage): String = when (stage) {
    LifecycleStage.HAND_OVER, LifecycleStage.GRADUATION -> "切换查看人"
    else -> "切换孩子"
}

internal fun opportunityComposerUiModel(stage: LifecycleStage, childName: String): OpportunityComposerUiModel = when (stage) {
    LifecycleStage.CO_PLAY -> OpportunityComposerUiModel(
        stageLabel = "共玩 · 一起体验",
        title = "从一句话开始，一起玩",
        description = "顺着${childName}正在玩的事，不做评分。",
        demoDescription = "写一句 $childName 最近玩什么，只预览本机固定模板；输入和点选都不发送、不保存。",
        inputLabel = "最近反复玩或说起什么？",
        placeholder = "例如：最近总想把积木车推下不同的斜坡",
        actionLabel = "看看有哪些共玩入口",
        persistContextByDefault = true,
    )
    LifecycleStage.ACCOMPANY -> OpportunityComposerUiModel(
        stageLabel = "陪伴 · 孩子可拒绝",
        title = "顺着兴趣，看见几扇门",
        description = "从${childName}主动关注的事开始。",
        demoDescription = "写一句 $childName 最近主动关注的事，只预览本机固定模板；输入和点选都不发送、不保存。",
        inputLabel = "孩子最近主动关注了什么？",
        placeholder = "例如：最近总在研究赛车为什么能过弯",
        actionLabel = "看看有哪些入口",
        persistContextByDefault = true,
    )
    LifecycleStage.CO_SELECT -> OpportunityComposerUiModel(
        stageLabel = "共选 · 一起比较",
        title = "一起比较，再一起选择",
        description = "和${childName}写下这次想探索的事。",
        demoDescription = "一起写一句想探索的事，只预览本机固定模板；输入和点选都不发送、不保存。",
        inputLabel = "最近想继续探索什么？",
        placeholder = "例如：想知道不同赛车为什么在弯道表现不一样",
        actionLabel = "一起看看有哪些入口",
        persistContextByDefault = true,
    )
    LifecycleStage.HAND_OVER -> OpportunityComposerUiModel(
        stageLabel = "放权 · 你来决定",
        title = "找不找入口，由你决定",
        description = "是否发送、是否保存，由你决定。",
        demoDescription = "你可以写一句想探索的事，先看本机固定模板；输入和点选都不发送，也不留进长期足迹。",
        inputLabel = "你这次想继续探索什么？",
        placeholder = "例如：我想找个真正能上手试的机械项目",
        actionLabel = "由我确认并寻找入口",
        persistContextByDefault = false,
    )
    LifecycleStage.GRADUATION -> OpportunityComposerUiModel(
        stageLabel = "成年交接 · 本人掌控",
        title = "新的家长侧记录已经停止",
        description = "资料与下一步由 $childName 本人掌控。",
        demoDescription = "",
        inputLabel = "",
        placeholder = "",
        actionLabel = "",
        persistContextByDefault = false,
    )
}

internal fun lifecycleUiModel(stage: LifecycleStage): LifecycleUiModel = when (stage) {
    LifecycleStage.CO_PLAY -> LifecycleUiModel(
        ageRange = "4–6 岁",
        relationship = "一起玩，不评价",
        decision = "家长发起共同体验，但活动本身首先要好玩。",
        childRight = "不做发展评分，不把一次喜欢写成长期结论。",
    )
    LifecycleStage.ACCOMPANY -> LifecycleUiModel(
        ageRange = "7–9 岁",
        relationship = "陪着看见选择",
        decision = "家长陪着选，孩子有权说不。",
        childRight = "孩子可以纠正理解，也可以让一次表达不进入长期足迹。",
    )
    LifecycleStage.CO_SELECT -> LifecycleUiModel(
        ageRange = "10–12 岁",
        relationship = "共同比较几扇门",
        decision = "孩子和家长共同决定，双方目标保持分开。",
        childRight = "孩子可以选择、拒绝和修正系统对自己的理解。",
    )
    LifecycleStage.HAND_OVER -> LifecycleUiModel(
        ageRange = "13–15 岁",
        relationship = "把控制权交还孩子",
        decision = "寻找入口前需要孩子本人同意，最终由孩子决定。",
        childRight = "私密、分享、导出与删除权应逐步由孩子掌控。",
        unfinishedBoundary = "当前版本已有逐次确认，但孩子独立私密密钥仍在建设中。",
    )
    LifecycleStage.GRADUATION -> LifecycleUiModel(
        ageRange = "16 岁起",
        relationship = "本人掌控自己的资料",
        decision = "停止新的家长侧儿童记录，由本人决定下一步。",
        childRight = "可以取走只包含本人资料的加密资料包，也可以只读保留、清空经历或从活动家庭副本删除本人资料。",
        unfinishedBoundary = "当前版本已有本人逐次确认和同步防复活标记，但共享设备勾选仍不是身份认证；本人私钥与完整迁移仍在建设。",
    )
}

private fun LifecycleStage.asLifecycleUiLabel() = when (this) {
    LifecycleStage.CO_PLAY -> "共玩"
    LifecycleStage.ACCOMPANY -> "陪伴"
    LifecycleStage.CO_SELECT -> "共选"
    LifecycleStage.HAND_OVER -> "放权"
    LifecycleStage.GRADUATION -> "成年交接"
}

@Composable
internal fun ChildStageCard(
    child: Child,
    selected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val age = child.ageAt()
    val belowMinimumAge = age < 4
    val stage = lifecycleStage(child)
    val copy = lifecycleUiModel(stage)
    JianyuCard(
        tone = JianyuCardTone.NEUTRAL,
        contentPadding = PaddingValues(17.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(child.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (selected) JianyuTextPill(
                    text = "当前查看",
                    tone = JianyuPillTone.DISCOVERY,
                    emphasized = true,
                )
                JianyuTextPill(
                    text = if (belowMinimumAge) "$age 周岁 · 尚未进入适用阶段" else "$age 周岁 · ${stage.asLifecycleUiLabel()}",
                    tone = JianyuPillTone.SUBTLE,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Text(
                "出生日期 · ${formatBirthDate(child.birthDate, child.birthYear)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (belowMinimumAge) {
                JianyuInset(tone = JianyuCardTone.HUMAN) {
                    Text(
                        "当前版本从 4 岁开始寻找入口。资料可以留在本机；现在不需要安排或记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            } else {
                Text(copy.relationship, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LifecycleBoundaryRow("现在谁决定", copy.decision)
                if (expanded) {
                    LifecycleStageStrip(stage)
                    LifecycleBoundaryRow("权利与边界", copy.childRight)
                }
                copy.unfinishedBoundary?.let {
                    JianyuInset(tone = JianyuCardTone.HUMAN) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                JianyuDisclosureToggle(
                    expanded = expanded,
                    onClick = onToggle,
                    collapsedLabel = "查看阶段与权利",
                    expandedLabel = "收起阶段与权利",
                )
            }
        }
    }
}

@Composable
private fun LifecycleStageStrip(current: LifecycleStage) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LifecycleStage.entries.forEach { stage ->
            val selected = stage == current
            val copy = lifecycleUiModel(stage)
            JianyuTextPill(
                text = "${stage.asLifecycleUiLabel()} ${copy.ageRange}",
                tone = if (selected) JianyuPillTone.DISCOVERY_STRONG else JianyuPillTone.SUBTLE,
                emphasized = selected,
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun LifecycleBoundaryRow(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
