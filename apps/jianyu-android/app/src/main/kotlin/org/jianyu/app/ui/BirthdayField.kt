package org.jianyu.app.ui

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChineseBirthDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE)

internal fun formatBirthDate(value: String?, fallbackYear: Int): String = value
    ?.let { runCatching { LocalDate.parse(it).format(ChineseBirthDateFormatter) }.getOrNull() }
    ?: "${fallbackYear}年（旧记录未保存月日）"

@Composable
internal fun BirthdayField(
    birthDate: String,
    onBirthDateChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val today = LocalDate.now()
    JianyuDateField(
        value = birthDate,
        onValueChange = onBirthDateChange,
        label = "出生日期",
        emptyLabel = "请选择年月日",
        contentDescription = "选择出生日期",
        supportingText = "生日用于计算周岁、调整安全边界和决定权，不会给孩子打分。",
        initialDate = today.minusYears(8),
        minimumDate = null,
        maximumDate = today.minusYears(4),
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
internal fun AssessmentDateField(
    occurredOn: String,
    onOccurredOnChange: (String) -> Unit,
    earliestDate: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val today = LocalDate.now()
    val minimum = earliestDate
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: today.minusYears(16)
    JianyuDateField(
        value = occurredOn,
        onValueChange = onOccurredOnChange,
        label = "考试或测验日期",
        emptyLabel = "请选择年月日",
        contentDescription = "选择考试或测验日期",
        supportingText = "保存真实发生日期，方便以后按时间回看；不会据此计算“进步分”",
        initialDate = today,
        minimumDate = minimum,
        maximumDate = today,
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
private fun JianyuDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    emptyLabel: String,
    contentDescription: String,
    supportingText: String,
    initialDate: LocalDate,
    minimumDate: LocalDate?,
    maximumDate: LocalDate,
    modifier: Modifier,
    enabled: Boolean,
) {
    val selected = value.takeIf(String::isNotBlank)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val initial = selected ?: initialDate
    val context = LocalContext.current

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                val configuration = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.SIMPLIFIED_CHINESE)
                }
                val localizedContext = ContextThemeWrapper(context, context.theme).apply {
                    applyOverrideConfiguration(configuration)
                }
                // Framework DatePicker also reads the process locale lazily for its
                // accessibility dates. Scope Chinese to this modal and restore it
                // as soon as the dialog closes; the rest of the app is unaffected.
                val originalLocale = Locale.getDefault()
                Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
                try {
                    DatePickerDialog(
                        localizedContext,
                        { _, year, zeroBasedMonth, day ->
                            onValueChange(LocalDate.of(year, zeroBasedMonth + 1, day).toString())
                        },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).apply {
                        minimumDate?.let {
                            datePicker.minDate = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }
                        datePicker.maxDate = maximumDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        setOnDismissListener { Locale.setDefault(originalLocale) }
                        show()
                    }
                } catch (error: Throwable) {
                    Locale.setDefault(originalLocale)
                    throw error
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        selected?.format(ChineseBirthDateFormatter) ?: emptyLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(Icons.Rounded.CalendarMonth, contentDescription = contentDescription)
            }
        }
        Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
