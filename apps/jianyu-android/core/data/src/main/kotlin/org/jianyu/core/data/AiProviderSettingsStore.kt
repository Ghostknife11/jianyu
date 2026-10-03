package org.jianyu.core.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class AiProviderSettings(
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    fun validate() {
        require(providerName.isNotBlank()) { "请填写 AI 服务名称" }
        require(model.isNotBlank()) { "请填写模型名称" }
        require(apiKey.isNotBlank()) { "请填写 API 密钥" }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "AI 地址必须是有效的 HTTPS 地址" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "AI 地址不能包含账号、查询参数或片段"
        }
    }
}

class AiProviderSettingsStore(context: Context) {
    private val store = AndroidKeystoreJsonStore(
        context = context,
        fileName = "ai-provider.vault",
        format = FORMAT,
        keyAlias = KEY_ALIAS,
        serializer = AiProviderSettings.serializer(),
        validate = AiProviderSettings::validate,
    )

    fun load(): AiProviderSettings? = store.load()
    fun save(settings: AiProviderSettings) = store.save(settings)
    fun erase() = store.erase()

    private companion object {
        const val FORMAT = "org.jianyu.ai-provider-settings/v1"
        const val KEY_ALIAS = "org.jianyu.ai-provider-settings.primary"
    }
}
