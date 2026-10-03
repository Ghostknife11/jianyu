package org.jianyu.core.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class WorldBriefProviderSettings(
    val providerName: String,
    val endpoint: String,
    val apiKey: String = "",
) {
    fun validate() {
        require(providerName.isNotBlank()) { "请填写世界信息服务名称" }
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
        require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "世界信息地址必须是有效的 HTTPS 地址" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "世界信息地址不能包含账号、查询参数或片段"
        }
    }
}

class WorldBriefProviderSettingsStore(context: Context) {
    private val store = AndroidKeystoreJsonStore(
        context = context,
        fileName = "world-brief-provider.vault",
        format = FORMAT,
        keyAlias = KEY_ALIAS,
        serializer = WorldBriefProviderSettings.serializer(),
        validate = WorldBriefProviderSettings::validate,
    )

    fun load(): WorldBriefProviderSettings? = store.load()
    fun save(settings: WorldBriefProviderSettings) = store.save(settings)
    fun erase() = store.erase()

    private companion object {
        const val FORMAT = "org.jianyu.world-brief-provider-settings/v1"
        const val KEY_ALIAS = "org.jianyu.world-brief-provider-settings.primary"
    }
}
