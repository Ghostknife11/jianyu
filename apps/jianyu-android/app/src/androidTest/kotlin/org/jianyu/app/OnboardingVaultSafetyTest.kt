package org.jianyu.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses only a disposable AVD and a deliberately invalid synthetic vault file. */
@RunWith(AndroidJUnit4::class)
class OnboardingVaultSafetyTest {
    @Test
    fun unreadableVaultCannotBeReplacedByNewFamilyAndCanBeRetried() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val vaultFile = File(app.filesDir, "family.vault")
        val pendingFile = File(app.filesDir, "family.vault.pending")
        check(!vaultFile.exists() && !pendingFile.exists()) { "Disposable test AVD unexpectedly contains family data" }
        val original = "synthetic unreadable vault; never a real family".encodeToByteArray()
        vaultFile.writeBytes(original)
        try {
            val viewModel = MainViewModel(app)
            val failed = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertTrue(failed.vaultOpenFailed)
            assertNull(failed.family)

            viewModel.setup("虚构家庭", "测试家长", "小禾", "2018-09-28")
            assertFalse(viewModel.state.value.onboardingSaving)
            assertArrayEquals(original, vaultFile.readBytes())
            assertFalse(pendingFile.exists())

            viewModel.retryOpenVault()
            val failedAgain = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertTrue(failedAgain.vaultOpenFailed)
            assertArrayEquals(original, vaultFile.readBytes())

            check(vaultFile.delete()) { "Could not remove test-only invalid vault" }
            viewModel.retryOpenVault()
            val recovered = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertFalse(recovered.vaultOpenFailed)
            assertNull(recovered.family)
        } finally {
            if (vaultFile.exists()) check(vaultFile.delete()) { "Could not remove test-only invalid vault" }
        }
    }

    @Test
    fun failedInitialWriteWithPendingFileBlocksOnboardingUntilTheTestBlockerIsRemoved() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val vaultFile = File(app.filesDir, "family.vault")
        val pendingFile = File(app.filesDir, "family.vault.pending")
        check(!vaultFile.exists() && !pendingFile.exists()) { "Disposable test AVD unexpectedly contains family data" }
        try {
            val viewModel = MainViewModel(app)
            val empty = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertFalse(empty.vaultOpenFailed)
            check(pendingFile.mkdir()) { "Could not prepare test-only write failure" }
            viewModel.setup("虚构家庭", "测试家长", "小禾", "2018-09-28")
            val failed = checkNotNull(withTimeoutOrNull(5_000) {
                viewModel.state.first { !it.onboardingSaving && it.vaultOpenFailed }
            }) { "Expected pending-write fail-closed state; actual=${viewModel.state.value}" }
            assertNull(failed.family)
            assertFalse(vaultFile.exists())
            assertTrue(pendingFile.exists())

            check(pendingFile.delete()) { "Could not remove test-only write blocker" }
            viewModel.retryOpenVault()
            checkNotNull(withTimeoutOrNull(5_000) {
                viewModel.state.first { !it.loading && !it.vaultOpenFailed }
            }) { "Expected empty-vault retry; actual=${viewModel.state.value}" }
            viewModel.setup("第一家庭", "测试家长", "小禾", "2018-09-28")
            viewModel.setup("不应写入的第二家庭", "测试家长", "小禾", "2018-09-28")
            val saved = checkNotNull(withTimeoutOrNull(5_000) { viewModel.state.first { it.family != null } }) {
                "Expected first onboarding save; actual=${viewModel.state.value}"
            }
            assertEquals("第一家庭", saved.family?.household?.name)
            assertTrue(vaultFile.exists())
        } finally {
            if (pendingFile.exists()) check(pendingFile.delete()) { "Could not remove test-only write blocker" }
            app.vaultRepository.erase()
            app.activeRecorderSettingsStore.erase()
        }
    }

    @Test
    fun pendingOnlyVaultIsNotTreatedAsAnEmptyDevice() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val vaultFile = File(app.filesDir, "family.vault")
        val pendingFile = File(app.filesDir, "family.vault.pending")
        check(!vaultFile.exists() && !pendingFile.exists()) { "Disposable test AVD unexpectedly contains family data" }
        val original = "synthetic interrupted vault write; never a real family".encodeToByteArray()
        pendingFile.writeBytes(original)
        try {
            val viewModel = MainViewModel(app)
            val failed = withTimeout(5_000) { viewModel.state.first { !it.loading } }
            assertTrue(failed.vaultOpenFailed)
            assertNull(failed.family)
            viewModel.setup("虚构家庭", "测试家长", "小禾", "2018-09-28")
            assertArrayEquals(original, pendingFile.readBytes())
            assertFalse(vaultFile.exists())
            assertTrue(runCatching { app.vaultRepository.exportEncrypted() }.isFailure)
            assertTrue(runCatching { app.vaultRepository.importEncrypted(original) }.isFailure)
            assertArrayEquals(original, pendingFile.readBytes())
        } finally {
            if (pendingFile.exists()) check(pendingFile.delete()) { "Could not remove test-only pending vault" }
        }
    }
}
