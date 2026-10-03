package org.jianyu.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.jianyu.core.domain.ProviderCapability
import org.jianyu.core.domain.PublicWorldQuery
import org.jianyu.core.domain.WorldBriefProvider
import org.jianyu.core.domain.SourceFailureException
import org.jianyu.core.domain.SourceFailureReason
import org.jianyu.core.domain.externalSourceHost
import org.jianyu.core.model.Verification
import org.jianyu.core.model.WorldBrief
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.io.IOException
import java.io.Reader

class HttpWorldBriefProvider internal constructor(
    private val loadSettings: () -> WorldBriefProviderSettings?,
    private val openConnection: (URI) -> HttpURLConnection,
) : WorldBriefProvider {
    constructor(settingsStore: WorldBriefProviderSettingsStore) : this(
        loadSettings = settingsStore::load,
        openConnection = { uri -> uri.toURL().openConnection() as HttpURLConnection },
    )

    override val id = "org.foe.world-brief.http"

    override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
        capability.requireValid(id, kind, "fetch-public-world-brief", setOf("public-world-query"))
        val settings = loadSettings() ?: return emptyList()
        settings.validate()
        val endpoint = buildEndpoint(settings.endpoint, query)
        val connection = openConnection(endpoint)
        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            if (settings.apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            val status = connection.responseCode
            if (status !in 200..299) {
                val reason = when (status) {
                    401, 403 -> SourceFailureReason.AUTHENTICATION_REJECTED
                    429 -> SourceFailureReason.RATE_LIMITED
                    400, 404, 413, 422 -> SourceFailureReason.REQUEST_REJECTED
                    else -> throw IllegalStateException("World Brief HTTP status $status")
                }
                throw SourceFailureException(reason)
            }
            try {
                val body = connection.inputStream.bufferedReader().use { it.readLimited(MAX_RESPONSE_CHARS) }
                parseWorldBriefResponse(body)
            } catch (network: IOException) {
                throw network
            } catch (_: Exception) {
                throw SourceFailureException(SourceFailureReason.INVALID_RESPONSE)
            }
        } catch (_: SocketTimeoutException) {
            throw SourceFailureException(SourceFailureReason.RESPONSE_TIMED_OUT)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildEndpoint(base: String, query: PublicWorldQuery): URI {
        val params = listOfNotNull(
            query.region?.takeIf { it.isNotBlank() }?.let { "region=${it.urlEncode()}" },
            query.timeWindow?.takeIf { it.isNotBlank() }?.let { "timeWindow=${it.urlEncode()}" },
            "language=${query.language.urlEncode()}",
            "categories=${query.categories.joinToString(",").urlEncode()}",
        ).joinToString("&")
        return URI("${base.trim()}${if ('?' in base) '&' else '?'}$params")
    }

    private fun String.urlEncode() = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}

private fun Reader.readLimited(maxChars: Int): String {
    val buffer = CharArray(8_192)
    val output = StringBuilder()
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(output.length + read <= maxChars) { "World Brief 响应过大" }
        output.append(buffer, 0, read)
    }
    return output.toString()
}

internal fun parseWorldBriefResponse(body: String): List<WorldBrief> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: error("World Brief 响应不是 JSON 对象")
    val schema = root.string("schema")
    require(schema == "org.foe.world-brief-feed/v1") { "不支持的 World Brief feed schema" }
    val records = root["briefs"] as? JsonArray ?: error("World Brief 响应缺少 briefs")
    return records.take(100).mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.string("id")?.trim().orEmpty()
        val title = item.string("title")?.trim().orEmpty()
        val summary = item.string("summary")?.trim().orEmpty()
        val topics = item.requiredMatchingTerms("topics") ?: return@mapNotNull null
        val matchTerms = item.optionalMatchingTerms("matchTerms") ?: return@mapNotNull null
        val sourceTitle = item.string("sourceTitle")?.trim().orEmpty()
        val retrievedAt = item.requiredInstant("retrievedAt") ?: return@mapNotNull null
        val startsAt = item.optionalInstant("startsAt") ?: return@mapNotNull null
        val expiresAt = item.optionalInstant("expiresAt") ?: return@mapNotNull null
        val timeMinutes = item.optionalInt("timeMinutes", 60) ?: return@mapNotNull null
        val travelMinutes = item.optionalInt("travelMinutes", 30) ?: return@mapNotNull null
        val minAge = item.optionalInt("minAge", 4) ?: return@mapNotNull null
        val maxAge = item.optionalInt("maxAge", 15) ?: return@mapNotNull null
        val costBand = item.enumOrDefault("costBand", CostBand.LOW) ?: return@mapNotNull null
        val caregiverEnergy = item.enumOrDefault("caregiverEnergy", EnergyBand.MEDIUM) ?: return@mapNotNull null
        val verification = item.string("verification")?.let { raw ->
            enumValues<Verification>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: return@mapNotNull null
        val bookingRequired = item.optionalBoolean("bookingRequired", false) ?: return@mapNotNull null
        val trackingWarning = item.optionalBoolean("trackingWarning", false) ?: return@mapNotNull null
        val verificationNotes = item.optionalStringList("verificationNotes", 8, 180) ?: return@mapNotNull null
        val sponsorship = if ("sponsorship" in item) {
            item.string("sponsorship")?.trim()?.takeIf(String::isNotBlank)?.take(160) ?: return@mapNotNull null
        } else {
            null
        }
        if (id.isBlank() || title.isBlank() || summary.isBlank() || topics.isEmpty() || sourceTitle.isBlank() || retrievedAt.isBlank()) {
            return@mapNotNull null
        }
        if (timeMinutes !in 0..1440 || travelMinutes !in 0..1440 || minAge !in 0..18 || maxAge !in 4..25 || minAge > maxAge) {
            return@mapNotNull null
        }
        WorldBrief(
            id = id.take(160),
            title = title.take(180),
            summary = summary.take(1200),
            topics = topics,
            matchTerms = matchTerms,
            region = item.string("region")?.trim()?.take(120) ?: "未指定地区",
            startsAt = startsAt.takeIf(String::isNotEmpty),
            expiresAt = expiresAt.takeIf(String::isNotEmpty),
            sourceTitle = sourceTitle.take(180),
            sourceUrl = item.string("sourceUrl")?.takeIf(::isSafeHttpsUrl),
            retrievedAt = retrievedAt,
            verification = verification,
            timeMinutes = timeMinutes,
            costBand = costBand,
            caregiverEnergy = caregiverEnergy,
            travelMinutes = travelMinutes,
            minAge = minAge,
            maxAge = maxAge,
            bookingRequired = bookingRequired,
            verificationNotes = verificationNotes,
            sponsorship = sponsorship,
            trackingWarning = trackingWarning,
        )
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.requiredMatchingTerms(key: String): List<String>? =
    optionalMatchingTerms(key)?.takeIf { it.isNotEmpty() }
private fun JsonObject.optionalMatchingTerms(key: String): List<String>? {
    if (key !in this) return emptyList()
    val values = this[key] as? JsonArray ?: return null
    if (values.size > 20) return null
    return values.map { element ->
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val term = primitive.content.trim()
        if (term.length !in 2..80) return null
        term
    }
}
private fun JsonObject.optionalInt(key: String, default: Int): Int? =
    if (key !in this) default else (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean? =
    if (key !in this) default else (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.requiredInstant(key: String): String? = string(key)?.takeIf(::isInstant)
private fun JsonObject.optionalInstant(key: String): String? = when {
    key !in this -> ""
    else -> string(key)?.takeIf(::isInstant)
}
private fun JsonObject.optionalStringList(key: String, maxItems: Int, maxLength: Int): List<String>? {
    if (key !in this) return emptyList()
    val values = this[key] as? JsonArray ?: return null
    if (values.size > maxItems) return null
    return values.map { element ->
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.take(maxLength) ?: return null
    }
}
private inline fun <reified T : Enum<T>> JsonObject.enumOrDefault(key: String, default: T): T? {
    if (key !in this) return default
    val raw = string(key) ?: return null
    return enumValues<T>().firstOrNull { it.name.equals(raw.replace('-', '_'), ignoreCase = true) }
}
private fun isInstant(value: String) = runCatching { Instant.parse(value); true }.getOrDefault(false)
private fun isSafeHttpsUrl(value: String) = externalSourceHost(value) != null
