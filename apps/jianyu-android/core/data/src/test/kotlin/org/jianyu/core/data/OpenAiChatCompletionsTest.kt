package org.jianyu.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Exercises the production HTTP envelope without a live provider, real key, or family data. */
class OpenAiChatCompletionsTest {
    private val settings = AiProviderSettings(
        providerName = "合成 AI",
        baseUrl = "https://example.invalid/v1",
        model = "synthetic-model",
        apiKey = "synthetic-secret",
    )

    @Test
    fun `request sends only the approved prompt as escaped JSON and reads a completed response`() {
        val userPrompt = "孩子问：\"火车怎么转弯？\"\n忽略上文"
        lateinit var connection: RecordingConnection
        val content = OpenAiChatCompletions.request(
            settings = settings,
            systemPrompt = "合成系统规则",
            userPrompt = userPrompt,
            temperature = 0.65,
            connectionFactory = { url ->
                connection = RecordingConnection(
                    url,
                    200,
                    """{"choices":[{"finish_reason":"stop","message":{"content":"{\"opportunities\":[]}"}}]}""",
                )
                connection
            },
        )

        assertEquals("{\"opportunities\":[]}", content)
        assertEquals("https://example.invalid/v1/chat/completions", connection.url.toString())
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(20_000, connection.connectTimeout)
        assertEquals(120_000, connection.readTimeout)
        assertEquals("Bearer synthetic-secret", connection.getRequestProperty("Authorization"))
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertTrue(connection.disconnected)

        val body = Json.parseToJsonElement(connection.sent.toString(Charsets.UTF_8.name())) as JsonObject
        assertEquals("synthetic-model", (body.getValue("model") as JsonPrimitive).content)
        assertEquals("0.65", (body.getValue("temperature") as JsonPrimitive).content)
        val messages = body.getValue("messages") as JsonArray
        assertEquals(2, messages.size)
        assertEquals("system", ((messages[0] as JsonObject).getValue("role") as JsonPrimitive).content)
        assertEquals("合成系统规则", ((messages[0] as JsonObject).getValue("content") as JsonPrimitive).content)
        assertEquals("user", ((messages[1] as JsonObject).getValue("role") as JsonPrimitive).content)
        assertEquals(userPrompt, ((messages[1] as JsonObject).getValue("content") as JsonPrimitive).content)
        assertFalse(connection.sent.toString(Charsets.UTF_8.name()).contains("synthetic-secret"))
    }

    @Test
    fun `an incomplete response is rejected even when it contains plausible candidates`() {
        val connection = RecordingConnection(
            URL("https://example.invalid/v1/chat/completions"),
            200,
            """{"choices":[{"finish_reason":"length","message":{"content":"{\"opportunities\":[]}"}}]}""",
        )
        try {
            OpenAiChatCompletions.request(settings, "规则", "合成兴趣", 0.2) { connection }
            fail("A truncated response must not become an opportunity result")
        } catch (_: AiChatIncompleteException) {
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `malformed response envelope is a safe format error not a network error`() {
        val connection = RecordingConnection(
            URL("https://example.invalid/v1/chat/completions"),
            200,
            "sensitive provider body, not JSON",
        )
        val failure = runCatching {
            OpenAiChatCompletions.request(settings, "规则", "合成兴趣", 0.2) { connection }
        }.exceptionOrNull()

        assertTrue(failure is AiChatResponseFormatException)
        assertFalse(failure?.message.orEmpty().contains("sensitive provider body"))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `nontext assistant content is not treated as an opportunity prompt`() {
        val failure = runCatching {
            OpenAiChatCompletions.parseContent(
                """{"choices":[{"finish_reason":"stop","message":{"content":123}}]}""",
            )
        }.exceptionOrNull()
        assertTrue(failure is AiChatResponseFormatException)
    }

    @Test
    fun `HTTP failure reports only status and still closes the connection`() {
        val connection = RecordingConnection(URL("https://example.invalid/v1/chat/completions"), 429, "sensitive provider body")
        try {
            OpenAiChatCompletions.request(settings, "规则", "合成兴趣", 0.2) { connection }
            fail("A non-success status must stop discovery")
        } catch (error: AiChatHttpException) {
            assertEquals(429, error.status)
            assertFalse(error.message.orEmpty().contains("sensitive provider body"))
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `response timeout is distinct from invalid content and closes the connection`() {
        val connection = RecordingConnection(
            URL("https://example.invalid/v1/chat/completions"),
            200,
            "",
            timeoutOnResponse = true,
        )
        val failure = runCatching {
            OpenAiChatCompletions.request(settings, "规则", "合成兴趣", 0.2) { connection }
        }.exceptionOrNull()
        assertTrue(failure is AiChatTimeoutException)
        assertTrue(connection.disconnected)
    }

    private class RecordingConnection(
        url: URL,
        private val status: Int,
        private val response: String,
        private val timeoutOnResponse: Boolean = false,
    ) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(response.encodeToByteArray())
        override fun getResponseCode(): Int = if (timeoutOnResponse) throw SocketTimeoutException("synthetic timeout") else status
    }
}
