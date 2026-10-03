package org.jianyu.app

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jianyu.core.data.AiProviderSettings
import org.jianyu.core.domain.LLMProvider
import org.jianyu.core.domain.ProviderCapability
import org.jianyu.core.domain.TaskContext
import org.jianyu.core.model.Child
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.LifecycleStage
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
import java.util.concurrent.atomic.AtomicBoolean

/** Run only on the disposable UI-test AVD; refuse to touch an existing family or connection. */
@RunWith(AndroidJUnit4::class)
class DiscoveryVaultFailureTest {
    @Test
    fun failedApprovalWriteNeverCallsExternalSource() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        val pendingFile = File(app.filesDir, "family.vault.pending")
        check(repository.load() == null && aiSettings.load() == null && !pendingFile.exists()) {
            "Disposable test AVD unexpectedly contains family data or connection state"
        }
        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(8).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-approval-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", timestamp),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val sourceCalled = AtomicBoolean(false)
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-approval-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<org.jianyu.core.model.Opportunity> {
                sourceCalled.set(true)
                return emptyList()
            }
        }
        val viewModels = ViewModelStore()
        var createdFamily = false
        var createdSettings = false
        var createdBlocker = false
        try {
            repository.save(family)
            createdFamily = true
            aiSettings.save(AiProviderSettings("合成 AI", "https://127.0.0.1:9/v1", "synthetic-model", "synthetic-key"))
            createdSettings = true
            val viewModel = MainViewModel(app, FormalDiscoverySources(fakeAi, emptyList(), null))
            viewModels.put("discovery-approval-failure", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }
            assertTrue(pendingFile.mkdir())
            createdBlocker = true
            viewModel.discover(
                childId = "synthetic-child",
                expression = "我想知道火车怎么转弯",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = false,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            val failed = withTimeout(10_000) { viewModel.state.first { it.error != null && !it.discovering } }
            assertFalse(sourceCalled.get())
            assertEquals("本机保存没有完成。请检查设备存储状态后重试。", failed.error)
            assertFalse(failed.disclosureSaveRisk)
            assertEquals(family, failed.family)
            assertTrue(pendingFile.exists())
            check(pendingFile.delete()) { "Synthetic write blocker could not be removed" }
            createdBlocker = false
            assertEquals(family, repository.load())
            assertNull(failed.opportunities)
        } finally {
            viewModels.clear()
            if (createdBlocker) check(pendingFile.delete()) { "Synthetic write blocker could not be removed" }
            if (createdSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun failedContextWriteDoesNotAppearAsSavedFamilyEvidence() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        val pendingFile = File(app.filesDir, "family.vault.pending")
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(aiSettings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        check(!pendingFile.exists()) { "Disposable test AVD has an unfinished vault write" }

        val now = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(8).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-household", "虚构家庭", now),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = now),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", now),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", birthday.year, now, birthday.toString())),
        )
        val viewModels = ViewModelStore()
        var createdFamily = false
        var createdSettings = false
        var createdBlocker = false
        try {
            repository.save(family)
            createdFamily = true
            // A loopback-only endpoint prevents an accidental external request if the write guard regresses.
            aiSettings.save(AiProviderSettings("合成 AI", "https://127.0.0.1:9/v1", "synthetic-model", "synthetic-key"))
            createdSettings = true
            val viewModel = MainViewModel(app)
            viewModels.put("discovery-failure", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null } }
            viewModel.updateComposerDraft("synthetic-child", LifecycleStage.ACCOMPANY, true) {
                it.copy(expression = "我想知道火车怎么转弯")
            }

            assertTrue(pendingFile.mkdir())
            createdBlocker = true
            viewModel.discover(
                childId = "synthetic-child",
                expression = "我想知道火车怎么转弯",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = true,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )

            val failed = withTimeout(10_000) { viewModel.state.first { it.error != null && !it.discovering } }
            assertEquals("本机保存没有完成。请检查设备存储状态后重试。", failed.error)
            assertEquals(family, failed.family)
            assertTrue(pendingFile.exists())
            check(pendingFile.delete()) { "Synthetic write blocker could not be removed" }
            createdBlocker = false
            assertEquals(family, repository.load())
            assertTrue(failed.family?.evidence?.isEmpty() == true)
            assertEquals("我想知道火车怎么转弯", failed.composerDraft?.expression)
            assertNull(failed.opportunities)
            assertNull(failed.sourceEventId)
            assertFalse(failed.discovering)
        } finally {
            viewModels.clear()
            if (createdBlocker) check(pendingFile.delete()) { "Synthetic write blocker could not be removed" }
            if (createdSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun failedFinalReceiptWarnsThatTheExternalRequestMayHaveOccurred() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        val pendingFile = File(app.filesDir, "family.vault.pending")
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(aiSettings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        check(!pendingFile.exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(8).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-receipt-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", timestamp),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val sourceCalled = AtomicBoolean(false)
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-receipt-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<org.jianyu.core.model.Opportunity> {
                capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
                sourceCalled.set(true)
                check(pendingFile.mkdir()) { "Could not block the synthetic final receipt write" }
                return emptyList()
            }
        }
        val viewModels = ViewModelStore()
        var createdFamily = false
        var createdSettings = false
        try {
            repository.save(family)
            createdFamily = true
            aiSettings.save(AiProviderSettings("合成 AI", "https://127.0.0.1:9/v1", "synthetic-model", "synthetic-key"))
            createdSettings = true
            val viewModel = MainViewModel(app, FormalDiscoverySources(fakeAi, emptyList(), null))
            viewModels.put("discovery-receipt-failure", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }
            viewModel.discover(
                childId = "synthetic-child",
                expression = "我想知道火车怎么转弯",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = true,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )

            val failed = withTimeout(10_000) { viewModel.state.first { it.error != null && !it.discovering } }
            assertTrue(sourceCalled.get())
            assertEquals(postRequestLocalSaveMessage(contextPersisted = true), failed.error)
            assertTrue(failed.disclosureSaveRisk)
            assertNull(failed.opportunities)
            assertTrue(pendingFile.exists())
            check(pendingFile.delete()) { "Synthetic write blocker could not be removed" }
            val saved = requireNotNull(repository.load())
            assertEquals(saved, failed.family)
            assertTrue(saved.evidence.any { it.expression == "我想知道火车怎么转弯" })
            assertTrue(saved.events.any { it.eventType == "provider.disclosure-approved" })
            assertFalse(saved.events.any { it.eventType == "provider.context-disclosed" })
        } finally {
            viewModels.clear()
            if (pendingFile.exists()) check(pendingFile.delete()) { "Synthetic write blocker could not be removed" }
            if (createdSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }
}
