package org.jianyu.app.ui

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jianyu.app.ui.theme.JianyuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Uses a synthetic date and the isolated UI-test emulator; never opens the Family Vault. */
@RunWith(AndroidJUnit4::class)
class BirthdayFieldUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun datePickerUsesChineseWithoutChangingTheRestOfTheProcessLocale() {
        val originalLocale = Locale.getDefault()
        try {
            composeRule.runOnIdle { Locale.setDefault(Locale.ENGLISH) }
            val birthday = mutableStateOf("2018-09-26")
            composeRule.setContent {
                JianyuTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        BirthdayField(birthDate = birthday.value, onBirthDateChange = { birthday.value = it })
                    }
                }
            }

            composeRule.onNodeWithText("2018年9月26日").performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            composeRule.waitUntil(5_000) {
                automation.rootInActiveWindow?.let { hasText(it, "9月26日周三") } == true
            }
            assertEquals(Locale.SIMPLIFIED_CHINESE, Locale.getDefault())
            val root = automation.rootInActiveWindow
            assertTrue(root != null && hasText(root, "确定") && hasText(root, "取消"))
            val day25 = findDay(root!!, day = "25", month = "九月", year = "2018")
            assertTrue("Calendar accessibility dates: ${allDayDescriptions(root)}", day25 != null)
            assertTrue(day25?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            composeRule.waitForIdle()
            val updatedRoot = automation.rootInActiveWindow!!
            assertTrue("Date picker after selecting 25: ${allTexts(updatedRoot)}", hasText(updatedRoot, "9月25日周二"))
            assertTrue(findText(automation.rootInActiveWindow!!, "确定")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            composeRule.waitUntil(5_000) { birthday.value == "2018-09-25" }
            composeRule.onNodeWithText("2018年9月25日").assertExists()
            assertEquals(Locale.ENGLISH, Locale.getDefault())
        } finally {
            composeRule.runOnIdle { Locale.setDefault(originalLocale) }
        }
    }

    private fun hasText(node: AccessibilityNodeInfo, expected: String): Boolean {
        return findText(node, expected) != null
    }

    private fun findText(node: AccessibilityNodeInfo, expected: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == expected) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findText(child, expected)?.let { return it }
            }
        }
        return null
    }

    private fun allTexts(node: AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.let(::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { addAll(allTexts(it)) }
        }
    }

    private fun findDay(node: AccessibilityNodeInfo, day: String, month: String, year: String): AccessibilityNodeInfo? {
        val description = node.contentDescription?.toString().orEmpty()
        if (node.text?.toString() == day && description.contains(month) && description.contains(year)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findDay(child, day, month, year)?.let { return it }
            }
        }
        return null
    }

    private fun allDayDescriptions(node: AccessibilityNodeInfo): List<String> = buildList {
        if (node.text?.toString() == "25") node.contentDescription?.toString()?.let(::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { addAll(allDayDescriptions(it)) }
        }
    }
}
