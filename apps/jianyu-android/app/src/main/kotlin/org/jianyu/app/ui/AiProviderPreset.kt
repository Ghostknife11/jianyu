package org.jianyu.app.ui

internal data class AiProviderPreset(
    val id: String,
    val label: String,
    val providerName: String,
    val baseUrl: String,
    val exampleModel: String,
    val guidance: String,
    val termsUrl: String? = null,
    val privacyUrl: String? = null,
    val custom: Boolean = false,
)

internal val jianyuAiProviderPresets = listOf(
    AiProviderPreset(
        id = "openai",
        label = "OpenAI",
        providerName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        exampleModel = "gpt-4.1-mini",
        guidance = "使用你自己的 OpenAI API 密钥；模型可按账户实际可用情况修改。",
        termsUrl = "https://openai.com/policies/services-agreement/",
        privacyUrl = "https://openai.com/business-data/",
    ),
    AiProviderPreset(
        id = "deepseek",
        label = "DeepSeek",
        providerName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        exampleModel = "deepseek-flash",
        guidance = "使用你自己的 DeepSeek API 密钥；模型可按账户实际可用情况修改。",
        termsUrl = "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html",
        privacyUrl = "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html",
    ),
    AiProviderPreset(
        id = "custom",
        label = "其他兼容服务",
        providerName = "OpenAI-compatible",
        baseUrl = "",
        exampleModel = "",
        guidance = "填写服务商提供的 HTTPS 地址、模型 ID 和 API 密钥。",
        custom = true,
    ),
)

internal fun matchAiProviderPreset(providerName: String?, baseUrl: String?): AiProviderPreset =
    jianyuAiProviderPresets.firstOrNull { preset ->
        !preset.custom && (
            preset.baseUrl.equals(baseUrl?.trimEnd('/'), ignoreCase = true) ||
                preset.providerName.equals(providerName, ignoreCase = true)
            )
    } ?: jianyuAiProviderPresets.last { it.custom }
