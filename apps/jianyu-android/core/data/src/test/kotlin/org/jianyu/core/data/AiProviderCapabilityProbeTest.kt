package org.jianyu.core.data

import org.jianyu.core.domain.PublicAiProbeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

// 合成占位值：只用于断言请求头形状，不对应任何真实服务凭据；仓库中不得放入真实密钥。
private const val SYNTHETIC_AUTHORIZATION_VALUE = "synthetic-authorization-value"

class AiProviderCapabilityProbeTest {
    private val settings = AiProviderSettings(
        providerName = "合成服务",
        baseUrl = "https://example.test/v1",
        model = "synthetic-model",
        apiKey = SYNTHETIC_AUTHORIZATION_VALUE,
    )

    @Test
    fun `public probe uses the production prompt shape without family data`() {
        var calls = 0
        val result = AiProviderCapabilityProbe { received, system, prompt, temperature ->
            calls++
            assertEquals(settings, received)
            assertTrue(system.contains("AI 只负责理解和发散"))
            assertTrue(prompt.contains("公开合成样例"))
            assertTrue(prompt.contains("纸飞机"))
            assertTrue(prompt.contains("\"ageBand\":\"7-9\""))
            assertFalse(prompt.contains(SYNTHETIC_AUTHORIZATION_VALUE))
            assertEquals(0.2, temperature, 0.0)
            VALID_CONTENT
        }.check(settings, PublicAiProbeCapability.issueForExplicitCheck())

        assertEquals(1, calls)
        assertEquals(AiCapabilityStatus.SAMPLE_PASSED, result.status)
        assertEquals(1, result.acceptedCount)
    }

    @Test
    fun `a response outside the shared schema is not called compatible`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ -> "我建议多做练习" }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.FORMAT_INCOMPATIBLE, result.status)
    }

    @Test
    fun `malformed chat envelope is reported as format incompatibility`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ ->
            OpenAiChatCompletions.parseContent("sensitive provider body, not JSON")
        }.check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.FORMAT_INCOMPATIBLE, result.status)
        assertEquals(null, result.httpStatus)
    }

    @Test
    fun `a valid Nothing response is inconclusive rather than malformed`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ -> """{"opportunities":[]}""" }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.VALID_NOTHING, result.status)
    }

    @Test
    fun `a parseable but infeasible candidate fails the local policy sample`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ -> VALID_CONTENT.replace("\"timeMinutes\":20", "\"timeMinutes\":120") }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.LOCAL_CHECK_REJECTED, result.status)
    }

    @Test
    fun `caregiver-only response does not pass a sample whose formal result has no visible door`() {
        val caregiverOnly = VALID_CONTENT.replace("\"primaryGoal\":\"CHILD\"", "\"primaryGoal\":\"CAREGIVER\"")
        val result = AiProviderCapabilityProbe { _, _, _, _ -> caregiverOnly }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.NO_DISPLAYABLE_DOORS, result.status)
        assertEquals(0, result.acceptedCount)
    }

    @Test
    fun `sample count matches displayed doors rather than every Gate-eligible candidate`() {
        val child = VALID_CONTENT.trim().removePrefix("{\"opportunities\":[").removeSuffix("]}")
        val caregiver = child.replace("\"primaryGoal\":\"CHILD\"", "\"primaryGoal\":\"CAREGIVER\"")
        val caregiverMedia = caregiver
            .replace("用现有纸张试两种折法", "一起看纸飞机视频")
            .replace("\"ecosystem\":\"making\"", "\"ecosystem\":\"media\"")
        val caregiverReading = caregiver
            .replace("用现有纸张试两种折法", "翻看纸飞机图册")
            .replace("\"ecosystem\":\"making\"", "\"ecosystem\":\"reading\"")
        val result = AiProviderCapabilityProbe { _, _, _, _ ->
            """{"opportunities":[$caregiverMedia,$caregiverReading,$child]}"""
        }.check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.SAMPLE_PASSED, result.status)
        assertEquals(2, result.acceptedCount)
    }

    @Test
    fun `connection errors are classified without leaking provider response text`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ -> error("secret or raw response") }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.CONNECTION_FAILED, result.status)
    }

    @Test
    fun `slow provider response has its own inconclusive status`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ -> throw AiChatTimeoutException() }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.RESPONSE_TIMED_OUT, result.status)
        assertEquals(null, result.httpStatus)
    }

    @Test
    fun `rejected public sample keeps only safe HTTP status for user guidance`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ -> throw AiChatHttpException(422) }
            .check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.CONNECTION_FAILED, result.status)
        assertEquals(422, result.httpStatus)
    }

    @Test
    fun `chat completion envelope extracts only assistant content`() {
        val body = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"opportunities\":[]}"}}]}"""
        assertEquals("{\"opportunities\":[]}", OpenAiChatCompletions.parseContent(body))
    }

    @Test
    fun `reported unfinished completions cannot become candidate content`() {
        listOf("length", "content_filter", "tool_calls", "aborted", "unexpected").forEach { reason ->
            val body = """{"choices":[{"finish_reason":"$reason","message":{"content":"{\"opportunities\":[]}"}}]}"""
            assertTrue(runCatching { OpenAiChatCompletions.parseContent(body) }.exceptionOrNull() is AiChatIncompleteException)
        }
        val nullFinish = """{"choices":[{"finish_reason":null,"message":{"content":"{\"opportunities\":[]}"}}]}"""
        assertTrue(runCatching { OpenAiChatCompletions.parseContent(nullFinish) }.exceptionOrNull() is AiChatIncompleteException)
    }

    @Test
    fun `public sample reports unfinished output without claiming model incompatibility`() {
        val result = AiProviderCapabilityProbe { _, _, _, _ ->
            OpenAiChatCompletions.parseContent(
                """{"choices":[{"finish_reason":"length","message":{"content":"{\"opportunities\":[]}"}}]}""",
            )
        }.check(settings, PublicAiProbeCapability.issueForExplicitCheck())
        assertEquals(AiCapabilityStatus.RESPONSE_INCOMPLETE, result.status)
    }

    @Test
    fun `provider key cannot be attached to an ambiguous URL`() {
        listOf(
            "http://example.test/v1",
            "https://user@example.test/v1",
            "https://example.test/v1?redirect=elsewhere",
            "https://example.test/v1#fragment",
        ).forEach { unsafe ->
            assertTrue("Expected rejection for $unsafe", runCatching { settings.copy(baseUrl = unsafe).validate() }.isFailure)
        }
    }

    @Test
    fun `shared transport posts to the configured endpoint without following redirects`() {
        lateinit var connection: FakeConnection
        val content = OpenAiChatCompletions.request(settings, "system instruction", "public synthetic case", 0.2) { url ->
            connection = FakeConnection(url, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
            connection
        }

        assertEquals("ok", content)
        assertEquals("https://example.test/v1/chat/completions", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("Bearer $SYNTHETIC_AUTHORIZATION_VALUE", connection.getRequestProperty("Authorization"))
        assertTrue(connection.sent.toString("UTF-8").contains("public synthetic case"))
        assertFalse(connection.sent.toString("UTF-8").contains("family-vault"))
    }

    @Test
    fun `HTTP failures do not surface raw provider content`() {
        val failure = runCatching {
            OpenAiChatCompletions.request(settings, "system", "public synthetic case", 0.2) { url ->
                FakeConnection(url, 401, "secret response body")
            }
        }.exceptionOrNull()

        assertTrue(failure is AiChatHttpException)
        assertEquals(401, (failure as AiChatHttpException).status)
        assertFalse(failure.message.orEmpty().contains("secret response body"))
    }

    private class FakeConnection(url: URL, private val status: Int, private val reply: String) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(reply.toByteArray())
        override fun getResponseCode() = status
    }

    private companion object {
        val VALID_CONTENT = """
            {"opportunities":[{
              "title":"用现有纸张试两种折法",
              "explanation":"由孩子选择要不要试，折完在家里比一比，不布置任务。",
              "whyNow":"孩子正在主动折纸飞机",
              "ecosystem":"making","primaryGoal":"CHILD","childPull":true,
              "timeMinutes":20,"costBand":"FREE","caregiverEnergy":"LOW",
              "travelMinutes":0,"confidence":0.7,"minAge":7,"maxAge":12,
              "naturalEntry":true,"interventionPressure":"LOW"
            }]}
        """.trimIndent()
    }
}
