package org.jianyu.core.data

import android.content.Context
import org.jianyu.core.model.OpportunityPack
import org.jianyu.core.model.OpportunityPackCodec

/** Reads declarative, non-executable Packs bundled with an Android client. */
class BundledOpportunityPackStore(
    private val context: Context,
) {
    fun load(assetPath: String): OpportunityPack {
        val value = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        require(value.length <= MAX_PACK_CHARS) { "Opportunity Pack exceeds the bundled size limit" }
        val pack = OpportunityPackCodec.decode(value)
        require(pack.schema == "org.foe.pack/v1") { "Unsupported Opportunity Pack schema" }
        require(pack.id.isNotBlank() && pack.version.isNotBlank() && pack.publisher.isNotBlank()) { "Invalid Opportunity Pack manifest" }
        require(pack.opportunities.size <= MAX_ENTRIES) { "Opportunity Pack contains too many entries" }
        return pack
    }

    private companion object {
        const val MAX_PACK_CHARS = 1_000_000
        const val MAX_ENTRIES = 2_000
    }
}
