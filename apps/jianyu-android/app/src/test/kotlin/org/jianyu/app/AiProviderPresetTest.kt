package org.jianyu.app

import org.jianyu.app.ui.jianyuAiProviderPresets
import org.jianyu.app.ui.matchAiProviderPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderPresetTest {
    @Test
    fun `preset ids are unique and network endpoints are HTTPS`() {
        assertEquals(jianyuAiProviderPresets.size, jianyuAiProviderPresets.map { it.id }.distinct().size)
        jianyuAiProviderPresets.filterNot { it.custom }.forEach { preset ->
            assertTrue(preset.baseUrl.startsWith("https://"))
            assertTrue(preset.exampleModel.isNotBlank())
            assertTrue(preset.termsUrl?.startsWith("https://") == true)
            assertTrue(preset.privacyUrl?.startsWith("https://") == true)
        }
    }

    @Test
    fun `existing endpoint selects its preset while an unknown service remains custom`() {
        assertEquals("deepseek", matchAiProviderPreset("anything", "https://api.deepseek.com/").id)
        assertEquals("custom", matchAiProviderPreset("家庭模型", "https://model.example.test/v1").id)
    }
}
