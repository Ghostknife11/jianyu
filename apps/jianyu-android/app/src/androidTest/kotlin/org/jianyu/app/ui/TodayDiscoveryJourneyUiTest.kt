package org.jianyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jianyu.app.FormalDiscoverySources
import org.jianyu.app.AppRoute
import org.jianyu.app.JianyuApplication
import org.jianyu.app.MainViewModel
import org.jianyu.app.postRequestLocalSaveMessage
import org.jianyu.app.ui.theme.JianyuTheme
import org.jianyu.core.data.AiProviderSettings
import org.jianyu.core.data.WorldBriefProviderSettings
import org.jianyu.core.domain.LLMProvider
import org.jianyu.core.domain.ProviderCapability
import org.jianyu.core.domain.PublicWorldQuery
import org.jianyu.core.domain.TaskContext
import org.jianyu.core.domain.WorldBriefOpportunitySource
import org.jianyu.core.domain.WorldBriefProvider
import org.jianyu.core.model.Child
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.TombstoneTarget
import org.jianyu.core.model.Verification
import org.jianyu.core.model.WorldBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full Android composer-to-vault journey with a fake source; only the disposable UI-test AVD may run it. */
@RunWith(AndroidJUnit4::class)
class TodayDiscoveryJourneyUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun refusalWithNeutralWeatherDoesNotCallAiOrWriteObservation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val settings = app.aiSettingsStore
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(settings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(8).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-refusal-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-refusal-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-refusal-member", "小禾", MemberRole.CHILD, "synthetic-refusal-child", timestamp),
            ),
            children = listOf(Child("synthetic-refusal-child", "synthetic-refusal-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val calls = AtomicInteger()
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-refusal-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                calls.incrementAndGet()
                return listOf(candidate("refused-door", "不应出现的推荐"))
            }
        }
        val viewModels = ViewModelStore()
        var createdFamily = false
        var createdSettings = false
        try {
            repository.save(family)
            createdFamily = true
            settings.save(AiProviderSettings("合成 AI", "https://127.0.0.1:9/v1", "synthetic-model", "synthetic-key"))
            createdSettings = true
            val viewModel = MainViewModel(app, FormalDiscoverySources(fakeAi, emptyList(), null))
            viewModels.put("synthetic-refusal", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }
            composeRule.onNodeWithTag("interest-input").performTextInput("孩子明确不想再看赛车，今天下雨了")
            assertEquals("孩子明确不想再看赛车，今天下雨了", viewModel.state.value.composerDraft?.expression)
            composeRule.onNodeWithText("这次没有适合找入口的主动线索。可以先留白，或补充孩子的想法。")
                .performScrollTo().assertIsDisplayed()
            saveScreenshot("today-no-child-clue-app-shell.png")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            composeRule.onNodeWithTag("discover-action").assertIsNotEnabled()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("interest-input"))
            composeRule.onNodeWithTag("interest-input").performTextClearance()
            composeRule.onNodeWithTag("interest-input").performTextInput("家长想带孩子看赛车")
            composeRule.onNodeWithText("这次没有适合找入口的主动线索。可以先留白，或补充孩子的想法。")
                .performScrollTo().assertIsDisplayed()
            saveScreenshot("today-caregiver-plan-no-child-clue.png")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            composeRule.onNodeWithTag("discover-action").assertIsNotEnabled()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("interest-input"))
            composeRule.onNodeWithTag("interest-input").performTextClearance()
            composeRule.onNodeWithTag("interest-input").performTextInput("天气预报说今晚能看到赛车活动")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            composeRule.onNodeWithTag("discover-action").assertIsNotEnabled()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("interest-input"))
            composeRule.onNodeWithTag("interest-input").performTextClearance()
            composeRule.onNodeWithTag("interest-input").performTextInput("孩子明确不想再看赛车，今天下雨了")
            viewModel.discover(
                childId = "synthetic-refusal-child",
                expression = "孩子明确不想再看赛车，今天下雨了",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = true,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            val rejected = withTimeout(10_000) {
                viewModel.state.first { it.error == "这次没有适合找入口的主动线索。可以先留白，或补充孩子的想法。" }
            }
            assertEquals(null, rejected.opportunities)
            assertEquals(0, calls.get())
            assertEquals(family, repository.load())
            for (background in listOf("家长想带孩子看赛车", "天气预报说今晚能看到赛车活动")) {
                viewModel.startNewDiscovery()
                assertEquals(null, viewModel.state.value.error)
                viewModel.discover(
                    childId = "synthetic-refusal-child",
                    expression = background,
                    caregiverGoal = null,
                    sharedGoal = null,
                    schoolWindow = null,
                    lifeContext = null,
                    region = null,
                    constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                    childConfirmed = false,
                    persistContext = true,
                    includeRecentContext = false,
                    providerDisclosureApproved = true,
                    useOfflineDemo = false,
                )
                withTimeout(10_000) {
                    viewModel.state.first { it.error == "这次没有适合找入口的主动线索。可以先留白，或补充孩子的想法。" }
                }
                assertEquals(0, calls.get())
                assertEquals(family, repository.load())
            }
        } finally {
            viewModels.clear()
            if (createdSettings) settings.erase()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun importedUnderFourMemberKeepsVaultButCannotDiscover() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(!File(app.filesDir, "family.vault.pending").exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(3).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-under-four-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-under-four-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-under-four-member", "小禾", MemberRole.CHILD, "synthetic-under-four-child", timestamp),
            ),
            children = listOf(Child("synthetic-under-four-child", "synthetic-under-four-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val calls = AtomicInteger()
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-under-four-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                calls.incrementAndGet()
                return listOf(candidate("under-four-door", "不应出现的推荐"))
            }
        }
        val viewModels = ViewModelStore()
        var createdFamily = false
        try {
            repository.save(family)
            createdFamily = true
            val viewModel = MainViewModel(app, FormalDiscoverySources(fakeAi, emptyList(), null))
            viewModels.put("synthetic-under-four", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null } }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }
            composeRule.onNodeWithText("暂不寻找入口").assertIsDisplayed()
            composeRule.onNodeWithText("还没到适用年龄").assertIsDisplayed()
            composeRule.onNodeWithTag("interest-input").assertDoesNotExist()
            composeRule.onNodeWithTag("discover-action").assertDoesNotExist()
            saveScreenshot("today-under-four-app-shell.png")
            composeRule.runOnIdle { viewModel.navigate(AppRoute.CHILDREN) }
            composeRule.onNodeWithText("3 周岁 · 尚未进入适用阶段").assertIsDisplayed()
            composeRule.onNodeWithText("3 周岁 · 共玩").assertDoesNotExist()
            saveScreenshot("family-under-four-app-shell.png")

            viewModel.discover(
                childId = "synthetic-under-four-child",
                expression = "虚构兴趣",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = true,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            val rejected = withTimeout(10_000) { viewModel.state.first { it.error == "当前版本支持 4 岁起的家庭成员；现在不寻找入口或新建观察" } }
            assertEquals(null, rejected.opportunities)
            assertEquals(0, calls.get())
            assertEquals(family, repository.load())
        } finally {
            viewModels.clear()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun postRequestWarningStaysVisibleAtLargeTextUntilAcknowledged() {
        var visible by mutableStateOf(true)
        val message = postRequestLocalSaveMessage(contextPersisted = true)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(600.dp)) {
                        if (visible) DisclosureSaveRiskCard(message) { visible = false }
                    }
                }
            }
        }

        composeRule.onNodeWithText("这次请求可能已送出").assertIsDisplayed()
        composeRule.onNodeWithText(message).assertIsDisplayed()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "post-request-warning-large.png")
            .outputStream().use { stream ->
                check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        composeRule.onNodeWithText("知道了").performClick()
        composeRule.onNodeWithText("这次请求可能已送出").assertDoesNotExist()
    }

    @Test
    fun failedAiRetryKeepsDraftRequiresNewApprovalThenChecksAndSavesChoice() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(aiSettings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        check(!File(app.filesDir, "family.vault.pending").exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(8).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-child-member", "小禾", MemberRole.CHILD, "synthetic-child", timestamp),
            ),
            children = listOf(Child("synthetic-child", "synthetic-child-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val calls = AtomicInteger()
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-approved-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
                assertEquals("我想知道火车怎么转弯", context.currentInterest)
                if (calls.incrementAndGet() == 1) error("Synthetic first-call failure")
                return listOf(
                    candidate("safe-door", "一起看真实弯道"),
                    candidate("forced-lesson", "做十页练习").copy(
                        primaryGoal = GoalOwner.CAREGIVER,
                        childPull = false,
                        naturalEntry = false,
                        interventionPressure = RiskLevel.HIGH,
                    ),
                )
            }
        }
        val viewModels = ViewModelStore()
        var createdFamily = false
        var createdSettings = false
        try {
            repository.save(family)
            createdFamily = true
            // A loopback-only endpoint ensures the test cannot silently become an external model request.
            aiSettings.save(AiProviderSettings("合成 AI", "https://127.0.0.1:9/v1", "synthetic-model", "synthetic-key"))
            createdSettings = true
            val viewModel = MainViewModel(app, FormalDiscoverySources(fakeAi, emptyList(), null))
            viewModels.put("synthetic-discovery-journey", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }

            composeRule.setContent {
                JianyuTheme { JianyuApp(viewModel) }
            }
            composeRule.runOnIdle { viewModel.navigate(AppRoute.CHILDREN) }
            composeRule.onNodeWithText("阶段与决定权").assertIsDisplayed()
            composeRule.onNodeWithText("家长陪着选，孩子有权说不。").assertIsDisplayed()
            saveScreenshot("family-app-shell-synthetic.png")
            composeRule.runOnIdle { viewModel.navigate(AppRoute.TODAY) }
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "today-composer-synthetic.png")
                .outputStream().use { stream ->
                    check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            composeRule.onNodeWithTag("interest-input").performTextInput("我想知道火车怎么转弯")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("调用你设置的 AI 服务可能产生费用。"))
            composeRule.onNodeWithText("调用你设置的 AI 服务可能产生费用。").assertIsDisplayed()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("ai-disclosure-consent"))
            composeRule.onNodeWithTag("ai-disclosure-consent").performClick()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            composeRule.onNodeWithTag("discover-action").performClick()

            val failed = withTimeout(10_000) { viewModel.state.first { it.opportunities != null && !it.discovering } }
            assertEquals(1, calls.get())
            assertEquals("byok-llm", failed.discoverySourceIssues.single().sourceKind)
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("调整后再试"))
            composeRule.onNodeWithText("调整后再试").performClick()
            composeRule.onNodeWithTag("interest-input").assertIsDisplayed()
            assertEquals("我想知道火车怎么转弯", viewModel.state.value.composerDraft?.expression)
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("ai-disclosure-consent"))
            composeRule.onNodeWithTag("ai-disclosure-consent").assertIsOff()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            composeRule.onNodeWithTag("discover-action").assertIsNotEnabled()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("ai-disclosure-consent"))
            composeRule.onNodeWithTag("ai-disclosure-consent").performClick()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            composeRule.onNodeWithTag("discover-action").performClick()

            val discovered = withTimeout(10_000) { viewModel.state.first { it.opportunities != null && !it.discovering && it.discoverySourceIssues.isEmpty() } }
            assertEquals("byok-ai", discovered.discoveryMode)
            assertEquals(2, calls.get())
            assertEquals(1, discovered.opportunities?.selected?.size)
            assertTrue(discovered.opportunities?.rejected?.any { it.opportunity.title == "做十页练习" } == true)
            assertNotNull(discovered.opportunities?.nothing)
            assertTrue(discovered.disclosureIncluded.contains("current-interest"))
            assertTrue(discovered.disclosureExcluded.contains("names"))
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("AI 生成 · 合成 AI"))
            composeRule.onNodeWithText("AI 生成 · 合成 AI").assertIsDisplayed()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("这次就留白"))
            composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("一起试试看"))
            composeRule.onNodeWithText("一起试试看").performClick()

            val chosen = withTimeout(10_000) { viewModel.state.first { it.choiceAcknowledgementId != null && !it.choiceSaving } }
            assertEquals(1, chosen.family?.choices?.size)
            val saved = requireNotNull(repository.load())
            assertEquals("一起看真实弯道", saved.choices.single().opportunity.title)
            assertEquals("chosen", saved.choices.single().status)
            assertTrue(saved.evidence.any { it.expression == "我想知道火车怎么转弯" })
            assertTrue(saved.events.any { it.eventType == "interest.observed" })
            assertTrue(saved.events.any { it.eventType == "provider.disclosure-approved" })
            assertTrue(saved.events.any { it.eventType == "provider.context-disclosed" })
            assertTrue(saved.events.any { it.eventType == "opportunity.chosen" })
            composeRule.runOnIdle { viewModel.navigate(AppRoute.TIMELINE) }
            composeRule.onNodeWithText("走过的路").assertIsDisplayed()
            saveScreenshot("timeline-app-shell-synthetic.png")
            composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("查看记录操作"))
            composeRule.onNodeWithText("查看记录操作").performClick()
            composeRule.onNodeWithText("删除这条选择").performClick()
            composeRule.onNodeWithText("取消").performClick()
            assertEquals(1, repository.load()?.choices?.size)
            composeRule.onNodeWithText("删除这条选择").performClick()
            composeRule.onNodeWithText("确认删除").performClick()
            withTimeout(10_000) {
                viewModel.state.first { it.family?.choices?.isEmpty() == true && it.choiceDeletingId == null }
            }
            val afterDelete = requireNotNull(repository.load())
            assertTrue(afterDelete.choices.isEmpty())
            assertTrue(afterDelete.evidence.any { it.expression == "我想知道火车怎么转弯" })
            assertFalse(afterDelete.events.any { it.eventType == "opportunity.chosen" })
            assertTrue(afterDelete.events.any { it.eventType == "opportunity.choice-deleted" })
            assertTrue(afterDelete.tombstones.any { it.targetType == TombstoneTarget.CHOICE && it.targetId == saved.choices.single().id })
            assertFalse(afterDelete.events.toString().contains("一起看真实弯道"))
            composeRule.onNodeWithText("一起看真实弯道").assertDoesNotExist()
        } finally {
            viewModels.clear()
            if (createdSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun approvedPublicWorldQueryReachesLocalMatchingWithoutChildContext() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        val worldSettings = app.worldBriefSettingsStore
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(aiSettings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        check(worldSettings.load() == null) { "Disposable test AVD unexpectedly contains World Brief settings" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(9).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-world-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-world-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-world-child-member", "小禾", MemberRole.CHILD, "synthetic-world-child", timestamp),
            ),
            children = listOf(Child("synthetic-world-child", "synthetic-world-child-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val worldCalls = AtomicInteger()
        val receivedQuery = AtomicReference<PublicWorldQuery>()
        val openedUrl = AtomicReference<String?>()
        val fakeWorld = object : WorldBriefProvider {
            override val id = "synthetic-public-world"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                capability.requireValid(id, kind, "fetch-public-world-brief", setOf("public-world-query"))
                worldCalls.incrementAndGet()
                receivedQuery.set(query)
                return listOf(WorldBrief(
                    id = "fictional-rail-event",
                    title = "虚构火车展览",
                    summary = "一条只用于本机测试的虚构公共活动。",
                    topics = listOf("火车"),
                    region = query.region ?: "未指定地区",
                    startsAt = Instant.now().plusSeconds(86_400).toString(),
                    expiresAt = Instant.now().plusSeconds(604_800).toString(),
                    sourceTitle = "合成公共来源",
                    sourceUrl = "https://example.test/fictional-rail-event?ref=synthetic",
                    retrievedAt = Instant.now().toString(),
                    verification = Verification.VERIFIED,
                    timeMinutes = 20,
                    costBand = CostBand.FREE,
                    caregiverEnergy = EnergyBand.LOW,
                    travelMinutes = 0,
                    minAge = 7,
                    maxAge = 12,
                ))
            }
        }
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-world-journey-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
                return emptyList()
            }
        }
        val viewModels = ViewModelStore()
        var createdFamily = false
        var createdAiSettings = false
        var createdWorldSettings = false
        try {
            repository.save(family)
            createdFamily = true
            // Both saved endpoints are loopback-only; these injected providers never open a connection.
            aiSettings.save(AiProviderSettings("合成 AI", "https://127.0.0.1:9/v1", "synthetic-model", "synthetic-key"))
            createdAiSettings = true
            worldSettings.save(WorldBriefProviderSettings("合成世界信息", "https://127.0.0.1:9/brief"))
            createdWorldSettings = true
            val viewModel = MainViewModel(
                app,
                FormalDiscoverySources(fakeAi, listOf(WorldBriefOpportunitySource()), fakeWorld),
            )
            viewModels.put("synthetic-world-journey", viewModel)
            withTimeout(10_000) {
                viewModel.state.first { !it.loading && it.family != null && it.aiConfigured && it.worldBriefConfigured }
            }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(density, fontScale = 1.3f),
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { openedUrl.set(uri) }
                    },
                ) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }

            viewModel.discover(
                childId = "synthetic-world-child",
                expression = "我想看看火车展览",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = "虚构城区",
                constraints = org.jianyu.core.model.FamilyConstraints(30, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = false,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )

            val discovered = withTimeout(10_000) { viewModel.state.first { it.opportunities != null && !it.discovering } }
            assertEquals(1, worldCalls.get())
            val publicQuery = requireNotNull(receivedQuery.get())
            assertEquals("虚构城区", publicQuery.region)
            assertEquals("next-14-days", publicQuery.timeWindow)
            assertFalse(publicQuery.toString().contains("火车展览"))
            assertTrue(discovered.discoverySourceIssues.isEmpty())
            assertTrue(discovered.opportunities?.selected?.any { it.opportunity.title == "虚构火车展览" } == true)
            assertTrue(discovered.opportunities?.nothing?.isNothing == true)
            val saved = requireNotNull(repository.load())
            assertEquals(2, saved.events.count { it.eventType == "provider.disclosure-approved" })
            assertEquals(2, saved.events.count { it.eventType == "provider.context-disclosed" })
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("虚构火车展览"))
            composeRule.onNodeWithText("入口 · 世界线索").assertIsDisplayed()
            composeRule.onNodeWithText("虚构火车展览").assertIsDisplayed()
            saveScreenshot("today-approved-world-brief-result.png")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("查看判断与来源"))
            composeRule.onNodeWithText("查看判断与来源").performClick()
            composeRule.onNodeWithText("来源方称已核验；请再核实").assertIsDisplayed()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("打开原始来源（外部网页）"))
            composeRule.onNodeWithText("打开原始来源（外部网页）").performScrollTo().assertIsDisplayed().performClick()
            composeRule.onNodeWithText("打开外部网页？").assertIsDisplayed()
            composeRule.onNodeWithText("example.test", substring = true).assertIsDisplayed()
            composeRule.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 3_000)
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "today-external-source-device.png")
                .outputStream().use { stream ->
                    check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                        .compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            assertEquals(null, openedUrl.get())
            composeRule.onNodeWithText("暂不打开").performClick()
            assertEquals(null, openedUrl.get())
            composeRule.onNodeWithText("打开原始来源（外部网页）").performScrollTo().performClick()
            composeRule.onNodeWithText("继续打开").performClick()
            assertEquals("https://example.test/fictional-rail-event?ref=synthetic", openedUrl.get())

            composeRule.runOnIdle { viewModel.startNewDiscovery() }
            viewModel.discover(
                childId = "synthetic-world-child",
                expression = "孩子明确不想再看火车展览",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = "虚构城区",
                constraints = org.jianyu.core.model.FamilyConstraints(30, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = false,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            val refused = withTimeout(10_000) {
                viewModel.state.first { it.error == "这次没有适合找入口的主动线索。可以先留白，或补充孩子的想法。" }
            }
            assertEquals(null, refused.opportunities)
            assertEquals(1, worldCalls.get())
            assertEquals(saved, repository.load())
        } finally {
            viewModels.clear()
            if (createdWorldSettings) worldSettings.erase()
            if (createdAiSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }

    private fun saveScreenshot(name: String) {
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
            .outputStream().use { stream ->
                check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
    }

    private fun switchViewedChild(name: String) {
        composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("切换孩子"))
        composeRule.onNodeWithText("切换孩子").performClick()
        composeRule.onNodeWithText(name).performClick()
    }

    @Test
    fun switchingAcrossFourStagesKeepsSourceActionsVisibleAndDraftsSeparate() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(!File(app.filesDir, "family.vault.pending").exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val youngerBirthday = LocalDate.now().minusYears(5).minusDays(5)
        val middleBirthday = LocalDate.now().minusYears(8).minusDays(5)
        val olderBirthday = LocalDate.now().minusYears(11).minusDays(5)
        val teenBirthday = LocalDate.now().minusYears(14).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-four-stage-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-younger-member", "小禾", MemberRole.CHILD, "synthetic-younger-child", timestamp),
                FamilyMember("synthetic-middle-member", "小雨", MemberRole.CHILD, "synthetic-middle-child", timestamp),
                FamilyMember("synthetic-older-member", "小舟", MemberRole.CHILD, "synthetic-older-child", timestamp),
                FamilyMember("synthetic-teen-member", "小岚", MemberRole.CHILD, "synthetic-teen-child", timestamp),
            ),
            children = listOf(
                Child("synthetic-younger-child", "synthetic-younger-member", "小禾", youngerBirthday.year, timestamp, youngerBirthday.toString()),
                Child("synthetic-middle-child", "synthetic-middle-member", "小雨", middleBirthday.year, timestamp, middleBirthday.toString()),
                Child("synthetic-older-child", "synthetic-older-member", "小舟", olderBirthday.year, timestamp, olderBirthday.toString()),
                Child("synthetic-teen-child", "synthetic-teen-member", "小岚", teenBirthday.year, timestamp, teenBirthday.toString()),
            ),
        )
        val viewModels = ViewModelStore()
        var createdFamily = false
        try {
            repository.save(family)
            createdFamily = true
            val viewModel = MainViewModel(app)
            viewModels.put("synthetic-four-stage-visual", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null } }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }
            composeRule.onNodeWithText("从一句话开始，一起玩").assertIsDisplayed()
            saveScreenshot("today-co-play-app-shell.png")
            assertSourceActionClearOfNavigation("连接 AI 服务")
            composeRule.onNodeWithTag("interest-input").performTextInput("小禾最近总在搭积木车")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            switchViewedChild("小舟")
            composeRule.onNodeWithText("一起比较，再一起选择").assertIsDisplayed()
            assertTrue(viewModel.state.value.composerDraft == null)
            saveScreenshot("today-co-select-app-shell.png")
            assertSourceActionClearOfNavigation("连接 AI 服务")
            composeRule.onNodeWithTag("interest-input").performTextInput("小舟想看火车如何过弯")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasTestTag("discover-action"))
            switchViewedChild("小禾")
            composeRule.onNodeWithText("从一句话开始，一起玩").assertIsDisplayed()
            assertEquals("小禾最近总在搭积木车", viewModel.state.value.composerDraft?.expression)
            saveScreenshot("today-co-play-restored-draft-app-shell.png")
            switchViewedChild("小舟")
            assertEquals("小舟想看火车如何过弯", viewModel.state.value.composerDraft?.expression)
            switchViewedChild("小雨")
            composeRule.onNodeWithText("顺着兴趣，看见几扇门").assertIsDisplayed()
            saveScreenshot("today-accompany-app-shell.png")
            assertSourceActionClearOfNavigation("连接 AI 服务")
            switchViewedChild("小岚")
            composeRule.onNodeWithText("找不找入口，由你决定").assertIsDisplayed()
            saveScreenshot("today-handover-unconfigured-app-shell.png")
            // The teen's shared-device/privacy explanation stays visible; the action must still be wholly above navigation.
            assertSourceActionClearOfNavigation("请家长设置 AI", requireExtraSpace = false)
            composeRule.onNodeWithTag("interest-input").performTextInput("我不想做题")
            composeRule.onNodeWithText("这次没有适合找入口的主动线索。你可以先留白，或再写一句。")
                .performScrollTo().assertIsDisplayed()
            saveScreenshot("today-handover-no-clue-app-shell.png")
            viewModel.discover(
                childId = "synthetic-teen-child",
                expression = "我想试做一辆小车",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = true,
                persistContext = false,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            withTimeout(10_000) {
                viewModel.state.first {
                    it.error == "这台共享设备还没有连接 AI；请家长设置，或先看离线演示"
                }
            }
            assertEquals(family, repository.load())
        } finally {
            viewModels.clear()
            if (createdFamily) repository.erase()
        }
    }

    private fun assertSourceActionClearOfNavigation(label: String, requireExtraSpace: Boolean = true) {
        val action = composeRule.onNodeWithText(label)
        action.assertIsDisplayed()
        val navigation = composeRule.onNodeWithTag("jianyu-navigation").fetchSemanticsNode().boundsInRoot
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label is crowded against the bottom navigation",
            actionBounds.bottom + (if (requireExtraSpace) actionBounds.height / 2 else 0f) <= navigation.top,
        )
    }

    @Test
    fun handOverDisclosureReceiptKeepsTheYoungPersonsAuthorship() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(aiSettings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        check(!File(app.filesDir, "family.vault.pending").exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(14).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-handover-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-young-person", "小禾", MemberRole.CHILD, "synthetic-young-person-child", timestamp),
            ),
            children = listOf(Child("synthetic-young-person-child", "synthetic-young-person", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val aiCalls = AtomicInteger()
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-handover-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                aiCalls.incrementAndGet()
                capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
                return listOf(candidate("handover-door", "自己观察真实弯道").copy(
                    explanation = "你可以自己观察真实的火车弯道，再决定要不要继续了解。",
                    whyNow = "你刚刚提到了想看火车怎样转弯。",
                ))
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
            viewModels.put("synthetic-handover-disclosure", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }
            composeRule.onNodeWithText("找不找入口，由你决定").assertIsDisplayed()
            assertSourceActionClearOfNavigation("选择离线演示", requireExtraSpace = false)
            composeRule.onNodeWithText("synthetic-model").assertDoesNotExist()
            saveScreenshot("today-handover-composer-app-shell.png")
            viewModel.discover(
                childId = "synthetic-young-person-child",
                expression = "我不想做题",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = true,
                persistContext = false,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            withTimeout(10_000) {
                viewModel.state.first {
                    it.error == "这次没有适合找入口的主动线索。你可以先留白，或再写一句。"
                }
            }
            assertEquals(0, aiCalls.get())
            assertEquals(family, repository.load())
            viewModel.startNewDiscovery()
            viewModel.discover(
                childId = "synthetic-young-person-child",
                expression = "我想自己观察火车怎么转弯",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = true,
                persistContext = false,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )

            withTimeout(10_000) { viewModel.state.first { it.opportunities != null && !it.discovering } }
            assertEquals(1, aiCalls.get())
            composeRule.onNodeWithText("这次的发现").assertIsDisplayed()
            composeRule.onNodeWithText("这次有一个入口，也可以留白。").assertIsDisplayed()
            saveScreenshot("today-handover-result-app-shell.png")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("你可以自己观察真实的火车弯道，再决定要不要继续了解。"))
            composeRule.onNodeWithText("你可以自己观察真实的火车弯道，再决定要不要继续了解。").assertIsDisplayed()
            saveScreenshot("today-handover-candidate-app-shell.png")
            val saved = requireNotNull(repository.load())
            assertTrue(saved.evidence.isEmpty())
            val approval = saved.events.single { it.eventType == "provider.disclosure-approved" }
            val receipt = saved.events.single { it.eventType == "provider.context-disclosed" }
            assertEquals("synthetic-young-person", approval.authorId)
            assertEquals(MemberRole.CHILD, approval.actorRole)
            assertEquals("shared-with-child", approval.visibility)
            assertEquals("synthetic-young-person", receipt.authorId)
            assertEquals(MemberRole.CHILD, receipt.actorRole)
            assertEquals("shared-with-child", receipt.visibility)
        } finally {
            viewModels.clear()
            if (createdSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun coSelectKeepsTheCaregiverAuthorAndSharesTheChoiceAndRelayedView() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        val aiSettings = app.aiSettingsStore
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(aiSettings.load() == null) { "Disposable test AVD unexpectedly contains AI settings" }
        check(!File(app.filesDir, "family.vault.pending").exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(11).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-co-select-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-co-select-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-co-select-member", "小舟", MemberRole.CHILD, "synthetic-co-select-child", timestamp),
            ),
            children = listOf(Child("synthetic-co-select-child", "synthetic-co-select-member", "小舟", birthday.year, timestamp, birthday.toString())),
        )
        val fakeAi = object : LLMProvider {
            override val id = "synthetic-co-select-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
                return listOf(candidate("co-select-door", "一起观察火车过弯"))
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
            viewModels.put("synthetic-co-select-choice", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null && it.aiConfigured } }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }
            viewModel.discover(
                childId = "synthetic-co-select-child",
                expression = "小舟想看火车如何过弯",
                caregiverGoal = null,
                sharedGoal = null,
                schoolWindow = null,
                lifeContext = null,
                region = null,
                constraints = org.jianyu.core.model.FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 0),
                childConfirmed = false,
                persistContext = true,
                includeRecentContext = false,
                providerDisclosureApproved = true,
                useOfflineDemo = false,
            )
            withTimeout(10_000) { viewModel.state.first { it.opportunities != null && !it.discovering } }
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("一起选这个"))
            composeRule.onNodeWithText("一起选这个").performClick()
            withTimeout(10_000) { viewModel.state.first { it.choiceAcknowledgementId != null && !it.choiceSaving } }
            composeRule.onNodeWithText("代记孩子的看法（可选）").assertIsDisplayed()
            saveScreenshot("today-co-select-choice-feedback.png")
            val chosen = requireNotNull(repository.load())
            val interestEvent = chosen.events.single { it.eventType == "interest.observed" }
            val choiceEvent = chosen.events.single { it.eventType == "opportunity.chosen" }
            assertEquals("shared-with-child", interestEvent.visibility)
            assertEquals("synthetic-co-select-caregiver", choiceEvent.authorId)
            assertEquals(MemberRole.CAREGIVER, choiceEvent.actorRole)
            assertEquals("shared-with-child", choiceEvent.visibility)
            assertEquals("chosen", chosen.choices.single().status)
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("喜欢"))
            composeRule.onNodeWithText("喜欢").performClick()
            withTimeout(10_000) { viewModel.state.first { it.choiceAcknowledgementId == null && it.feedbackSavingChoiceId == null } }
            val reflected = requireNotNull(repository.load())
            val viewEvent = reflected.events.single { it.eventType == "opportunity.feedback-recorded" }
            assertEquals("synthetic-co-select-caregiver", viewEvent.authorId)
            assertEquals(MemberRole.CAREGIVER, viewEvent.actorRole)
            assertEquals("shared-with-child", viewEvent.visibility)
            assertEquals("caregiver-relayed-child-view", viewEvent.payload["responseSource"])
            composeRule.runOnIdle { viewModel.navigate(AppRoute.TIMELINE) }
            composeRule.onNodeWithText("走过的路").assertIsDisplayed()
            composeRule.onNodeWithTag("timeline-list").performScrollToNode(hasText("后来的看法（家长代记）：喜欢"))
            composeRule.onNodeWithText("后来的看法（家长代记）：喜欢").assertIsDisplayed()
            composeRule.onNodeWithText("记录署名：测试家长", substring = true).assertIsDisplayed()
            saveScreenshot("timeline-co-select-relayed-view.png")
        } finally {
            viewModels.clear()
            if (createdSettings) aiSettings.erase()
            if (createdFamily) repository.erase()
        }
    }

    @Test
    fun graduationAppShellKeepsPersonalAndHouseholdDecisionsSeparate() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JianyuApplication
        val repository = app.vaultRepository
        check(repository.load() == null) { "Disposable test AVD unexpectedly contains family data" }
        check(!File(app.filesDir, "family.vault.pending").exists()) { "Disposable test AVD has an unfinished vault write" }

        val timestamp = Instant.now().toString()
        val birthday = LocalDate.now().minusYears(17).minusDays(5)
        val family = FamilyState(
            household = Household("synthetic-graduation-household", "虚构家庭", timestamp),
            members = listOf(
                FamilyMember("synthetic-caregiver", "测试家长", MemberRole.CAREGIVER, createdAt = timestamp),
                FamilyMember("synthetic-adult-member", "小禾", MemberRole.CHILD, "synthetic-adult-child", timestamp),
            ),
            children = listOf(Child("synthetic-adult-child", "synthetic-adult-member", "小禾", birthday.year, timestamp, birthday.toString())),
        )
        val viewModels = ViewModelStore()
        var createdFamily = false
        try {
            repository.save(family)
            createdFamily = true
            val viewModel = MainViewModel(app)
            viewModels.put("synthetic-graduation-visual", viewModel)
            withTimeout(10_000) { viewModel.state.first { !it.loading && it.family != null } }
            composeRule.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                    JianyuTheme { JianyuApp(viewModel) }
                }
            }
            composeRule.onNodeWithText("成年交接").assertIsDisplayed()
            composeRule.onNodeWithText("带走我的资料").assertIsDisplayed()
            saveScreenshot("today-graduation-app-shell.png")
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("查看家庭副本选项"))
            composeRule.onNodeWithText("查看家庭副本选项").performClick()
            composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("清空经历，只保留家庭关系"))
            composeRule.onNodeWithText("清空经历，只保留家庭关系").assertIsDisplayed()
            saveScreenshot("today-graduation-household-options.png")
            assertEquals(family, repository.load())
        } finally {
            viewModels.clear()
            if (createdFamily) repository.erase()
        }
    }

    private fun candidate(id: String, title: String) = Opportunity(
        opportunityId = id,
        title = title,
        explanation = "从孩子自己提出的问题出发，家里可以决定是否尝试。",
        whyNow = "孩子正在问火车转弯。",
        ecosystem = "real-world",
        primaryGoal = GoalOwner.CHILD,
        childPull = true,
        requirements = OpportunityRequirements(20, CostBand.FREE, EnergyBand.LOW, 0),
        sourceKind = "byok-llm",
        sourceTitle = "合成 AI",
        verification = Verification.IDEA,
        riskLevel = RiskLevel.LOW,
        score = 0.7,
    )
}
