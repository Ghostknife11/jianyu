package org.jianyu.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.jianyu.app.MainUiState
import org.jianyu.app.ui.theme.JianyuTheme
import org.jianyu.core.domain.DefaultOpportunityPolicy
import org.jianyu.core.domain.FamilyOpportunityEngine
import org.jianyu.core.domain.LLMProvider
import org.jianyu.core.domain.OpportunityDiscoveryRequest
import org.jianyu.core.domain.OpportunitySource
import org.jianyu.core.domain.ProviderCapability
import org.jianyu.core.domain.PublicWorldQuery
import org.jianyu.core.domain.SourceFailureException
import org.jianyu.core.domain.SourceFailureReason
import org.jianyu.core.domain.TaskContext
import org.jianyu.core.domain.WorldBriefOpportunitySource
import org.jianyu.core.domain.WorldBriefProvider
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import org.jianyu.core.model.WorldBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the production result items from synthetic Provider output; no Vault or network is opened. */
@RunWith(AndroidJUnit4::class)
class DiscoveryResultUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun balancedGoalsRemainVisibleBeforeFamilyChoiceAtLargeText() = runBlocking {
        val adultSource = object : OpportunitySource {
            override val id = "synthetic-adult-source"
            override val kind = "byok-llm"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(
                    candidate("adult-making", "合成动手入口", GoalOwner.CAREGIVER, true, kind).copy(ecosystem = "making"),
                    candidate("adult-media", "合成观看入口", GoalOwner.CAREGIVER, true, kind).copy(ecosystem = "media"),
                    candidate("adult-reading", "合成阅读入口", GoalOwner.CAREGIVER, true, kind).copy(ecosystem = "reading"),
                    candidate("adult-sport", "合成运动入口", GoalOwner.CAREGIVER, true, kind).copy(ecosystem = "sport"),
                )
            }
        }
        val childSource = object : OpportunitySource {
            override val id = "synthetic-child-source"
            override val kind = "pack"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(
                    candidate("child-nature", "合成自然入口", GoalOwner.CHILD, true, kind).copy(ecosystem = "nature"),
                    candidate("shared-family", "合成家庭入口", GoalOwner.SHARED, true, kind).copy(ecosystem = "family-life"),
                )
            }
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(adultSource, childSource))
        assertEquals(4, result.opportunities.selected.size)
        assertEquals(2, result.opportunities.selected.count { it.opportunity.primaryGoal == GoalOwner.CAREGIVER })
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = MainUiState(
                                    loading = false,
                                    opportunities = result.opportunities,
                                    discoveryMode = "byok-ai",
                                    contextPersisted = false,
                                ),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        val list = composeRule.onNodeWithTag("result-list")
        list.performScrollToNode(hasText("合成自然入口"))
        composeRule.onAllNodesWithText("合成自然入口").onFirst().assertIsDisplayed()
        // Goal ownership must be readable in the route overview, before the full cards.
        composeRule.onAllNodesWithText("主要回应孩子想做的事").onFirst().assertIsDisplayed()
        saveScreenshot("formal-balanced-goals-overview-large.png")
        list.performScrollToNode(hasText("合成家庭入口"))
        composeRule.onAllNodesWithText("主要回应共同想做的事").onFirst().assertIsDisplayed()
        list.performScrollToNode(hasText("合成动手入口"))
        composeRule.onAllNodesWithText("主要回应家长的期待").onFirst().assertIsDisplayed()
        list.performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        Unit
    }

    @Test
    fun formalAiResultShowsItsSourceNothingAndAWorkingFamilyChoice() = runBlocking {
        val source = object : LLMProvider {
            override val id = "synthetic-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                candidate("real-door", "一起看真实弯道", GoalOwner.CHILD, true, "byok-llm"),
                candidate("forced-lesson", "写公式练习", GoalOwner.CAREGIVER, false, "byok-llm").copy(
                    naturalEntry = false,
                    interventionPressure = RiskLevel.HIGH,
                ),
                candidate("daily-worksheet", "每天做三页速度练习", GoalOwner.CHILD, true, "byok-llm").copy(
                    ecosystem = "reading",
                    naturalEntry = true,
                    interventionPressure = RiskLevel.LOW,
                ),
            )
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertEquals(1, result.opportunities.selected.size)
        assertTrue(result.opportunities.rejected.any { it.opportunity.title == "写公式练习" })
        assertTrue(result.opportunities.rejected.any {
            it.opportunity.title == "每天做三页速度练习" && "daily-task-pressure" in it.reasons
        })
        val disclosure = result.sourceDisclosures.getValue(source.id)
        var state by mutableStateOf(MainUiState(
            loading = false,
            opportunities = result.opportunities,
            discoveryMode = "byok-ai",
            discoverySourceIssues = result.sourceIssues,
            disclosureIncluded = disclosure.includedCategories,
            disclosureExcluded = disclosure.excludedCategories,
            contextPersisted = false,
        ))
        var showDataBoundary by mutableStateOf(false)
        var chosenTitle: String? = null
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = state,
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = showDataBoundary,
                                onToggleDataBoundary = { showDataBoundary = !showDataBoundary },
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { opportunity, _ ->
                                    chosenTitle = opportunity.title
                                    state = state.copy(opportunities = null)
                                },
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("AI 找入口，本机筛选；你们来选。").assertIsDisplayed()
        composeRule.onNodeWithText("描述不存入长期足迹；外部 AI 可能按其条款处理已发送内容。").assertIsDisplayed()
        composeRule.onNodeWithText("离线演示：未调用 AI，也未发送资料。").assertDoesNotExist()
        composeRule.onNodeWithText("查看这次的数据边界").performClick()
        composeRule.onNodeWithText("这次 AI 服务可接收的线索").assertIsDisplayed()
        saveScreenshot("formal-ai-result-receipt.png")
        composeRule.onNodeWithText("这次看见的门").assertDoesNotExist()
        saveScreenshot("formal-ai-one-door-result.png")
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("AI 生成 · 合成 AI"))
        composeRule.onNodeWithText("这是一个想法，不是事实").assertIsDisplayed()
        composeRule.onNodeWithText("AI 生成 · 合成 AI").assertIsDisplayed()
        saveScreenshot("formal-ai-result-source.png")
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        composeRule.onNodeWithText("另一扇门 · 留白").assertIsDisplayed()
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("本机排除了 2 个不合适的入口"))
        composeRule.onNodeWithText("查看原因").performClick()
        composeRule.onNodeWithText("像每日任务，可能增加压力", substring = true).performScrollTo().assertIsDisplayed()
        saveScreenshot("formal-ai-daily-task-rejected.png")
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("一起选这个"))
        composeRule.onNodeWithText("一起选这个").assertIsDisplayed().performClick()
        assertEquals("一起看真实弯道", chosenTitle)
        composeRule.onNodeWithText("一起选这个").assertDoesNotExist()
    }

    @Test
    fun longGeneratedTitleAndExplanationRemainReadableAndChoiceReachable() = runBlocking {
        val longTitle = "把最近一直想弄懂的赛车过弯问题带到周末原本就要走的路上，先看看真实道路的弯道，再由孩子决定要不要继续观察不同路线"
        val longExplanation = "这只是一个虚构的长说明，用来检查入口卡片是否仍然能完整换行。".repeat(5)
        val longSource = "虚构的第三方家庭机会发现服务名称，用来检查来源文字换行后仍能读清楚"
        val source = object : LLMProvider {
            override val id = "synthetic-long-copy-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                candidate("long-door", longTitle, GoalOwner.CHILD, true, "byok-llm").copy(
                    explanation = longExplanation,
                    sourceTitle = longSource,
                ),
            )
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertEquals(1, result.opportunities.selected.size)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = MainUiState(loading = false, opportunities = result.opportunities, discoveryMode = "byok-ai"),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("result-list")
        composeRule.onNodeWithText("这次看见的门").assertDoesNotExist()
        list.performScrollToNode(hasText("入口 · 影视与内容"))
        composeRule.onNodeWithText("入口 · 影视与内容").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText(longTitle).fetchSemanticsNodes().isNotEmpty())
        saveScreenshot("formal-ai-long-door-title.png")
        composeRule.onNodeWithText("查看完整说明").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("收起说明").assertIsDisplayed()
        composeRule.onNodeWithText(longSource, substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("一起选这个").performScrollTo().assertIsDisplayed()
        saveScreenshot("formal-ai-long-door-choice.png")
    }

    @Test
    fun distinctDoorsStillHaveACompactComparisonBeforeTheirFullCards() = runBlocking {
        val source = object : LLMProvider {
            override val id = "synthetic-two-door-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                candidate("watch-door", "一起看看真实车辆怎么过弯", GoalOwner.CHILD, true, "byok-llm"),
                candidate("relabeled-door", "一起看看真实车辆怎么过弯！", GoalOwner.CHILD, true, "byok-llm")
                    .copy(ecosystem = "making"),
                candidate("make-door", "用家里纸板试一段弯道", GoalOwner.CHILD, true, "byok-llm")
                    .copy(ecosystem = "making"),
            )
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertEquals(2, result.opportunities.selected.size)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = MainUiState(loading = false, opportunities = result.opportunities, discoveryMode = "byok-ai"),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("result-list")
        list.performScrollToNode(hasText("这次看见的门"))
        composeRule.onNodeWithText("这次看见的门").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("一起看看真实车辆怎么过弯").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("用家里纸板试一段弯道").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("一起看看真实车辆怎么过弯！").assertDoesNotExist()
        composeRule.onNodeWithText("留白").assertIsDisplayed()
        saveScreenshot("formal-ai-two-door-overview.png")
    }

    @Test
    fun failedAiIsNotDisguisedAsDemoWhenAnotherSourceHasADoor() = runBlocking {
        val failedAi = object : LLMProvider {
            override val id = "synthetic-failed-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> =
                throw SourceFailureException(SourceFailureReason.RESPONSE_INCOMPLETE)
        }
        val localSource = object : org.jianyu.core.domain.OpportunitySource {
            override val id = "synthetic-pack"
            override val kind = "pack"
            override val acceptedDataCategories = setOf("current-interest")
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                candidate("pack-door", "用纸板做一段弯道", GoalOwner.CHILD, true, "pack"),
            )
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(failedAi, localSource))
        assertEquals("byok-llm", result.sourceIssues.single().sourceKind)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = MainUiState(
                                    loading = false,
                                    opportunities = result.opportunities,
                                    discoveryMode = "byok-ai",
                                    discoverySourceIssues = result.sourceIssues,
                                    contextPersisted = false,
                                ),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("AI 这次没有完成寻找；下方若有入口，来自其他来源并经过本机检查。").assertIsDisplayed()
        composeRule.onNodeWithText("调整后再试").assertIsDisplayed()
        composeRule.onNodeWithText("离线演示：未调用 AI，也未发送资料。").assertDoesNotExist()
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("这次 AI 返回的内容没有完整结束", substring = true))
        composeRule.onNodeWithText("这次 AI 返回的内容没有完整结束", substring = true).assertIsDisplayed()
        saveScreenshot("formal-ai-incomplete-source.png")
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("用纸板做一段弯道"))
        assertTrue(composeRule.onAllNodesWithText("用纸板做一段弯道").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("内容包 · 合成内容包"))
        composeRule.onNodeWithText("这是一个想法，不是事实").assertIsDisplayed()
        composeRule.onNodeWithText("内容包 · 合成内容包").assertIsDisplayed()
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        saveScreenshot("formal-ai-failed-with-pack.png")
    }

    @Test
    fun rejectedWorldCredentialsGiveAReadableNextStepAtLargeText() = runBlocking {
        val failedWorld = object : WorldBriefProvider {
            override val id = "synthetic-world"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> =
                throw SourceFailureException(SourceFailureReason.AUTHENTICATION_REJECTED)
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(), listOf(WorldBriefOpportunitySource()), failedWorld,
            approvedWorldQuery = PublicWorldQuery(region = null, timeWindow = "next-14-days"),
        )
        assertEquals(1, result.sourceIssues.size)
        assertTrue(result.sourceIssues.any {
            it.sourceId == failedWorld.id && it.reasonCode == SourceFailureReason.AUTHENTICATION_REJECTED.code
        })
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = MainUiState(
                                    loading = false,
                                    opportunities = result.opportunities,
                                    discoveryMode = "byok-ai",
                                    discoverySourceIssues = result.sourceIssues,
                                    contextPersisted = false,
                                ),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("result-list")
        list.performScrollToNode(hasText("请检查 API 密钥或服务权限", substring = true))
        composeRule.onNodeWithText("请检查 API 密钥或服务权限", substring = true).assertIsDisplayed()
        saveScreenshot("formal-world-auth-rejected-large.png")
        list.performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        Unit
    }

    @Test
    fun rejectedAiRequestGivesAReadableNextStepWithoutAFalseDemoFallback() = runBlocking {
        val rejectedAi = object : LLMProvider {
            override val id = "synthetic-rejected-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> =
                throw SourceFailureException(SourceFailureReason.REQUEST_REJECTED)
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(rejectedAi))
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            discoveryResultItems(
                                state = MainUiState(
                                    loading = false,
                                    opportunities = result.opportunities,
                                    discoveryMode = "byok-ai",
                                    discoverySourceIssues = result.sourceIssues,
                                    contextPersisted = false,
                                ),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        val list = composeRule.onNodeWithTag("result-list")
        list.performScrollToNode(hasText("请核对模型名称、服务地址与接口兼容性", substring = true))
        composeRule.onNodeWithText("请核对模型名称、服务地址与接口兼容性", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("离线演示：未调用 AI，也未发送资料。").assertDoesNotExist()
        saveScreenshot("formal-ai-request-rejected.png")
        list.performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        composeRule.onNodeWithText("留白").assertIsDisplayed()
        composeRule.onNodeWithText("另一扇门 · 留白").assertDoesNotExist()
        saveScreenshot("formal-ai-empty-result-nothing.png")
    }

    @Test
    fun unsourcedAiEventShowsAPlainReasonAndOnlyNothingAtLargeText() = runBlocking {
        val source = object : LLMProvider {
            override val id = "synthetic-unsourced-event-ai"
            override val kind = "vendor-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                candidate("fictional-event", "本周虚构赛车展", GoalOwner.CHILD, true, "world-brief").copy(
                    ecosystem = "world-event",
                    sourceUrl = null,
                ),
            )
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.rejected.single().reasons.contains("ai-world-event-without-source"))
        val lead = emptyDiscoveryDescriptionFor(
            MainUiState(loading = false, opportunities = result.opportunities, discoverySourceIssues = result.sourceIssues),
            LifecycleStage.CO_SELECT,
        )
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item { JianyuPageHeader("此刻", "这次的发现", lead) }
                            discoveryResultItems(
                                state = MainUiState(
                                    loading = false,
                                    opportunities = result.opportunities,
                                    discoveryMode = "byok-ai",
                                    discoverySourceIssues = result.sourceIssues,
                                    contextPersisted = false,
                                ),
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(lead).assertIsDisplayed()
        composeRule.onNodeWithText("本周虚构赛车展").assertDoesNotExist()
        saveScreenshot("formal-ai-unsourced-world-event.png")
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        Unit
    }

    @Test
    fun caregiverOnlyCandidatesExplainTheOmissionWithoutAFalseGateRejection() = runBlocking {
        val source = object : LLMProvider {
            override val id = "synthetic-caregiver-only-ai"
            override val kind = "byok-llm"
            override val modelId = "synthetic-model"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                candidate("adult-media", "合成家长期待入口", GoalOwner.CAREGIVER, true, kind),
            )
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.rejected.isEmpty())
        assertEquals(1, result.opportunities.eligibleNotSelected.size)
        val state = MainUiState(
            loading = false,
            opportunities = result.opportunities,
            discoveryMode = "byok-ai",
            contextPersisted = false,
        )
        val lead = emptyDiscoveryDescriptionFor(state, LifecycleStage.CO_SELECT)
        assertTrue(lead.contains("家长期待"))
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                JianyuTheme {
                    Box(Modifier.width(360.dp).height(720.dp)) {
                        LazyColumn(
                            modifier = Modifier.testTag("result-list"),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item { JianyuPageHeader("此刻", "这次的发现", lead) }
                            discoveryResultItems(
                                state = state,
                                stage = LifecycleStage.CO_SELECT,
                                showDataBoundary = false,
                                onToggleDataBoundary = {},
                                onNewDiscovery = {},
                                onPreviewChoice = { _, _ -> error("Formal mode must not preview") },
                                onChoose = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText(lead).assertIsDisplayed()
        composeRule.onNodeWithText("合成家长期待入口").assertDoesNotExist()
        saveScreenshot("formal-caregiver-only-empty-result-large.png")
        composeRule.onNodeWithTag("result-list").performScrollToNode(hasText("这次就留白"))
        composeRule.onNodeWithText("这次就留白").assertIsDisplayed()
        Unit
    }

    private fun request() = OpportunityDiscoveryRequest(
        currentInterest = "赛车拐弯",
        age = 11,
        lifecycleStage = LifecycleStage.CO_SELECT,
        goals = FamilyGoals(child = "想知道为什么过弯不打滑"),
        constraints = FamilyConstraints(60, CostBand.FREE, EnergyBand.LOW, 20),
    )

    private fun candidate(id: String, title: String, goal: GoalOwner, childPull: Boolean, source: String) = Opportunity(
        opportunityId = id,
        title = title,
        explanation = "从孩子现在主动问的问题开始，家庭可以自己决定是否尝试。",
        whyNow = "孩子正在问赛车拐弯。",
        ecosystem = if (source == "pack") "making" else "media",
        primaryGoal = goal,
        childPull = childPull,
        requirements = OpportunityRequirements(20, CostBand.FREE, EnergyBand.LOW, 0),
        sourceKind = source,
        sourceTitle = if (source == "pack") "合成内容包" else "合成 AI",
        verification = Verification.IDEA,
        riskLevel = RiskLevel.LOW,
        score = 0.7,
    )

    private fun saveScreenshot(name: String) {
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name)
        output.outputStream().use { stream ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
