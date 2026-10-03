package org.jianyu.core.data

import android.content.Context
import kotlinx.serialization.Serializable
import org.jianyu.core.domain.OpaqueSyncObjectId
import java.net.URI
import java.util.Base64

@Serializable
data class FolderSyncSettings(
    val treeUri: String,
    val displayName: String,
    val deviceId: String,
    val keyId: String,
    val keyMaterial: String,
) {
    fun validate() {
        val uri = runCatching { URI(treeUri) }.getOrElse { throw IllegalArgumentException("Folder URI is invalid") }
        require(uri.scheme == "content" && treeUri.length <= 2_048) { "Folder URI must be a bounded content URI" }
        require(displayName.length in 1..120 && displayName.none(Char::isISOControl)) { "Folder display name is invalid" }
        require(DEVICE_ID.matches(deviceId)) { "Sync device ID is invalid" }
        require(keyMaterial.length in 40..64) { "Folder sync key encoding is invalid" }
        householdKey()
    }

    override fun toString(): String =
        "FolderSyncSettings(treeUri=[REDACTED], displayName=$displayName, deviceId=$deviceId, keyId=$keyId, keyMaterial=[REDACTED])"

    fun householdKey(): HouseholdSyncKey {
        val bytes = runCatching { Base64.getUrlDecoder().decode(keyMaterial) }
            .getOrElse { throw IllegalArgumentException("Folder sync key encoding is invalid") }
        return try {
            HouseholdSyncKey.fromBytes(keyId, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{16,128}")

        fun create(treeUri: String, displayName: String): FolderSyncSettings {
            val key = HouseholdSyncKey.generate()
            val bytes = key.copyMaterial()
            return try {
                FolderSyncSettings(
                    treeUri = treeUri,
                    displayName = displayName.trim(),
                    deviceId = OpaqueSyncObjectId.generate().value,
                    keyId = key.keyId,
                    keyMaterial = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                ).also(FolderSyncSettings::validate)
            } finally {
                bytes.fill(0)
            }
        }
    }
}

class FolderSyncSettingsStore(context: Context) {
    private val store = AndroidKeystoreJsonStore(
        context = context,
        fileName = "folder-sync-settings.vault",
        format = FORMAT,
        keyAlias = KEY_ALIAS,
        serializer = FolderSyncSettings.serializer(),
        validate = FolderSyncSettings::validate,
    )

    fun load(): FolderSyncSettings? = store.load()
    fun save(settings: FolderSyncSettings) = store.save(settings)
    fun erase() = store.erase()

    private companion object {
        const val FORMAT = "org.jianyu.folder-sync-settings/v1"
        const val KEY_ALIAS = "org.jianyu.folder-sync-settings.primary"
    }
}
