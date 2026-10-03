package org.jianyu.app

import org.jianyu.core.domain.DiscoverySourceIssue
import org.jianyu.core.model.Child
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DiscoveryComposerDraftTest {
    @Test
    fun `draft survives unrelated state updates for the same child and stage`() {
        val state = MainUiState(selectedChildId = "child-a")
            .withComposerDraft("child-a", LifecycleStage.ACCOMPANY, true) {
                it.copy(expression = "最近在研究车轮", region = "上海")
            }

        val afterSettings = state.copy(aiConfigured = true)
        assertEquals("最近在研究车轮", afterSettings.composerDraft?.expression)
        assertEquals("上海", afterSettings.composerDraft?.region)
    }

    @Test
    fun `draft cannot be carried to another child or age stage`() {
        val state = MainUiState(selectedChildId = "child-a")
            .withComposerDraft("child-a", LifecycleStage.ACCOMPANY, true) {
                it.copy(expression = "只属于甲的线索", region = "上海")
            }

        assertEquals(
            state,
            state.withComposerDraft("child-b", LifecycleStage.ACCOMPANY, true) { it.copy(expression = "错误写入") },
        )
        val nextStage = state.withComposerDraft("child-a", LifecycleStage.CO_SELECT, false) { it }
        assertEquals("", nextStage.composerDraft?.expression)
        assertEquals("", nextStage.composerDraft?.region)
        assertEquals(false, nextStage.composerDraft?.persistContext)
        assertNull(MainUiState(selectedChildId = "child-b").composerDraft)
    }

    @Test
    fun `draft fields have the same length limits as the discovery form`() {
        val state = MainUiState(selectedChildId = "child-a")
            .withComposerDraft("child-a", LifecycleStage.ACCOMPANY, true) {
                it.copy(expression = "x".repeat(1000), caregiverGoal = "x".repeat(400), region = "x".repeat(100))
            }

        assertEquals(800, state.composerDraft?.expression?.length)
        assertEquals(300, state.composerDraft?.caregiverGoal?.length)
        assertEquals(80, state.composerDraft?.region?.length)
    }

    @Test
    fun `failed source return keeps draft but clears old result and disclosure`() {
        val state = MainUiState(selectedChildId = "child-a")
            .withComposerDraft("child-a", LifecycleStage.ACCOMPANY, true) {
                it.copy(expression = "最近在研究车轮", region = "上海")
            }.copy(
                discoveryMode = "byok-ai",
                sourceEventId = "old-request",
                discoverySourceIssues = listOf(DiscoverySourceIssue("ai", "byok-llm")),
                disclosureIncluded = listOf("current-interest"),
                contextPersisted = true,
            )

        val retry = state.afterDiscoveryReturn(keepDraft = true)
        assertEquals("最近在研究车轮", retry.composerDraft?.expression)
        assertEquals("最近在研究车轮", retry.composerDrafts["child-a"]?.expression)
        assertEquals("上海", retry.composerDraft?.region)
        assertNull(retry.sourceEventId)
        assertNull(retry.discoveryMode)
        assertTrue(retry.discoverySourceIssues.isEmpty())
        assertTrue(retry.disclosureIncluded.isEmpty())
        assertNull(retry.contextPersisted)
        assertNull(state.afterDiscoveryReturn(keepDraft = false).composerDraft)
        assertTrue("child-a" !in state.afterDiscoveryReturn(keepDraft = false).composerDrafts)
        assertNull(state.copy(discoverySourceIssues = emptyList()).afterDiscoveryReturn(keepDraft = true).composerDraft)
    }

    @Test
    fun `unsent drafts stay separate and return when switching between children`() {
        val family = twoChildFamily()
        val first = MainUiState(family = family, selectedChildId = "child-a")
            .withComposerDraft("child-a", LifecycleStage.CO_PLAY, true) {
                it.copy(expression = "甲最近总在搭车", region = "上海")
            }
        val second = first.withSelectedChild("child-b")
        assertNull(second.composerDraft)
        val secondWritten = second.withComposerDraft("child-b", LifecycleStage.CO_SELECT, true) {
            it.copy(expression = "乙想看火车过弯", region = "北京")
        }

        val backToFirst = secondWritten.withSelectedChild("child-a")
        assertEquals("甲最近总在搭车", backToFirst.composerDraft?.expression)
        assertEquals("上海", backToFirst.composerDraft?.region)
        val backToSecond = backToFirst.withSelectedChild("child-b")
        assertEquals("乙想看火车过弯", backToSecond.composerDraft?.expression)
        assertEquals("北京", backToSecond.composerDraft?.region)
        assertEquals(emptyList<String>(), backToSecond.disclosureIncluded)
    }

    @Test
    fun `switching rejects a stale stage draft and active writes`() {
        val family = twoChildFamily()
        val state = MainUiState(family = family, selectedChildId = "child-a")
            .withComposerDraft("child-a", LifecycleStage.CO_PLAY, true) { it.copy(expression = "甲的线索") }
            .copy(composerDrafts = mapOf(
                "child-b" to DiscoveryComposerDraft("child-b", LifecycleStage.ACCOMPANY, persistContext = true, expression = "旧阶段草稿"),
            ))
        val switched = state.withSelectedChild("child-b")
        assertNull(switched.composerDraft)
        assertTrue("child-b" !in switched.composerDrafts)
        assertEquals(state, state.copy(discovering = true).withSelectedChild("child-b").copy(discovering = false))
        assertEquals(state, state.copy(choiceSaving = true).withSelectedChild("child-b").copy(choiceSaving = false))
        assertEquals(state, state.copy(feedbackSavingChoiceId = "choice").withSelectedChild("child-b").copy(feedbackSavingChoiceId = null))
        assertEquals(state, state.copy(disclosureSaveRisk = true).withSelectedChild("child-b").copy(disclosureSaveRisk = false))
        assertEquals(state, state.withSelectedChild("unknown-child"))
    }

    private fun twoChildFamily(): FamilyState {
        val now = LocalDate.now()
        val younger = now.minusYears(5).minusDays(5)
        val older = now.minusYears(11).minusDays(5)
        val created = "2026-01-01T00:00:00Z"
        return FamilyState(
            household = Household("household", "虚构家庭", created),
            members = listOf(
                FamilyMember("caregiver", "家长", MemberRole.CAREGIVER, createdAt = created),
                FamilyMember("member-a", "甲", MemberRole.CHILD, "child-a", created),
                FamilyMember("member-b", "乙", MemberRole.CHILD, "child-b", created),
            ),
            children = listOf(
                Child("child-a", "member-a", "甲", younger.year, created, younger.toString()),
                Child("child-b", "member-b", "乙", older.year, created, older.toString()),
            ),
        )
    }
}
