package org.jianyu.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OpportunityPackCodecTest {
    @Test
    fun `pack round trip preserves public declarative fields`() {
        val pack = OpportunityPack(
            id = "pack.demo",
            version = "1.0.0",
            title = "Demo",
            publisher = "Publisher",
            license = "Apache-2.0",
            opportunities = listOf(
                PackedOpportunity(
                    opportunityId = "entry.demo",
                    triggerTerms = listOf("赛车"),
                    title = "看看真实机器",
                    explanation = "保持体验本身成立",
                    entryPoint = PackedEntryPoint("real-machines", "孩子正在主动关注"),
                    ecosystem = "现实世界",
                    goalAlignment = PackedGoalAlignment("child"),
                    childPull = true,
                    requirements = PackedRequirements(30, "free", "low", 10),
                ),
            ),
        )

        assertEquals(pack, OpportunityPackCodec.decode(OpportunityPackCodec.encode(pack)))
    }
}
