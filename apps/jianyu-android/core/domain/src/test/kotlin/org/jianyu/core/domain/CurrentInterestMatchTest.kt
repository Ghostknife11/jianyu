package org.jianyu.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentInterestMatchTest {
    @Test
    fun `an explicit refusal is not current child pull`() {
        assertTrue(currentInterestRefusesTerm("孩子明确不想再看赛车", "赛车"))
        assertFalse(currentInterestMentionsTerm("孩子明确不想再看赛车", "赛车"))
        assertTrue(currentInterestRefusesTerm("赛车我不要了", "赛车"))
        assertFalse(currentInterestMentionsTerm("赛车我不要了", "赛车"))
    }

    @Test
    fun `a different refused clause does not suppress the chosen topic`() {
        val expression = "不想做题，但想知道赛车为什么转弯"
        assertTrue(currentInterestMentionsTerm(expression, "赛车"))
        assertFalse(currentInterestRefusesTerm(expression, "赛车"))
        assertTrue(currentInterestMentionsTerm("赛车", "赛车"))
        assertTrue(currentInterestMentionsTerm("孩子特别喜欢赛车", "赛车"))
        assertTrue(currentInterestRefusesTerm("孩子说别再看赛车了", "赛车"))
    }

    @Test
    fun `mixed topics keep the rejected term visible to the source filter`() {
        val expression = "不想赛车，但想看看汽车"
        assertTrue(currentInterestRefusesTerm(expression, "赛车"))
        assertTrue(currentInterestMentionsTerm(expression, "汽车"))
    }

    @Test
    fun `a pure refusal is not a positive child pull while a separate wanted clause is`() {
        assertFalse(currentInterestHasNonRefusalClue("孩子明确不想再看赛车"))
        assertFalse(currentInterestHasNonRefusalClue("不要赛车，也不想看汽车"))
        assertFalse(currentInterestHasNonRefusalClue("不要赛车，今天下雨了"))
        assertFalse(currentInterestHasNonRefusalClue("不要赛车，但家长想安排物理课"))
        assertTrue(currentInterestHasNonRefusalClue("不想做题，但想知道赛车为什么转弯"))
        assertTrue(currentInterestHasNonRefusalClue("不想赛车，但孩子问汽车为什么能转弯"))
        assertTrue(currentInterestHasNonRefusalClue("赛车"))
    }

    @Test
    fun `adult plans and public background do not become child interest`() {
        for (background in listOf("家长想带孩子看赛车", "天气预报说今晚能看到赛车活动")) {
            assertFalse(currentInterestHasNonRefusalClue(background))
            assertFalse(currentInterestMentionsTerm(background, "赛车"))
        }
        assertTrue(currentInterestMentionsTerm("家长想上物理课，但孩子想看赛车", "赛车"))
        assertTrue(currentInterestMentionsTerm("天气预报说有赛车活动，孩子问赛车为什么这么快", "赛车"))
        assertTrue(currentInterestMentionsTerm("赛车", "赛车"))
    }

    @Test
    fun `caregiver refusal is not misattributed as a child veto`() {
        val expression = "家长不想去赛车现场，但孩子想在家看赛车"
        assertFalse(currentInterestRefusesTerm(expression, "赛车"))
        assertTrue(currentInterestMentionsTerm(expression, "赛车"))
        assertTrue(currentInterestHasNonRefusalClue(expression))
        assertTrue(currentInterestRefusesTerm("孩子不想看赛车，但家长想去赛车现场", "赛车"))
        assertFalse(currentInterestHasNonRefusalClue("parent doesn't want to watch racing"))
        assertFalse(currentInterestRefusesTerm("parent doesn't want racing, but child wants racing", "racing"))
    }
}
