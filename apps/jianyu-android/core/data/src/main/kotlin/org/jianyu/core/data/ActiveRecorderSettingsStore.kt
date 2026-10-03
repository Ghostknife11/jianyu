package org.jianyu.core.data

import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
data class ActiveRecorderSettings(val memberId: String) {
    fun validate() {
        require(memberId.length in 1..128 && memberId.none(Char::isISOControl)) {
            "Active recorder member ID is invalid"
        }
    }
}

class ActiveRecorderSettingsStore(context: Context) {
    private val store = AndroidKeystoreJsonStore(
        context = context,
        fileName = "active-recorder.vault",
        format = FORMAT,
        keyAlias = KEY_ALIAS,
        serializer = ActiveRecorderSettings.serializer(),
        validate = ActiveRecorderSettings::validate,
    )

    fun load(): ActiveRecorderSettings? = store.load()
    fun save(settings: ActiveRecorderSettings) = store.save(settings)
    fun erase() = store.erase()

    private companion object {
        const val FORMAT = "org.jianyu.active-recorder-settings/v1"
        const val KEY_ALIAS = "org.jianyu.active-recorder-settings.primary"
    }
}
