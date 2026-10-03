package org.jianyu.app

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jianyu.core.model.Child
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Uses only the disposable test AVD and fictional households. */
@RunWith(AndroidJUnit4::class)
class PortableImportPreviewTest {
    @Test
    fun importRequiresDecryptedHouseholdPreviewBeforeReplacementAndRetainsExistingVaultOnFailure() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        val current = family("synthetic-current-household", "本机虚构家庭")
        val incoming = family("synthetic-incoming-household", "恢复包虚构家庭")
        val exported = app.portableFamilyBundleCodec.create(incoming)
        val bundleFile = File(app.cacheDir, "synthetic-import-preview.foe")
        val viewModels = ViewModelStore()
        try {
            repository.save(current)
            bundleFile.writeBytes(exported.bytes)
            val viewModel = MainViewModel(app)
            viewModels.put("portable-import-preview", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family?.household?.id == current.household.id } }

            val wrongCode = (if (exported.recoveryCode.first() == 'A') "B" else "A") + exported.recoveryCode.drop(1)
            viewModel.previewPortableBundle(Uri.fromFile(bundleFile), wrongCode)
            val rejected = withTimeout(10_000) {
                viewModel.state.first { !it.portableImportChecking && it.portableImportError != null }
            }
            assertEquals("恢复码错误或恢复包已被修改", rejected.portableImportError)
            assertNull(rejected.portableImportPreview)
            assertEquals(current.household.id, repository.load()?.household?.id)

            viewModel.previewPortableBundle(Uri.fromFile(bundleFile), exported.recoveryCode)
            val preview = withTimeout(10_000) { viewModel.state.first { it.portableImportPreview != null } }
            assertEquals("本机虚构家庭", preview.portableImportPreview?.currentFamilyName)
            assertEquals("恢复包虚构家庭", preview.portableImportPreview?.incomingFamilyName)
            assertEquals(current.household.id, repository.load()?.household?.id)

            viewModel.cancelPortableImport()
            assertNull(viewModel.state.value.portableImportPreview)
            viewModel.confirmPortableImport()
            assertEquals(current.household.id, repository.load()?.household?.id)

            viewModel.previewPortableBundle(Uri.fromFile(bundleFile), exported.recoveryCode)
            withTimeout(10_000) { viewModel.state.first { it.portableImportPreview != null } }
            val changedCurrent = current.copy(household = current.household.copy(name = "本机资料已更新"))
            repository.save(changedCurrent)
            viewModel.confirmPortableImport()
            val stale = withTimeout(10_000) {
                viewModel.state.first { !it.portableImportSaving && it.portableImportError == "本机资料已变化，请重新检查恢复包" }
            }
            assertNull(stale.portableImportPreview)
            assertEquals(changedCurrent.household.name, repository.load()?.household?.name)

            viewModel.previewPortableBundle(Uri.fromFile(bundleFile), exported.recoveryCode)
            withTimeout(10_000) { viewModel.state.first { it.portableImportPreview != null } }
            viewModel.confirmPortableImport()
            val imported = withTimeout(10_000) {
                viewModel.state.first { !it.portableImportSaving && it.family?.household?.id == incoming.household.id }
            }
            assertEquals(AppRoute.TODAY, imported.route)
            assertFalse(imported.portableImportChecking)
            assertNull(imported.portableImportPreview)
            assertEquals(incoming.household.id, repository.load()?.household?.id)
            assertTrue(imported.notice.orEmpty().contains("恢复包已导入"))
        } finally {
            viewModels.clear()
            repository.erase()
            app.activeRecorderSettingsStore.erase()
            bundleFile.delete()
        }
    }

    private fun family(id: String, name: String): FamilyState {
        val at = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(10).minusDays(4)
        return FamilyState(
            household = Household(id, name, at),
            members = listOf(
                FamilyMember("$id-caregiver", "虚构家长", MemberRole.CAREGIVER, createdAt = at),
                FamilyMember("$id-child-member", "小禾", MemberRole.CHILD, "$id-child", at),
            ),
            children = listOf(Child("$id-child", "$id-child-member", "小禾", birthday.year, at, birthday.toString())),
        )
    }
}
