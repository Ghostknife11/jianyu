package org.jianyu.core.data

import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Verification
import org.jianyu.core.domain.OpportunityEcosystems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StructuredOpportunityParserTest {
    @Test
    fun `an intentional empty candidate set remains valid for Nothing only`() {
        val parsed = parseStructuredOpportunityContent(
            content = """{"opportunities":[]}""",
            providerName = "合成 Provider",
            model = "synthetic-model",
        )

        assertEquals(emptyList<Any>(), parsed)
    }

    @Test
    fun `provider JSON remains an unverified candidate until local gate`() {
        val content = """
            ```json
            {"opportunities":[
              {"title":"比较真实圈速变化","explanation":"看一小段比赛，孩子自己挑两个圈速猜原因。","whyNow":"赛车兴趣与近期速度主题自然相遇","ecosystem":"体育","primaryGoal":"CHILD","childPull":true,"timeMinutes":25,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.72,"minAge":9,"maxAge":13,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"写三页练习题","explanation":"完成速度公式训练。","whyNow":"家长想安排练习","ecosystem":"课程","primaryGoal":"CAREGIVER","childPull":false,"timeMinutes":60,"costBand":"FREE_EXISTING","caregiverEnergy":"MEDIUM","travelMinutes":0,"confidence":0.9,"minAge":9,"maxAge":13,"naturalEntry":false,"interventionPressure":"HIGH"}
            ]}
            ```
        """.trimIndent()
        val parsed = parseStructuredOpportunityContent(content, "合成 Provider", "synthetic-model")
        assertEquals(2, parsed.size)
        assertEquals(GoalOwner.CHILD, parsed.first().primaryGoal)
        assertEquals(Verification.IDEA, parsed.first().verification)
        assertEquals(OpportunityEcosystems.SPORT, parsed.first().ecosystem)
        assertEquals(OpportunityEcosystems.WORKSHEET, parsed[1].ecosystem)
        assertFalse(parsed[1].naturalEntry)
        assertEquals("合成 Provider · synthetic-model", parsed.first().sourceTitle)
        assertEquals(0.9, parsed[1].score, 0.0)
    }

    @Test
    fun `malformed AI fields are dropped instead of becoming cheap easy defaults`() {
        val content = """
            {"opportunities":[
              {"title":"费用格式错误","explanation":"不应通过。","whyNow":"测试","ecosystem":"线下文化","primaryGoal":"CHILD","childPull":true,"timeMinutes":30,"costBand":"未知费用","caregiverEnergy":"LOW","travelMinutes":10,"confidence":0.7,"minAge":8,"maxAge":12,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"时间类型错误","explanation":"不应通过。","whyNow":"测试","ecosystem":"动手","primaryGoal":"CHILD","childPull":true,"timeMinutes":"半天","costBand":"LOW","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.7,"minAge":8,"maxAge":12,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"真假类型错误","explanation":"不应通过。","whyNow":"测试","ecosystem":"阅读","primaryGoal":"CHILD","childPull":"yes","timeMinutes":20,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.7,"minAge":8,"maxAge":12,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"年龄范围倒置","explanation":"不应通过。","whyNow":"测试","ecosystem":"自然","primaryGoal":"CHILD","childPull":true,"timeMinutes":20,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.7,"minAge":14,"maxAge":8,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"合规候选","explanation":"保留真实字段。","whyNow":"回应当下兴趣","ecosystem":"家庭生活","primaryGoal":"SHARED","childPull":true,"timeMinutes":35,"costBand":"FREE_EXISTING","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.66,"minAge":7,"maxAge":15,"naturalEntry":true,"interventionPressure":"LOW"}
            ]}
        """.trimIndent()

        val parsed = parseStructuredOpportunityContent(content, "合成 Provider", "synthetic-model")

        assertEquals(listOf("合规候选"), parsed.map { it.title })
        assertEquals(35, parsed.single().requirements.timeMinutes)
        assertEquals(GoalOwner.SHARED, parsed.single().primaryGoal)
        assertEquals(OpportunityEcosystems.FAMILY_LIFE, parsed.single().ecosystem)
    }
}
