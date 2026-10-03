package org.jianyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jianyu.app.ui.theme.JianyuTheme
import org.jianyu.core.domain.FeedbackProvenance
import org.jianyu.core.domain.appendChoiceFeedback
import org.jianyu.core.domain.deleteChoiceWithTombstones
import org.jianyu.core.model.Child
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Household
import org.jianyu.core.model.Hypothesis
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.Verification
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Isolated synthetic state: never launches the app or reads/writes the Family Vault. */
@RunWith(AndroidJUnit4::class)
class TimelineFeedbackUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun handOverChoiceDeletionNeedsSeparateConfirmationAndLeavesOnlyContentFreeReceipt() {
        val caregiver = FamilyMember("test-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = Instant.now().toString())
        val childMember = FamilyMember("test-child-member", "小禾", MemberRole.CHILD, "test-child", Instant.now().toString())
        var family by mutableStateOf(syntheticFamily(14, caregiver, childMember))
        var confirmedCalls = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        TimelineScreen(
                            family = family,
                            feedbackSavingChoiceId = null,
                            onCaregiverFeedback = { _, _ -> },
                            onChildFeedback = { _, _ -> },
                            onCorrectEvidence = { _, _ -> },
                            onDeleteEvidence = {},
                            onDeleteChoice = { id, subjectConfirmed ->
                                if (subjectConfirmed) confirmedCalls++
                                val ids = listOf("choice-delete", "event-delete", "choice-delete-audit").iterator()
                                family = deleteChoiceWithTombstones(
                                    family, id, childMember.id, MemberRole.CHILD, subjectConfirmed,
                                    "instrumentation-only", Instant.now().toString(), ids::next,
                                )
                            },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("查看记录操作"))
        composeRule.onNodeWithText("查看记录操作").performClick()
        composeRule.onNodeWithText("删除这条选择").performClick()
        composeRule.onNodeWithText("这由本人决定；共用设备不能验证身份。").assertExists()
        composeRule.waitForIdle()
        Thread.sleep(400) // Let the Android dialog window finish its entrance animation before capture.
        saveScreenshot("timeline-choice-delete-confirm-teen.png", dialog = true)
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(0, confirmedCalls)
        assertEquals(1, family.choices.size)

        composeRule.onNodeWithText("删除这条选择").performClick()
        composeRule.onNodeWithText("由我确认删除").performClick()
        assertEquals(1, confirmedCalls)
        assertEquals(0, family.choices.size)
        assertEquals("opportunity.choice-deleted", family.events.single().eventType)
        assertEquals(2, family.tombstones.size)
        composeRule.onNodeWithText("去站台看看火车").assertDoesNotExist()
    }

    @Test
    fun youngerChildViewIsRelayedOnceAndShownWithCaregiverAttribution() {
        verifyFeedbackFlow(age = 8, expectedPrompt = "如果孩子已表达看法，家长可代记；不知道就跳过。点选不代表已参与。",
            expectedAttribution = "后来的看法（家长代记）：喜欢")
    }

    @Test
    fun handOverViewKeepsChildVoiceAndSignature() {
        verifyFeedbackFlow(age = 14, expectedPrompt = "如果你已有看法，可以自己补充；共用设备上的署名不是身份认证。点选不代表已参与。",
            expectedAttribution = "后来的看法（孩子署名）：喜欢")
    }

    @Test
    fun graduationHistoryDoesNotCallTheAdultAChild() {
        val now = Instant.now().toString()
        val caregiver = FamilyMember("test-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = now)
        val childMember = FamilyMember("test-child-member", "小禾", MemberRole.CHILD, "test-child", now)
        val expression = "小时候说想看看火车"
        val base = syntheticFamily(18, caregiver, childMember)
        val family = base.copy(
            choices = base.choices.map { it.copy(status = "child-vetoed") },
            evidence = listOf(Evidence(
                id = "legacy-demo-evidence",
                childId = "test-child",
                authorId = caregiver.id,
                stream = ContextStream.CHILD,
                kind = EvidenceKind.CHILD_STATED,
                expression = expression,
                occurredAt = now,
                recordedAt = now,
                ownerId = caregiver.id,
                visibility = EvidenceVisibility.SHARED_WITH_CHILD,
            )),
            hypotheses = listOf(Hypothesis(
                id = "legacy-demo-hypothesis",
                childId = "test-child",
                statement = "当时可能想观察火车",
                confidence = 0.5,
                supports = listOf("legacy-demo-evidence"),
                modelId = "synthetic-test",
                policyVersion = "test",
                derivedAt = now,
            )),
            events = base.events.map {
                it.copy(eventType = "opportunity.child-vetoed", authorId = childMember.id, actorRole = MemberRole.CHILD)
            } + FamilyEvent(
                eventId = "legacy-demo-event",
                eventType = "interest.observed",
                householdId = "test-household",
                authorId = caregiver.id,
                actorRole = caregiver.role,
                subjectId = "test-child",
                deviceId = "instrumentation-only",
                occurredAt = now,
                recordedAt = now,
                visibility = "shared-with-child",
                payload = mapOf("discoveryMode" to "offline-demo", "expression" to expression),
            ),
        )
        composeRule.setContent {
            JianyuTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
        composeRule.onNodeWithText("本人当时拒绝").assertExists()
        composeRule.onNodeWithText("记录署名：小禾 · 本人").assertExists()
        composeRule.onNodeWithText("孩子已拒绝").assertDoesNotExist()
        saveScreenshot("timeline-graduation-choice-language.png")
        composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("查看本机操作记录"))
        composeRule.onNodeWithText("查看本机操作记录").performClick()
        composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("本人当时拒绝一个入口"))
        composeRule.onNodeWithText("本人当时拒绝一个入口").assertExists()
        composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("查看旧版演示记录"))
        composeRule.onNodeWithText("这些只是旧版演示，不代表真实想法；以后 AI 找入口也不会参考。").assertExists()
        composeRule.onNodeWithText("查看旧版演示记录").performClick()
        composeRule.onNodeWithTag("timeline-list").performScrollToKey("demo-hypothesis-legacy-demo-hypothesis")
        composeRule.onNodeWithText("关联过演示输入，仅供查看旧记录，不应视为对本人的真实判断。").assertExists()
        saveScreenshot("timeline-graduation-legacy-language.png")
    }

    private fun verifyFeedbackFlow(age: Int, expectedPrompt: String, expectedAttribution: String) {
        val handOver = age == 14
        val caregiver = FamilyMember("test-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = Instant.now().toString())
        val childMember = FamilyMember("test-child-member", "小禾", MemberRole.CHILD, "test-child", Instant.now().toString())
        var family by mutableStateOf(syntheticFamily(age, caregiver, childMember))
        var savingChoiceId by mutableStateOf<String?>(null)
        var acceptedTaps = 0

        composeRule.setContent {
            JianyuTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TimelineScreen(
                        family = family,
                        feedbackSavingChoiceId = savingChoiceId,
                        onCaregiverFeedback = { id, _ ->
                            if (!handOver) {
                                acceptedTaps++
                                savingChoiceId = id
                            }
                        },
                        onChildFeedback = { id, _ ->
                            if (handOver) {
                                acceptedTaps++
                                savingChoiceId = id
                            }
                        },
                        onCorrectEvidence = { _, _ -> },
                        onDeleteEvidence = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(expectedPrompt).assertExists()
        saveScreenshot("timeline-feedback-${age}-before.png")
        composeRule.onNodeWithText("喜欢").performClick()
        composeRule.onNodeWithText("正在保存看法…").assertExists()
        composeRule.onNodeWithText("一般").assertIsNotEnabled()
        composeRule.onNodeWithText("不合适").assertIsNotEnabled()
        assertEquals(1, acceptedTaps)

        composeRule.runOnIdle {
            family = appendChoiceFeedback(
                family = family,
                choiceId = "test-choice",
                value = "喜欢",
                author = if (handOver) childMember else caregiver,
                provenance = if (handOver) FeedbackProvenance.CHILD_SIGNED else FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW,
                eventId = "test-feedback-event",
                recordedAt = Instant.now(),
                deviceId = "instrumentation-only",
                referenceDate = LocalDate.now(),
            )
            savingChoiceId = null
        }

        composeRule.onNodeWithText(expectedAttribution).assertExists()
        saveScreenshot("timeline-feedback-${age}-after.png")
        composeRule.onNodeWithText("喜欢").assertDoesNotExist()
        composeRule.onNodeWithText("正在保存看法…").assertDoesNotExist()
        assertEquals(1, acceptedTaps)
        assertEquals("喜欢", family.choices.single().feedback)
    }

    private fun saveScreenshot(name: String, dialog: Boolean = false) {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        output.outputStream().use { stream ->
            val bitmap = if (dialog) {
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            } else {
                composeRule.onRoot().captureToImage().asAndroidBitmap()
            }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun syntheticFamily(age: Int, caregiver: FamilyMember, childMember: FamilyMember): FamilyState {
        val now = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(age.toLong()).minusDays(10).toString()
        val author = if (age == 14) childMember else caregiver
        val opportunity = Opportunity(
            opportunityId = "test-door",
            title = "去站台看看火车",
            explanation = "从孩子提起的火车开始。",
            whyNow = "孩子主动提起火车。",
            ecosystem = "places",
            primaryGoal = GoalOwner.CHILD,
            childPull = true,
            requirements = OpportunityRequirements(30, CostBand.FREE, EnergyBand.LOW, 0),
            sourceKind = "synthetic-test",
            verification = Verification.IDEA,
            score = 0.7,
        )
        return FamilyState(
            household = Household("test-household", "虚构家庭", now),
            members = listOf(caregiver, childMember),
            children = listOf(Child("test-child", childMember.id, "小禾", birthday.take(4).toInt(), now, birthday)),
            choices = listOf(FamilyChoice("test-choice", "test-child", opportunity, "test-discovery", "chosen", now)),
            events = listOf(FamilyEvent(
                eventId = "test-choice-event",
                eventType = "opportunity.chosen",
                householdId = "test-household",
                authorId = author.id,
                actorRole = author.role,
                subjectId = "test-child",
                deviceId = "instrumentation-only",
                occurredAt = now,
                recordedAt = now,
                visibility = "guardians",
                payload = mapOf("choiceId" to "test-choice"),
            )),
        )
    }
}
