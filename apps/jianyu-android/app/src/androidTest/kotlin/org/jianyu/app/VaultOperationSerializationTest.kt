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
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.Opportunity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Runs only on the disposable test AVD; proves two queued edits survive encrypted Vault readback. */
@RunWith(AndroidJUnit4::class)
class VaultOperationSerializationTest {
    @Test
    fun rapidIndependentCorrectionsBothSurviveEncryptedVaultReadback() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        check(repository.load() == null && app.aiSettingsStore.load() == null) {
            "Disposable test AVD unexpectedly contains family data or AI settings"
        }
        val at = Instant.now().toString()
        val birthDate = LocalDate.now().minusYears(10).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-serial-household", "虚构家庭", at),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = at),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", at),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", birthDate.year, at, birthDate.toString())),
            evidence = listOf(Evidence(
                id = "synthetic-original",
                childId = "synthetic-child",
                authorId = "synthetic-caregiver",
                stream = ContextStream.CHILD,
                kind = EvidenceKind.DIRECT_OBSERVATION,
                expression = "孩子在看火车",
                occurredAt = at,
                recordedAt = at,
                ownerId = "synthetic-child-member",
                visibility = EvidenceVisibility.GUARDIANS,
            )),
        )
        val viewModels = ViewModelStore()
        try {
            repository.save(family)
            val viewModel = MainViewModel(app)
            viewModels.put("serial-vault-writes", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null } }

            viewModel.correctEvidence("synthetic-original", "我想看站台")
            viewModel.correctEvidence("synthetic-original", "我还想知道火车怎么转弯")

            val completed = withTimeout(10_000) {
                viewModel.state.first { state ->
                    state.family?.evidence?.count { it.source == "child-correction:synthetic-original" } == 2
                }
            }
            assertEquals(2, completed.family?.events?.count { it.eventType == "evidence.child-corrected" })
            val persisted = repository.load()
            assertNotNull(persisted)
            assertEquals(
                setOf("我想看站台", "我还想知道火车怎么转弯"),
                persisted!!.evidence.filter { it.source == "child-correction:synthetic-original" }.map { it.expression }.toSet(),
            )
            assertEquals(2, persisted.events.count { it.eventType == "evidence.child-corrected" })
        } finally {
            viewModels.clear()
            repository.erase()
        }
    }

    @Test
    fun rapidDoubleTapStartsOnlyOneFormalAiRequest() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val settingsStore = app.aiSettingsStore
        check(repository.load() == null && settingsStore.load() == null) {
            "Disposable test AVD unexpectedly contains family data or AI settings"
        }
        val at = Instant.now().toString()
        val birthDate = LocalDate.now().minusYears(10).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-double-tap-household", "虚构家庭", at),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = at),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", at),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", birthDate.year, at, birthDate.toString())),
        )
        val calls = AtomicInteger(0)
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-double-tap-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                calls.incrementAndGet()
                return emptyList()
            }
        }
        val viewModels = ViewModelStore()
        try {
            repository.save(family)
            settingsStore.save(AiProviderSettings("合成 AI", "https://example.invalid/v1", "synthetic-model", "synthetic-key"))
            val viewModel = MainViewModel(app, FormalDiscoverySources(fakeAi, emptyList(), null))
            viewModels.put("single-formal-request", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }

            repeat(2) {
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
            }

            withTimeout(10_000) { viewModel.state.first { it.opportunities != null && !it.discovering } }
            assertEquals(1, calls.get())
            val persisted = repository.load()!!
            assertEquals(1, persisted.events.count { it.eventType == "provider.disclosure-approved" })
            assertEquals(1, persisted.events.count { it.eventType == "provider.context-disclosed" })
        } finally {
            viewModels.clear()
            settingsStore.erase()
            repository.erase()
        }
    }
}
