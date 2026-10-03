package org.jianyu.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class JianyuCardTone { NEUTRAL, DISCOVERY, HUMAN, PREVIEW, DANGER }

/** Shared page rhythm; new screens should not choose their own outer gutters. */
internal object JianyuLayout {
    val screenHorizontal = 20.dp
    val screenVertical = 18.dp
    val sectionGap = 16.dp
}

/** A quiet, nested information surface. It is never a second competing card or action. */
@Composable
internal fun JianyuInset(
    modifier: Modifier = Modifier,
    tone: JianyuCardTone = JianyuCardTone.NEUTRAL,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable () -> Unit,
) {
    val color = when (tone) {
        JianyuCardTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainer
        JianyuCardTone.DISCOVERY -> MaterialTheme.colorScheme.primaryContainer
        JianyuCardTone.HUMAN -> MaterialTheme.colorScheme.secondaryContainer
        JianyuCardTone.PREVIEW -> MaterialTheme.colorScheme.tertiaryContainer
        JianyuCardTone.DANGER -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = color,
    ) {
        Box(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
    }
}

internal enum class JianyuPillTone {
    NEUTRAL,
    SUBTLE,
    DISCOVERY,
    DISCOVERY_STRONG,
    HUMAN,
    HUMAN_STRONG,
    DANGER,
}

@Composable
internal fun JianyuCard(
    modifier: Modifier = Modifier,
    tone: JianyuCardTone = JianyuCardTone.NEUTRAL,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    emphasized: Boolean = false,
    content: @Composable () -> Unit,
) {
    val color = when (tone) {
        JianyuCardTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerLow
        JianyuCardTone.DISCOVERY -> MaterialTheme.colorScheme.primaryContainer
        JianyuCardTone.HUMAN -> MaterialTheme.colorScheme.secondaryContainer
        JianyuCardTone.PREVIEW -> MaterialTheme.colorScheme.tertiaryContainer
        JianyuCardTone.DANGER -> MaterialTheme.colorScheme.errorContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = if (emphasized) 2.dp else 0.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(contentPadding)) { content() }
    }
}

/** Shared page hierarchy. Product surfaces should start here instead of inventing local title styles. */
@Composable
internal fun JianyuPageHeader(
    section: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            section,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun JianyuSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One card-level heading style across product surfaces. Page and opportunity heroes remain separate. */
@Composable
internal fun JianyuCardTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** The same affordance and accessibility state for optional detail across screens. */
@Composable
internal fun JianyuDisclosureToggle(
    expanded: Boolean,
    onClick: () -> Unit,
    collapsedLabel: String,
    expandedLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        enabled = enabled,
    ) {
        Text(if (expanded) expandedLabel else collapsedLabel)
        Icon(
            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
        )
    }
}

/** Full opportunity and Nothing titles share readable wrapping and heading semantics. */
@Composable
internal fun JianyuOpportunityTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
        fontWeight = FontWeight.SemiBold,
    )
}

/** Shared selected-state language. Warm containers are reserved for human-decision cards, not generic selection. */
@Composable
internal fun JianyuChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/**
 * Shared read-only metadata/status capsule. Interactive choices continue to use [JianyuChoiceChip].
 * Strong tones are reserved for the active item inside a compact status sequence.
 */
@Composable
internal fun JianyuPill(
    tone: JianyuPillTone = JianyuPillTone.NEUTRAL,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val (containerColor, contentColor) = when (tone) {
        JianyuPillTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        JianyuPillTone.SUBTLE -> MaterialTheme.colorScheme.surface.copy(alpha = 0.78f) to MaterialTheme.colorScheme.onSurfaceVariant
        JianyuPillTone.DISCOVERY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        JianyuPillTone.DISCOVERY_STRONG -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        JianyuPillTone.HUMAN -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        JianyuPillTone.HUMAN_STRONG -> MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.onSecondary
        JianyuPillTone.DANGER -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
internal fun JianyuTextPill(
    text: String,
    tone: JianyuPillTone = JianyuPillTone.NEUTRAL,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) {
    JianyuPill(tone = tone, modifier = modifier, contentPadding = contentPadding) {
        Text(
            text,
            style = if (emphasized) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** A saved source has the same status, service, and optional model hierarchy on every page. */
@Composable
internal fun JianyuSavedConnection(
    providerName: String?,
    model: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        JianyuTextPill("连接信息已保存在本机")
        Text(
            "服务：${providerName ?: "名称待确认"}",
            style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
        )
        model?.let {
            Text(
                "模型",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Heading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun JianyuSupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Destructive actions keep the same danger meaning in cards and confirmation dialogs. */
@Composable
internal fun JianyuDangerButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        content = content,
    )
}

@Composable
internal fun JianyuDangerOutlinedButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        content = content,
    )
}

@Composable
internal fun JianyuDangerTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        content = content,
    )
}
