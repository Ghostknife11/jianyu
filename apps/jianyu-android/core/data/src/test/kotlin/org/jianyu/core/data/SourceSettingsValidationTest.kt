package org.jianyu.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceSettingsValidationTest {
    @Test
    fun `AI settings use the same credential name as the UI`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AiProviderSettings("测试来源", "https://example.org/v1", "example-model", "").validate()
        }
        assertEquals("请填写 API 密钥", error.message)
    }

    @Test
    fun `malformed source addresses have actionable messages`() {
        val ai = assertThrows(IllegalArgumentException::class.java) {
            AiProviderSettings("测试来源", "https://[", "example-model", "example-key").validate()
        }
        val world = assertThrows(IllegalArgumentException::class.java) {
            WorldBriefProviderSettings("测试来源", "https://[", "").validate()
        }
        assertEquals("AI 地址必须是有效的 HTTPS 地址", ai.message)
        assertEquals("世界信息地址必须是有效的 HTTPS 地址", world.message)
    }

    @Test
    fun `source addresses cannot hide credentials or query data`() {
        val ai = assertThrows(IllegalArgumentException::class.java) {
            AiProviderSettings("测试来源", "https://user:secret@example.org/v1", "example-model", "example-key").validate()
        }
        val world = assertThrows(IllegalArgumentException::class.java) {
            WorldBriefProviderSettings("测试来源", "https://example.org/feed?token=secret", "").validate()
        }
        assertEquals("AI 地址不能包含账号、查询参数或片段", ai.message)
        assertEquals("世界信息地址不能包含账号、查询参数或片段", world.message)
    }
}
