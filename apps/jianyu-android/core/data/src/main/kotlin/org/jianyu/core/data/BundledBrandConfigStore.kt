package org.jianyu.core.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jianyu.core.domain.BrandConfig

/** Loads the same public BrandConfig document used by forks and other FOE clients. */
class BundledBrandConfigStore(
    private val context: Context,
) {
    fun load(assetPath: String): BrandConfig {
        val value = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        return parseBrandConfig(value)
    }
}

/** Pure parser kept separate from Android asset access so forks can test their identity document. */
internal fun parseBrandConfig(value: String): BrandConfig {
    require(value.length <= MAX_CONFIG_CHARS) { "BrandConfig exceeds the bundled size limit" }
    val root = Json.parseToJsonElement(value) as? JsonObject ?: error("BrandConfig must be a JSON object")
    require(root.string("schema") == "org.foe.brand-config/v1") { "Unsupported BrandConfig schema" }
    return BrandConfig(
        id = root.requiredString("id"),
        displayName = root.requiredString("displayNameZhCN"),
        romanizedName = root.requiredString("productName"),
        engineName = root.requiredString("engineName"),
        tagline = root.requiredString("taglineZhCN"),
        hero = root.requiredString("heroZhCN"),
        mission = root.requiredString("missionZhCN"),
        privacyUrl = root.string("privacyUrl"),
        sourceUrl = root.string("sourceUrl"),
        features = root.booleanMap("features"),
        defaultPolicies = root.stringMap("defaultPolicies"),
    )
}

private fun JsonObject.requiredString(key: String) = requireNotNull(string(key)) { "BrandConfig.$key is required" }
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.booleanMap(key: String) = (this[key] as? JsonObject).orEmpty().mapValues { (_, value) ->
    (value as? JsonPrimitive)?.booleanOrNull ?: false
}
private fun JsonObject.stringMap(key: String) = (this[key] as? JsonObject).orEmpty().mapValues { (_, value) ->
    (value as? JsonPrimitive)?.contentOrNull.orEmpty()
}

private const val MAX_CONFIG_CHARS = 100_000
