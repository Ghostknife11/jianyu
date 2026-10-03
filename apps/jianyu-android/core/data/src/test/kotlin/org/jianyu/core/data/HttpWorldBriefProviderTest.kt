package org.jianyu.core.data

import kotlinx.coroutines.runBlocking
import org.jianyu.core.domain.DefaultOpportunityPolicy
import org.jianyu.core.domain.FamilyOpportunityEngine
import org.jianyu.core.domain.OpportunityDiscoveryRequest
import org.jianyu.core.domain.PublicWorldQuery
import org.jianyu.core.domain.SourceFailureReason
import org.jianyu.core.domain.WorldBriefOpportunitySource
import org.jianyu.core.model.Verification
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.LifecycleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.time.Instant

class HttpWorldBriefProviderTest {
    @Test
    fun `configured public query becomes a locally matched World opportunity`() = runBlocking {
        var requestedUri: URI? = null
        lateinit var connection: RecordingConnection
        val provider = HttpWorldBriefProvider(
            loadSettings = { WorldBriefProviderSettings("合成服务", "https://example.test/brief", "fixture-only-key") },
            openConnection = { uri ->
                requestedUri = uri
                RecordingConnection(uri.toURL(), 200, """
                    {"schema":"org.foe.world-brief-feed/v1","briefs":[{
                      "id":"moon-evening","title":"合成观月夜","summary":"需再核实现场条件",
                      "topics":["astronomy","moon"],"matchTerms":["月亮","观月"],
                      "region":"北京","sourceTitle":"合成公开来源",
                      "sourceUrl":"https://example.test/original",
                      "retrievedAt":"2026-10-03T00:00:00Z","expiresAt":"2026-10-04T00:00:00Z",
                      "verification":"LIKELY","timeMinutes":60,"costBand":"FREE",
                      "caregiverEnergy":"LOW","travelMinutes":20,"minAge":8,"maxAge":14
                    }]}
                """.trimIndent()).also { connection = it }
            },
        )
        val query = PublicWorldQuery(region = "北京", timeWindow = "next-14-days")
        val result = FamilyOpportunityEngine(
            DefaultOpportunityPolicy(clock = { Instant.parse("2026-10-03T05:00:00Z") }),
        ).discover(
            request(), listOf(WorldBriefOpportunitySource()), provider, approvedWorldQuery = query,
        )

        assertEquals(listOf("world:moon-evening"), result.opportunities.selected.map { it.opportunity.opportunityId })
        assertTrue(result.opportunities.nothing.isNothing)
        assertTrue(result.sourceIssues.isEmpty())
        val uri = requireNotNull(requestedUri)
        assertEquals("https", uri.scheme)
        assertEquals("example.test", uri.host)
        assertEquals("/brief", uri.path)
        val fields = uri.query.split('&').map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        assertEquals(setOf("region", "timeWindow", "language", "categories"), fields.keys)
        assertEquals("北京", fields["region"])
        assertEquals("next-14-days", fields["timeWindow"])
        assertEquals("zh-CN", fields["language"])
        assertFalse(uri.toString().contains("孩子想看月亮"))
        assertFalse(uri.toString().contains("fixture-only-key"))
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.headers["Accept"])
        assertEquals("Bearer fixture-only-key", connection.headers["Authorization"])
        assertEquals(false, connection.instanceFollowRedirects)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `non success status never reads or carries a provider response body`() = runBlocking {
        lateinit var connection: RecordingConnection
        val provider = HttpWorldBriefProvider(
            loadSettings = { WorldBriefProviderSettings("合成服务", "https://example.test/brief") },
            openConnection = { uri ->
                RecordingConnection(uri.toURL(), 302, "PRIVATE SYNTHETIC RESPONSE BODY")
                    .also { connection = it }
            },
        )
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(), listOf(WorldBriefOpportunitySource()), provider,
            approvedWorldQuery = PublicWorldQuery(region = "北京", timeWindow = "next-14-days"),
        )
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals(1, result.sourceIssues.size)
        assertEquals("provider-unavailable-or-invalid-response", result.sourceIssues.first { it.sourceId == provider.id }.reasonCode)
        assertFalse(connection.inputStreamRead)
        assertFalse(connection.errorStreamRead)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test
    fun `HTTP failures retain safe reason codes without reading response bodies`() = runBlocking {
        val cases = listOf(
            401 to SourceFailureReason.AUTHENTICATION_REJECTED,
            403 to SourceFailureReason.AUTHENTICATION_REJECTED,
            429 to SourceFailureReason.RATE_LIMITED,
            400 to SourceFailureReason.REQUEST_REJECTED,
            404 to SourceFailureReason.REQUEST_REJECTED,
            422 to SourceFailureReason.REQUEST_REJECTED,
        )
        cases.forEach { (status, expected) ->
            lateinit var connection: RecordingConnection
            val provider = HttpWorldBriefProvider(
                loadSettings = { WorldBriefProviderSettings("合成服务", "https://example.test/brief") },
                openConnection = { uri -> RecordingConnection(uri.toURL(), status, "PRIVATE RESPONSE BODY").also { connection = it } },
            )
            val result = discover(provider)
            assertEquals(1, result.sourceIssues.size)
            assertEquals(expected.code, result.sourceIssues.first { it.sourceId == provider.id }.reasonCode)
            assertFalse(connection.inputStreamRead)
            assertFalse(connection.errorStreamRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun `timeout and malformed feed have distinct safe reasons`() = runBlocking {
        val settings = { WorldBriefProviderSettings("合成服务", "https://example.test/brief") }
        val timedOut = HttpWorldBriefProvider(settings) { uri ->
            RecordingConnection(uri.toURL(), 200, "", responseFailure = SocketTimeoutException("PRIVATE DETAIL"))
        }
        val invalid = HttpWorldBriefProvider(settings) { uri -> RecordingConnection(uri.toURL(), 200, "PRIVATE MALFORMED BODY") }
        assertEquals(
            SourceFailureReason.RESPONSE_TIMED_OUT.code,
            discover(timedOut).sourceIssues.first { it.sourceId == timedOut.id }.reasonCode,
        )
        assertEquals(
            SourceFailureReason.INVALID_RESPONSE.code,
            discover(invalid).sourceIssues.first { it.sourceId == invalid.id }.reasonCode,
        )
    }

    private suspend fun discover(provider: HttpWorldBriefProvider) = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
        request(), listOf(WorldBriefOpportunitySource()), provider,
        approvedWorldQuery = PublicWorldQuery(region = "北京", timeWindow = "next-14-days"),
    )

    private fun request() = OpportunityDiscoveryRequest(
        currentInterest = "孩子想看月亮",
        age = 11,
        lifecycleStage = LifecycleStage.CO_SELECT,
        goals = FamilyGoals(child = "看看月亮"),
        constraints = FamilyConstraints(),
        region = "北京",
    )

    private class RecordingConnection(
        url: URL,
        private val status: Int,
        private val body: String,
        private val responseFailure: Exception? = null,
    ) : HttpURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        var inputStreamRead = false
        var errorStreamRead = false
        var disconnected = false

        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            responseFailure?.let { throw it }
            return status
        }
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getInputStream(): InputStream {
            inputStreamRead = true
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }
        override fun getErrorStream(): InputStream {
            errorStreamRead = true
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `parser accepts a bounded attributable public record`() {
        val result = parseWorldBriefResponse(
            """
            {
              "schema":"org.foe.world-brief-feed/v1",
              "briefs":[{
                "id":"event-1",
                "title":"公开赛车体验日",
                "summary":"活动信息仍需家庭在原始来源确认。",
                "topics":["赛车","机械"],
                "matchTerms":["赛车体验","机械"],
                "region":"北京",
                "startsAt":"2026-09-20T02:00:00Z",
                "expiresAt":"2026-09-20T10:00:00Z",
                "sourceTitle":"合成测试来源",
                "sourceUrl":"https://example.test/event-1",
                "retrievedAt":"2026-09-13T00:00:00Z",
                "verification":"VERIFIED",
                "timeMinutes":90,
                "costBand":"FREE",
                "caregiverEnergy":"LOW",
                "travelMinutes":20,
                "minAge":8,
                "maxAge":14,
                "bookingRequired":true,
                "verificationNotes":["确认名额","确认成人陪同要求"],
                "sponsorship":"由合成机构赞助",
                "trackingWarning":true
              }]
            }
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        assertEquals(listOf("赛车", "机械"), result.single().topics)
        assertEquals(listOf("赛车体验", "机械"), result.single().matchTerms)
        assertEquals(Verification.VERIFIED, result.single().verification)
        assertEquals(90, result.single().timeMinutes)
        assertEquals(CostBand.FREE, result.single().costBand)
        assertEquals(EnergyBand.LOW, result.single().caregiverEnergy)
        assertTrue(result.single().bookingRequired)
        assertEquals(listOf("确认名额", "确认成人陪同要求"), result.single().verificationNotes)
        assertEquals("由合成机构赞助", result.single().sponsorship)
        assertTrue(result.single().trackingWarning)
    }

    @Test
    fun `parser drops incomplete records and strips unsafe source urls`() {
        val result = parseWorldBriefResponse(
            """
            {
              "schema":"org.foe.world-brief-feed/v1",
              "briefs":[
                {"id":"missing-topics","title":"无主题","summary":"不应进入","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z"},
                {"id":"safe-record","title":"机械展","summary":"公开摘要","topics":["机械"],"region":"上海","sourceTitle":"测试","sourceUrl":"javascript:alert(1)","retrievedAt":"2026-09-13T00:00:00Z","verification":"IDEA"},
                {"id":"credential-link","title":"机械展","summary":"公开摘要","topics":["机械"],"region":"上海","sourceTitle":"测试","sourceUrl":"https://name:secret@example.test/event","retrievedAt":"2026-09-13T00:00:00Z","verification":"IDEA"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, result.size)
        assertEquals(listOf("safe-record", "credential-link"), result.map { it.id })
        assertTrue(result.all { it.sourceUrl == null && it.topics.isNotEmpty() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parser rejects unknown feed schema`() {
        parseWorldBriefResponse("""{"schema":"unknown","briefs":[]}""")
    }

    @Test
    fun `parser drops present but malformed practical fields instead of inventing defaults`() {
        val result = parseWorldBriefResponse(
            """
            {
              "schema":"org.foe.world-brief-feed/v1",
              "briefs":[
                {"id":"bad-time","title":"活动","summary":"摘要","topics":["机械"],"region":"上海","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"VERIFIED","timeMinutes":"很久"},
                {"id":"bad-booking","title":"活动","summary":"摘要","topics":["机械"],"region":"上海","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"VERIFIED","bookingRequired":"yes"},
                {"id":"bad-start","title":"活动","summary":"摘要","topics":["机械"],"region":"上海","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"VERIFIED","startsAt":"tomorrow"},
                {"id":"valid-defaults","title":"活动","summary":"摘要","topics":["机械"],"region":"上海","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"IDEA"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("valid-defaults"), result.map { it.id })
        assertEquals(60, result.single().timeMinutes)
        assertEquals(false, result.single().bookingRequired)
    }

    @Test
    fun `optional localized match terms keep old feeds compatible and reject malformed declarations`() {
        val result = parseWorldBriefResponse(
            """
            {"schema":"org.foe.world-brief-feed/v1","briefs":[
              {"id":"localized","title":"观月夜","summary":"公开摘要","topics":["moon"],"matchTerms":["月亮","观月"],"region":"北京","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"IDEA"},
              {"id":"legacy","title":"观月夜","summary":"公开摘要","topics":["月亮"],"region":"北京","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"IDEA"},
              {"id":"invalid","title":"观月夜","summary":"公开摘要","topics":["moon"],"matchTerms":[1],"region":"北京","sourceTitle":"测试","retrievedAt":"2026-09-13T00:00:00Z","verification":"IDEA"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("localized", "legacy"), result.map { it.id })
        assertEquals(listOf("月亮", "观月"), result.first().matchTerms)
        assertTrue(result.last().matchTerms.isEmpty())
    }
}
