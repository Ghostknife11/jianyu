package org.jianyu.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrandConfigParserTest {
    @Test
    fun `keeps tagline hero and mission as separate identity fields`() {
        val config = parseBrandConfig(validConfig)

        assertEquals("测试品牌", config.displayName)
        assertEquals("Family Opportunity Engine", config.engineName)
        assertEquals("多看见几扇门，少规定一条路。", config.tagline)
        assertEquals("从一隅，看见更多可能。", config.hero)
        assertEquals("让机会更容易被看见。", config.mission)
    }

    @Test
    fun `requires every identity field instead of silently substituting another one`() {
        val withoutHero = validConfig.replace("\"heroZhCN\": \"从一隅，看见更多可能。\",", "")

        assertThrows(IllegalArgumentException::class.java) {
            parseBrandConfig(withoutHero)
        }
    }

    private val validConfig = """
        {
          "schema": "org.foe.brand-config/v1",
          "id": "org.example.brand",
          "productName": "Example",
          "displayNameZhCN": "测试品牌",
          "engineName": "Family Opportunity Engine",
          "taglineZhCN": "多看见几扇门，少规定一条路。",
          "heroZhCN": "从一隅，看见更多可能。",
          "missionZhCN": "让机会更容易被看见。"
        }
    """.trimIndent()
}
