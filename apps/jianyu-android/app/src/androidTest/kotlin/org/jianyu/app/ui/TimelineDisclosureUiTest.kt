package org.jianyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jianyu.app.ui.theme.JianyuTheme
import org.jianyu.core.model.Child
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic category-only records; never opens a family vault or an external source. */
@RunWith(AndroidJUnit4::class)
class TimelineDisclosureUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun approvalAndLaterScopeStayReadableAtLargeTextWithoutClaimingDelivery() {
        val timestamp = "2026-09-27T10:00:00Z"
        fun disclosureEvent(id: String, type: String, provider: String, categories: String) = FamilyEvent(
            eventId = id,
            eventType = type,
            householdId = "synthetic-household",
            authorId = "synthetic-caregiver",
            actorRole = MemberRole.CAREGIVER,
            subjectId = "synthetic-child",
            deviceId = "synthetic-device",
            occurredAt = timestamp,
            recordedAt = timestamp,
            visibility = "guardians",
            payload = mapOf(
                "provider" to provider,
                "includedCategories" to categories,
                "rawValuesStored" to "false",
            ),
        )
        val family = FamilyState(
            household = Household("synthetic-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", timestamp),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", 2018, timestamp, "2018-09-27")),
            events = listOf(
                disclosureEvent("approval", "provider.disclosure-approved", "合成 AI", "current-interest,coarse-region"),
                disclosureEvent("later-scope", "provider.context-disclosed", "合成世界服务", "coarse-region,public-time-window,language,public-categories"),
            ),
        )
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    // Match the production Scaffold body: dark text color needs Surface's LocalContentColor.
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.width(360.dp).height(720.dp)) {
                            TimelineScreen(
                                family = family,
                                feedbackSavingChoiceId = null,
                                onCaregiverFeedback = { _, _ -> },
                                onChildFeedback = { _, _ -> },
                                onCorrectEvidence = { _, _ -> },
                                onDeleteEvidence = {},
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("还没有留下选择或线索").assertExists()
        composeRule.onNodeWithText("不必专门留下记录。\n本机操作记录可在下方查看。").assertExists()
        composeRule.onNodeWithText("查看本机操作记录").performClick()
        composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("请求后记录的信息范围"))
        composeRule.onNodeWithText("外部服务：合成世界服务").assertExists()
        composeRule.onNodeWithText("信息类别：填写的地区、未来 14 天时间范围、语言、公共活动类别").assertExists()
        saveScreenshot("timeline-disclosure-world-large.png")

        composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("请求前确认的信息范围"))
        composeRule.onNodeWithText("外部服务：合成 AI").assertExists()
        composeRule.onNodeWithText("信息类别：这次的兴趣描述、填写的地区").assertExists()
        composeRule.onNodeWithText("current-interest").assertDoesNotExist()
        saveScreenshot("timeline-disclosure-approval-large.png")
    }

    private fun saveScreenshot(name: String) {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        output.outputStream().use { stream ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
