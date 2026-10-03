package org.jianyu.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.data.FolderSyncSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Exercises only synthetic data on the disposable UI-test AVD. */
@RunWith(AndroidJUnit4::class)
class FolderSyncStartupSafetyTest {
    @Test
    fun unreadableFolderSettingsStayVisibleAndUntouchedUntilRetrySucceeds() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val vaultFile = File(app.filesDir, "family.vault")
        val settingsFile = File(app.filesDir, "folder-sync-settings.vault")
        check(!vaultFile.exists() && !settingsFile.exists()) { "Disposable test AVD unexpectedly contains family data" }
        val invalidSettings = "synthetic unreadable folder settings".encodeToByteArray()
        try {
            app.vaultRepository.save(
                FamilyState(
                    household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                    members = emptyList(),
                    children = emptyList(),
                ),
            )
            settingsFile.writeBytes(invalidSettings)
            val viewModel = MainViewModel(app)
            val failed = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertNotNull(failed.family)
            assertTrue(failed.folderSyncSettingsUnreadable)
            assertFalse(failed.folderSyncConfigured)
            assertArrayEquals(invalidSettings, settingsFile.readBytes())

            check(settingsFile.delete()) { "Could not remove test-only invalid settings" }
            viewModel.retryOpenSyncFolderSettings()
            val retried = withTimeout(5_000) { viewModel.state.first { !it.folderSyncSettingsUnreadable } }
            assertFalse(retried.folderSyncConfigured)
            assertNotNull(retried.family)
        } finally {
            if (settingsFile.exists()) check(settingsFile.delete()) { "Could not remove test-only invalid settings" }
            app.folderSyncSettingsStore.erase()
            app.vaultRepository.erase()
        }
    }

    @Test
    fun explicitDiscardOfUnreadableFolderSettingsKeepsLocalFamilyVault() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val vaultFile = File(app.filesDir, "family.vault")
        val settingsFile = File(app.filesDir, "folder-sync-settings.vault")
        check(!vaultFile.exists() && !settingsFile.exists()) { "Disposable test AVD unexpectedly contains family data" }
        try {
            app.vaultRepository.save(
                FamilyState(
                    household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                    members = emptyList(),
                    children = emptyList(),
                ),
            )
            settingsFile.writeText("synthetic unreadable folder settings")
            val viewModel = MainViewModel(app)
            val failed = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertTrue(failed.folderSyncSettingsUnreadable)

            viewModel.disconnectSyncFolder()
            val disconnected = withTimeout(5_000) {
                viewModel.state.first { !it.folderSyncSettingsUnreadable }
            }
            assertFalse(settingsFile.exists())
            assertNotNull(disconnected.family)
            assertNotNull(app.vaultRepository.load())
        } finally {
            if (settingsFile.exists()) check(settingsFile.delete()) { "Could not remove test-only invalid settings" }
            app.folderSyncSettingsStore.erase()
            app.vaultRepository.erase()
        }
    }

    @Test
    fun lostKeystoreAliasDoesNotCreateReplacementKeyWhileReadingOldSettings() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val settingsFile = File(app.filesDir, "folder-sync-settings.vault")
        check(!settingsFile.exists()) { "Disposable test AVD unexpectedly contains folder settings" }
        val alias = "org.jianyu.folder-sync-settings.primary"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            app.folderSyncSettingsStore.save(
                FolderSyncSettings.create("content://documents/tree/primary%3ASynthetic", "虚构文件夹"),
            )
            val original = settingsFile.readBytes()
            assertTrue(keyStore.containsAlias(alias))
            keyStore.deleteEntry(alias)

            assertTrue(runCatching { app.folderSyncSettingsStore.load() }.isFailure)
            assertFalse(keyStore.containsAlias(alias))
            assertArrayEquals(original, settingsFile.readBytes())
        } finally {
            app.folderSyncSettingsStore.erase()
        }
    }

    @Test
    fun unreadableFolderSettingsDoNotBlockExplicitFamilyVaultErasure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val vaultFile = File(app.filesDir, "family.vault")
        val settingsFile = File(app.filesDir, "folder-sync-settings.vault")
        check(!vaultFile.exists() && !settingsFile.exists()) { "Disposable test AVD unexpectedly contains family data" }
        try {
            app.vaultRepository.save(
                FamilyState(
                    household = Household("synthetic-household", "虚构家庭", "2026-09-27T00:00:00Z"),
                    members = emptyList(),
                    children = emptyList(),
                ),
            )
            settingsFile.writeText("synthetic unreadable folder settings")
            val viewModel = MainViewModel(app)
            withTimeout(5_000) { viewModel.state.first { !it.loading && it.folderSyncSettingsUnreadable } }

            viewModel.eraseVault()
            val erased = withTimeout(5_000) { viewModel.state.first { !it.loading && it.family == null } }
            assertFalse(erased.vaultOpenFailed)
            assertFalse(vaultFile.exists())
            assertFalse(settingsFile.exists())
        } finally {
            app.folderSyncSettingsStore.erase()
            app.vaultRepository.erase()
        }
    }
}
