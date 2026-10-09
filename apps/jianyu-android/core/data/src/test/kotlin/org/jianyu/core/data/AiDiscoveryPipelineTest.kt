package org.jianyu.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jianyu.core.domain.DefaultOpportunityPolicy
import org.jianyu.core.domain.FamilyOpportunityEngine
import org.jianyu.core.domain.OpportunityDiscoveryRequest
import org.jianyu.core.domain.SourceFailureReason
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.LifecycleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

// 合成占位值：只用于断言请求头形状，不对应任何真实服务凭据；仓库中不得放入真实密钥。
private const val SYNTHETIC_AUTHORIZATION_VALUE = "synthetic-authorization-value"

class AiDiscoveryPipelineTest {
    private val settings = AiProviderSettings(
        providerName = "合成 AI",
        baseUrl = "https://example.test/v1",
        model = "synthetic-model",
        apiKey = SYNTHETIC_AUTHORIZATION_VALUE,
    )

    @Test
    fun `formal AI wire response reaches local gate without sending secrets or family identifiers`() = runBlocking {
        val envelope = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("finish_reason", "stop")
                    put("message", buildJsonObject { put("content", RESPONSE) })
                })
            })
        }.toString()
        lateinit var connection: RecordingConnection
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { received, system, user, temperature ->
                OpenAiChatCompletions.request(received, system, user, temperature) { url ->
                    connection = RecordingConnection(url, envelope)
                    connection
                }
            },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))

        assertEquals("https://example.test/v1/chat/completions", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer $SYNTHETIC_AUTHORIZATION_VALUE", connection.getRequestProperty("Authorization"))
        assertTrue(connection.disconnected)
        val body = Json.parseToJsonElement(connection.sent.toString(Charsets.UTF_8.name())) as JsonObject
        val messages = body.getValue("messages") as JsonArray
        val prompt = ((messages[1] as JsonObject).getValue("content") as JsonPrimitive).content
        assertTrue(prompt.contains("\"currentInterest\":\"赛车拐弯\""))
        assertTrue(prompt.contains("会由孩子和家长一起阅读"))
        assertFalse(prompt.contains("候选应让家长产生"))
        assertFalse(prompt.contains("recentSelectedOptionCount7Days"))
        assertFalse(connection.sent.toString(Charsets.UTF_8.name()).contains(settings.apiKey))
        assertFalse(prompt.contains("householdId"))
        assertFalse(prompt.contains("birthDate"))
        assertEquals(listOf("一起看真实弯道", "用现有材料试轮胎抓地"),
            result.opportunities.selected.map { it.opportunity.title })
        assertEquals(listOf("写公式练习"), result.opportunities.rejected.map { it.opportunity.title })
        assertTrue(result.opportunities.nothing.isNothing)
        assertTrue(result.sourceIssues.isEmpty())
    }

    @Test
    fun `formal AI candidates pass through firewall gate diversity and Nothing`() = runBlocking {
        var calls = 0
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { received, system, prompt, temperature ->
                calls++
                assertEquals(settings, received)
                assertEquals(0.65, temperature, 0.0)
                assertTrue(system.contains("AI 只负责理解和发散"))
                assertTrue(prompt.contains("\"currentInterest\":\"赛车拐弯\""))
                assertFalse(prompt.contains(SYNTHETIC_AUTHORIZATION_VALUE))
                assertFalse(prompt.contains("recentSelectedOptionCount7Days"))
                RESPONSE
            },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))

        assertEquals(1, calls)
        assertEquals("synthetic-model", source.modelId)
        assertEquals(listOf("一起看真实弯道", "用现有材料试轮胎抓地"),
            result.opportunities.selected.map { it.opportunity.title })
        assertEquals(listOf("写公式练习"), result.opportunities.rejected.map { it.opportunity.title })
        assertTrue(result.opportunities.rejected.single().reasons.contains("insufficient-child-pull"))
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals(listOf("byok-llm"), result.sourceKinds)
        assertTrue(result.sourceIssues.isEmpty())
        assertTrue("current-interest" in result.sourceDisclosures.getValue(source.id).includedCategories)
        assertFalse("recent-intervention-count" in result.sourceDisclosures.getValue(source.id).includedCategories)
    }

    @Test
    fun `invalid formal AI response is disclosed as incomplete discovery with Nothing still available`() = runBlocking {
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { _, _, _, _ -> "我建议每天完成练习" },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        assertTrue(result.sourceKinds.isEmpty())
        assertEquals(source.id, result.sourceIssues.single().sourceId)
        assertEquals(SourceFailureReason.INVALID_RESPONSE.code, result.sourceIssues.single().reasonCode)
    }

    @Test
    fun `truncated AI completion cannot be shown as a family opportunity`() = runBlocking {
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { _, _, _, _ ->
                OpenAiChatCompletions.parseContent(
                    """{"choices":[{"finish_reason":"length","message":{"content":"{\"opportunities\":[]}"}}]}""",
                )
            },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        assertTrue(result.sourceKinds.isEmpty())
        assertEquals(source.id, result.sourceIssues.single().sourceId)
        assertEquals(SourceFailureReason.RESPONSE_INCOMPLETE.code, result.sourceIssues.single().reasonCode)
    }

    @Test
    fun `malformed chat envelope is an invalid source response while Nothing remains`() = runBlocking {
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { _, _, _, _ -> OpenAiChatCompletions.parseContent("provider raw response, not JSON") },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals(SourceFailureReason.INVALID_RESPONSE.code, result.sourceIssues.single().reasonCode)
        assertFalse(result.sourceIssues.single().toString().contains("provider raw response"))
    }

    @Test
    fun `slow AI response leaves Nothing and a timeout reason without pretending the search completed`() = runBlocking {
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { _, _, _, _ -> throw AiChatTimeoutException() },
        )
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals(SourceFailureReason.RESPONSE_TIMED_OUT.code, result.sourceIssues.single().reasonCode)
    }

    @Test
    fun `authentication and rate limit failures give safe actionable reasons without exposing responses`() = runBlocking {
        listOf(
            401 to SourceFailureReason.AUTHENTICATION_REJECTED,
            403 to SourceFailureReason.AUTHENTICATION_REJECTED,
            429 to SourceFailureReason.RATE_LIMITED,
            400 to SourceFailureReason.REQUEST_REJECTED,
            404 to SourceFailureReason.REQUEST_REJECTED,
            413 to SourceFailureReason.REQUEST_REJECTED,
            422 to SourceFailureReason.REQUEST_REJECTED,
        ).forEach { (status, expected) ->
            val source = OpenAiCompatibleOpportunitySource(
                loadSettings = { settings },
                complete = { _, _, _, _ -> throw AiChatHttpException(status) },
            )
            val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
            assertTrue(result.opportunities.selected.isEmpty())
            assertTrue(result.opportunities.nothing.isNothing)
            assertEquals(expected.code, result.sourceIssues.single().reasonCode)
            assertFalse(result.sourceIssues.single().toString().contains(settings.apiKey))
        }
    }

    @Test
    fun `AI invented world event is rejected while a source independent route remains`() = runBlocking {
        val response = """
            {"opportunities":[
              {"title":"本周虚构赛车展","explanation":"声称某地本周有展览，但没有原始出处。","whyNow":"孩子问过赛车拐弯","ecosystem":"world-event","primaryGoal":"CHILD","childPull":true,"timeMinutes":30,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.99,"minAge":9,"maxAge":14,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"在已有赛车游戏里比较过弯","explanation":"用家里已有的游戏试不同过弯方式。","whyNow":"回应当前问题","ecosystem":"digital-game","primaryGoal":"CHILD","childPull":true,"timeMinutes":20,"costBand":"FREE_EXISTING","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.55,"minAge":9,"maxAge":14,"naturalEntry":true,"interventionPressure":"LOW"}
            ]}
        """.trimIndent()
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { _, _, _, _ -> response },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))

        assertEquals(listOf("在已有赛车游戏里比较过弯"), result.opportunities.selected.map { it.opportunity.title })
        assertEquals(listOf("本周虚构赛车展"), result.opportunities.rejected.map { it.opportunity.title })
        assertTrue("ai-world-event-without-source" in result.opportunities.rejected.single().reasons)
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `AI cannot hide a daily worksheet in explanation behind a natural title`() = runBlocking {
        val response = """
            {"opportunities":[
              {"title":"用纸板观察赛车转弯","explanation":"先做一个纸板弯道，然后每天做三页速度练习。","whyNow":"声称孩子想研究赛车","ecosystem":"reading","primaryGoal":"CHILD","childPull":true,"timeMinutes":20,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.95,"minAge":9,"maxAge":14,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"用现有赛车游戏试过弯","explanation":"让孩子自己尝试不同过弯方式。","whyNow":"回应孩子当前问题","ecosystem":"digital-game","primaryGoal":"CHILD","childPull":true,"timeMinutes":20,"costBand":"FREE_EXISTING","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.55,"minAge":9,"maxAge":14,"naturalEntry":true,"interventionPressure":"LOW"}
            ]}
        """.trimIndent()
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings },
            complete = { _, _, _, _ -> response },
        )
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertEquals(listOf("用现有赛车游戏试过弯"), result.opportunities.selected.map { it.opportunity.title })
        assertEquals(listOf("用纸板观察赛车转弯"), result.opportunities.rejected.map { it.opportunity.title })
        assertTrue("daily-task-pressure" in result.opportunities.rejected.single().reasons)
        assertTrue(result.opportunities.nothing.isNothing)
    }

    private fun request() = OpportunityDiscoveryRequest(
        currentInterest = "赛车拐弯",
        age = 11,
        lifecycleStage = LifecycleStage.CO_SELECT,
        goals = FamilyGoals(child = "想知道为什么过弯不打滑"),
        constraints = FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 20),
        recentInterventionCount = 4,
    )

    private class RecordingConnection(url: URL, private val response: String) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(response.encodeToByteArray())
        override fun getResponseCode(): Int = 200
    }

    private companion object {
        val RESPONSE = """
            {"opportunities":[
              {"title":"一起看真实弯道","explanation":"由孩子挑一段比赛，自己问为什么车能转弯。","whyNow":"孩子正主动问赛车拐弯","ecosystem":"media","primaryGoal":"CHILD","childPull":true,"timeMinutes":20,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.7,"minAge":9,"maxAge":14,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"用现有材料试轮胎抓地","explanation":"只用手边材料，愿意时试不同表面。","whyNow":"和孩子眼下的问题自然相连","ecosystem":"making","primaryGoal":"CHILD","childPull":true,"timeMinutes":30,"costBand":"FREE_EXISTING","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.6,"minAge":9,"maxAge":14,"naturalEntry":true,"interventionPressure":"LOW"},
              {"title":"写公式练习","explanation":"每天做三页速度练习。","whyNow":"家长想补课","ecosystem":"reading","primaryGoal":"CAREGIVER","childPull":false,"timeMinutes":30,"costBand":"FREE","caregiverEnergy":"LOW","travelMinutes":0,"confidence":0.9,"minAge":9,"maxAge":14,"naturalEntry":false,"interventionPressure":"HIGH"}
            ]}
        """.trimIndent()
    }
}
