package org.jianyu.core.domain

import kotlinx.coroutines.runBlocking
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import org.jianyu.core.model.WorldBrief
import java.time.Instant
import java.util.concurrent.CancellationException

class OpportunityEngineTest {
    @Test
    fun `zero available time and zero duration candidates cannot bypass Nothing`() {
        val candidate = fixtureOpportunity("zero-minute-claim", "nature", 0.9).copy(
            requirements = OpportunityRequirements(0, CostBand.FREE_EXISTING, EnergyBand.NONE, 0),
        )
        val policy = DefaultOpportunityPolicy()
        assertEquals("0.2.10", policy.version)
        val noTime = policy.evaluate(candidate, request(
            constraints = FamilyConstraints(0, CostBand.FREE_EXISTING, EnergyBand.NONE, 0),
        ))
        assertFalse(noTime.allowed)
        assertTrue("no-available-time" in noTime.reasons)
        assertTrue("invalid-duration" in noTime.reasons)

        val fakeInstant = policy.evaluate(candidate, request())
        assertFalse(fakeInstant.allowed)
        assertTrue("invalid-duration" in fakeInstant.reasons)
    }

    @Test
    fun `provider cannot insert its own Nothing into the selectable entrance list`() = runBlocking {
        val source = object : OpportunitySource {
            override val id = "synthetic-reserved-nothing"
            override val kind = "synthetic-pack"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(fixtureOpportunity("fake-nothing", OpportunityEcosystems.NOTHING, 0.99).copy(
                    isNothing = true,
                ))
            }
        }
        val set = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source)).opportunities
        assertTrue(set.selected.isEmpty())
        assertTrue("reserved-nothing-option" in set.rejected.single().reasons)
        assertTrue(set.nothing.isNothing)
    }

    @Test
    fun `identical candidates still keep separate Gate evaluations after one is selected`() = runBlocking {
        val duplicate = fixtureOpportunity("same-door", "making", 0.6)
        val source = object : OpportunitySource {
            override val id = "synthetic-duplicate-source"
            override val kind = "synthetic-pack"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(duplicate, duplicate.copy())
            }
        }
        val set = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source)).opportunities
        assertEquals(1, set.selected.size)
        assertEquals(1, set.eligibleNotSelected.size)
        assertTrue(set.eligibleNotSelected.single().allowed)
        assertTrue(set.rejected.isEmpty())
    }

    @Test
    fun `diversity keeps caregiver-primary doors from outnumbering child and shared doors`() = runBlocking {
        val caregiverSource = object : OpportunitySource {
            override val id = "synthetic-caregiver-doors"
            override val kind = "synthetic-ai"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(
                    fixtureOpportunity("adult-making", "making", 0.99).copy(primaryGoal = GoalOwner.CAREGIVER),
                    fixtureOpportunity("adult-media", "media", 0.98).copy(primaryGoal = GoalOwner.CAREGIVER),
                    fixtureOpportunity("adult-reading", "reading", 0.97).copy(primaryGoal = GoalOwner.CAREGIVER),
                    fixtureOpportunity("adult-sport", "sport", 0.96).copy(primaryGoal = GoalOwner.CAREGIVER),
                )
            }
        }
        val familySource = object : OpportunitySource {
            override val id = "synthetic-family-doors"
            override val kind = "synthetic-pack"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(
                    fixtureOpportunity("child-nature", "nature", 0.2),
                    fixtureOpportunity("shared-family", "family-life", 0.1).copy(primaryGoal = GoalOwner.SHARED),
                )
            }
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(), listOf(caregiverSource, familySource),
        )
        val visible = result.opportunities.selected.map { it.opportunity }
        assertTrue(visible.any { it.opportunityId == "child-nature" })
        assertTrue(visible.any { it.opportunityId == "shared-family" })
        assertTrue(visible.count { it.primaryGoal == GoalOwner.CAREGIVER } <=
            visible.count { it.primaryGoal != GoalOwner.CAREGIVER })
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals(2, result.opportunities.eligibleNotSelected.size)
        assertTrue(result.opportunities.eligibleNotSelected.all { it.allowed && it.opportunity.primaryGoal == GoalOwner.CAREGIVER })

        val adultOnly = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(), listOf(caregiverSource),
        )
        assertTrue(adultOnly.opportunities.selected.isEmpty())
        assertTrue(adultOnly.opportunities.nothing.isNothing)
        assertTrue(adultOnly.opportunities.rejected.isEmpty())
        assertEquals(4, adultOnly.opportunities.eligibleNotSelected.size)
        assertTrue(adultOnly.opportunities.eligibleNotSelected.all { it.allowed && it.opportunity.primaryGoal == GoalOwner.CAREGIVER })
    }

    @Test
    fun `AI world event without a checkable source cannot become a family entrance`() {
        val aiEvent = fixtureOpportunity("invented-event", OpportunityEcosystems.WORLD_EVENT, 0.99).copy(
            sourceKind = "byok-llm",
            sourceUrl = null,
        )
        val decision = DefaultOpportunityPolicy().evaluate(aiEvent, request())
        assertFalse(decision.allowed)
        assertTrue("ai-world-event-without-source" in decision.reasons)

        val publicBrief = aiEvent.copy(
            sourceKind = "world-brief",
            sourceTitle = "虚构公共来源",
            sourceUrl = "https://example.test/fictional-event",
            retrievedAt = Instant.now().toString(),
        )
        assertTrue(DefaultOpportunityPolicy().evaluate(publicBrief, request()).allowed)
    }

    @Test
    fun `engine binds candidate provenance to the invoked provider rather than its own claim`() = runBlocking {
        val spoofingAi = object : LLMProvider {
            override val id = "synthetic-spoofing-ai"
            override val kind = "vendor-llm"
            override val modelId = "synthetic"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
                return listOf(fixtureOpportunity("invented-event", OpportunityEcosystems.WORLD_EVENT, 0.99).copy(
                    sourceKind = "world-brief",
                    sourceTitle = "伪装的世界来源",
                    sourceUrl = null,
                ))
            }
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(spoofingAi))
        val rejected = result.opportunities.rejected.single()
        assertEquals("byok-llm", rejected.opportunity.sourceKind)
        assertTrue("ai-world-event-without-source" in rejected.reasons)
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
    }


    @Test
    fun `policy rejects AI child pull asserted over refusal without a new positive clue`() {
        val refused = request().copy(currentInterest = "孩子明确不想再看赛车")
        val decision = DefaultOpportunityPolicy().evaluate(fixtureOpportunity("ai-claim", "making", 0.8), refused)
        assertFalse(decision.allowed)
        assertTrue("no-current-child-pull" in decision.reasons)
        val neutral = DefaultOpportunityPolicy().evaluate(
            fixtureOpportunity("weather-claim", "making", 0.8),
            refused.copy(currentInterest = "不要赛车，今天下雨了"),
        )
        assertFalse(neutral.allowed)
        assertTrue("no-current-child-pull" in neutral.reasons)
        for (background in listOf("家长想带孩子看赛车", "天气预报说今晚能看到赛车活动")) {
            val decision = DefaultOpportunityPolicy().evaluate(
                fixtureOpportunity("adult-or-background-claim", "making", 0.8),
                refused.copy(currentInterest = background),
            )
            assertFalse(decision.allowed)
            assertTrue("no-current-child-pull" in decision.reasons)
        }
        assertTrue(DefaultOpportunityPolicy().evaluate(
            fixtureOpportunity("mixed", "making", 0.8),
            refused.copy(currentInterest = "不想做题，但想知道赛车为什么转弯"),
        ).allowed)
    }

    @Test
    fun `preflight scope allows possible public briefs but never invents family fields`() {
        val source = object : OpportunitySource {
            override val id = "preflight-source"
            override val kind = "fixture"
            override val acceptedDataCategories = setOf("current-interest", "public-world-briefs", "names")
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> = emptyList()
        }
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())

        val approved = engine.previewSourceDisclosure(request(), source, worldBriefMayContribute = true)

        assertTrue("current-interest" in approved.includedCategories)
        assertTrue("public-world-briefs" in approved.includedCategories)
        assertFalse("names" in approved.includedCategories)
        assertTrue("names" in approved.excludedCategories)
    }

    @Test
    fun `a source cannot expand beyond its approved categories before discovery`() = runBlocking {
        var expanded = false
        var called = false
        val source = object : OpportunitySource {
            override val id = "changing-source"
            override val kind = "byok-llm"
            override val acceptedDataCategories get() = if (expanded) {
                setOf("current-interest", "declared-goals")
            } else {
                setOf("current-interest")
            }
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                called = true
                return emptyList()
            }
        }
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())
        val approved = engine.previewSourceDisclosure(request(), source, worldBriefMayContribute = false)
        expanded = true

        val result = engine.discover(
            request(),
            listOf(source),
            approvedCategoriesBySource = mapOf(source.id to approved.includedCategories.toSet()),
        )

        assertFalse(called)
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals("outside-approved-scope", result.sourceIssues.single().reasonCode)
    }

    @Test
    fun `missing source approval fails closed even when source requests no categories`() = runBlocking {
        var called = false
        val source = object : OpportunitySource {
            override val id = "missing-approval"
            override val kind = "fixture"
            override val acceptedDataCategories = emptySet<String>()
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                called = true
                return emptyList()
            }
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(), listOf(source), approvedCategoriesBySource = emptyMap(),
        )
        assertFalse(called)
        assertEquals("outside-approved-scope", result.sourceIssues.single().reasonCode)
    }

    @Test
    fun `public world query is never sent without an exact per-request approval`() = runBlocking {
        val received = mutableListOf<PublicWorldQuery>()
        val provider = object : WorldBriefProvider {
            override val id = "approved-public-world"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                received += query
                return emptyList()
            }
        }
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())
        val request = request()
        val source = LocalDemoOpportunitySource()

        val missing = engine.discover(request, listOf(source), worldBriefProvider = provider)
        val wrongRegion = engine.discover(
            request, listOf(source), worldBriefProvider = provider,
            approvedWorldQuery = PublicWorldQuery("另一个地区", "next-14-days"),
        )
        val wrongWindow = engine.discover(
            request, listOf(source), worldBriefProvider = provider,
            approvedWorldQuery = PublicWorldQuery(request.region, "next-30-days"),
        )
        val wrongCategories = engine.discover(
            request, listOf(source), worldBriefProvider = provider,
            approvedWorldQuery = approvedWorldQuery().copy(categories = listOf("events")),
        )

        assertTrue(received.isEmpty())
        listOf(missing, wrongRegion, wrongWindow, wrongCategories).forEach { result ->
            assertEquals("outside-approved-scope", result.sourceIssues.single().reasonCode)
            assertTrue(result.opportunities.nothing.isNothing)
        }

        val approved = approvedWorldQuery()
        val allowed = engine.discover(
            request, listOf(source), worldBriefProvider = provider,
            approvedWorldQuery = approved,
        )
        assertEquals(listOf(approved), received)
        assertTrue(allowed.sourceIssues.isEmpty())
    }

    @Test
    fun `duplicate source IDs cannot share one approval`() = runBlocking {
        fun source() = object : OpportunitySource {
            override val id = "same-id"
            override val kind = "fixture"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> = emptyList()
        }
        val failure = runCatching {
            FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
                request(), listOf(source(), source()), approvedCategoriesBySource = mapOf("same-id" to emptySet()),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `handover requires child confirmation and graduation stops observations`() {
        assertTrue(lifecycleAuthority(LifecycleStage.HAND_OVER).requiresChildConfirmation)
        assertFalse(lifecycleAuthority(LifecycleStage.GRADUATION).allowsNewObservation)
        assertEquals("child", lifecycleAuthority(LifecycleStage.HAND_OVER).decisionOwner)
    }
    @Test
    fun `diverse options always keep Nothing`() = runBlocking {
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())
        val result = engine.discover(request(), listOf(LocalDemoOpportunitySource()))
        assertEquals(
            result.opportunities.selected.size,
            result.opportunities.selected.map { it.opportunity.ecosystem }.distinct().size,
        )
        assertTrue(result.opportunities.nothing.isNothing)
        assertTrue("names" in result.disclosure.excludedCategories)
    }

    @Test
    fun `ecosystem aliases are normalized before diversity selection`() = runBlocking {
        val aliases = object : OpportunitySource {
            override val id = "ecosystem-alias-fixture"
            override val kind = "fixture"

            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> = listOf(
                fixtureOpportunity("making-en", "making", 0.9),
                fixtureOpportunity("making-zh", "动手制作", 0.8),
                fixtureOpportunity("people-zh", "身边的人", 0.7),
            )
        }

        val selected = FamilyOpportunityEngine(DefaultOpportunityPolicy())
            .discover(request(), listOf(aliases))
            .opportunities.selected
            .map { it.opportunity }

        assertEquals(listOf("making-en", "people-zh"), selected.map { it.opportunityId })
        assertEquals(listOf(OpportunityEcosystems.MAKING, OpportunityEcosystems.PEOPLE), selected.map { it.ecosystem })
    }

    @Test
    fun `gate rejects a child labeled option when the source admits no child pull`() = runBlocking {
        val source = object : OpportunitySource {
            override val id = "no-child-pull-fixture"
            override val kind = "byok-llm"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                fixtureOpportunity("unsupported-child-route", "making", 0.99).copy(
                    sourceKind = kind,
                    primaryGoal = GoalOwner.CHILD,
                    childPull = false,
                ),
            )
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(source))
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue("insufficient-child-pull" in result.opportunities.rejected.single().reasons)
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `policy rejects an explicit daily worksheet even when AI labels it child led and low pressure`() {
        val worksheet = fixtureOpportunity("daily-worksheet", "reading", 0.9).copy(
            title = "每天做三页速度练习",
            primaryGoal = GoalOwner.CHILD,
            childPull = true,
            naturalEntry = true,
            interventionPressure = RiskLevel.LOW,
        )
        val rejected = DefaultOpportunityPolicy().evaluate(worksheet, request())
        assertFalse(rejected.allowed)
        assertTrue("daily-task-pressure" in rejected.reasons)

        val chosenStudy = worksheet.copy(title = "孩子自己想弄懂速度公式")
        assertTrue(DefaultOpportunityPolicy().evaluate(chosenStudy, request()).allowed)
    }

    @Test
    fun `policy rejects an assignment hidden in the explanation without rejecting a negated example`() {
        val naturalTitle = fixtureOpportunity("hidden-assignment", "making", 0.9).copy(
            title = "用纸板观察赛车转弯",
            explanation = "先做一个纸板弯道，然后每天做三页速度练习。",
            primaryGoal = GoalOwner.CHILD,
            childPull = true,
            naturalEntry = true,
            interventionPressure = RiskLevel.LOW,
        )
        val rejected = DefaultOpportunityPolicy().evaluate(naturalTitle, request())
        assertFalse(rejected.allowed)
        assertTrue("daily-task-pressure" in rejected.reasons)

        val voluntary = naturalTitle.copy(explanation = "孩子自己想知道为什么会打滑，不需要每天做三页练习。")
        assertTrue(DefaultOpportunityPolicy().evaluate(voluntary, request()).allowed)
    }

    @Test
    fun `diversity takes turns across feasible sources instead of ranking by self reported score`() = runBlocking {
        fun source(sourceId: String, sourceKind: String, candidates: List<Opportunity>) = object : OpportunitySource {
            override val id = sourceId
            override val kind = sourceKind
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = candidates
        }
        val ai = source("ai-fixture", "byok-llm", listOf(
            fixtureOpportunity("ai-making", "making", 0.99).copy(sourceKind = "byok-llm"),
            fixtureOpportunity("ai-media", "media", 0.98).copy(sourceKind = "byok-llm"),
            fixtureOpportunity("ai-reading", "reading", 0.97).copy(sourceKind = "byok-llm"),
            fixtureOpportunity("ai-sport", "sport", 0.96).copy(sourceKind = "byok-llm"),
            fixtureOpportunity("ai-nature", "nature", 0.95).copy(sourceKind = "byok-llm"),
        ))
        val pack = source("pack-fixture", "pack", listOf(
            fixtureOpportunity("pack-family", "family-life", 0.22).copy(sourceKind = "pack"),
        ))
        val world = source("world-fixture", "world-brief", listOf(
            fixtureOpportunity("world-event", "world-event", 0.11).copy(
                sourceKind = "world-brief",
                retrievedAt = "2026-09-13T00:00:00Z",
            ),
        ))

        val selected = FamilyOpportunityEngine(DefaultOpportunityPolicy(clock = { Instant.parse("2026-09-14T10:00:00Z") }))
            .discover(request(), listOf(ai, pack, world))
            .opportunities.selected.map { it.opportunity.opportunityId }

        assertEquals(listOf("ai-making", "pack-family", "world-event", "ai-media", "ai-reading"), selected)
    }

    @Test
    fun `diversity does not show the same door twice just because its ecosystem changed`() = runBlocking {
        val source = object : OpportunitySource {
            override val id = "duplicate-door-fixture"
            override val kind = "pack"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                fixtureOpportunity("first", "media", 0.8).copy(title = "一起看真实弯道"),
                fixtureOpportunity("relabeled", "making", 0.7).copy(title = "一起看真实弯道！"),
                fixtureOpportunity("different", "making", 0.6).copy(title = "用纸板试一段弯道"),
            )
        }
        val selected = FamilyOpportunityEngine(DefaultOpportunityPolicy())
            .discover(request(), listOf(source)).opportunities.selected
        assertEquals(listOf("first", "different"), selected.map { it.opportunity.opportunityId })
    }

    @Test
    fun `same public title at distinct original URLs remains two sourced items`() = runBlocking {
        val source = object : OpportunitySource {
            override val id = "different-public-sources-fixture"
            override val kind = "pack"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability) = listOf(
                fixtureOpportunity("venue-a", "place", 0.8).copy(
                    title = "周末开放日",
                    sourceUrl = "https://example.org/venue-a",
                ),
                fixtureOpportunity("venue-b", "world-event", 0.7).copy(
                    title = "周末开放日！",
                    sourceUrl = "https://example.org/venue-b",
                ),
            )
        }
        val selected = FamilyOpportunityEngine(DefaultOpportunityPolicy())
            .discover(request(), listOf(source)).opportunities.selected
        assertEquals(listOf("venue-a", "venue-b"), selected.map { it.opportunity.opportunityId })
    }

    @Test
    fun `unknown ecosystem identifiers fail into one stable other route`() {
        assertEquals(OpportunityEcosystems.OTHER, OpportunityEcosystems.canonical("vendor-new-route"))
        assertEquals(OpportunityEcosystems.MAKING, OpportunityEcosystems.canonical("家庭实验"))
        assertEquals(OpportunityEcosystems.REAL_WORLD, OpportunityEcosystems.canonical("现实观察"))
    }

    @Test
    fun `gate rejects options beyond caregiver energy`() = runBlocking {
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())
        val result = engine.discover(
            request(constraints = FamilyConstraints(caregiverEnergy = EnergyBand.LOW)),
            listOf(LocalDemoOpportunitySource()),
        )
        assertTrue(result.opportunities.rejected.any { "exceeds-caregiver-energy" in it.reasons })
    }

    @Test
    fun `recent selections warn about restraint without vetoing new child pull`() = runBlocking {
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())
        val result = engine.discover(
            request(recentInterventionCount = 4),
            listOf(LocalDemoOpportunitySource()),
        )
        assertTrue(result.opportunities.selected.isNotEmpty())
        assertTrue(result.opportunities.selected.all { "recent-intervention-load" in it.warnings })
        assertTrue(result.opportunities.rejected.none { "recent-intervention-load" in it.reasons })
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `lifecycle boundaries match product authority stages`() {
        assertEquals("共玩", lifecycleStage(2020, 2026).label)
        assertEquals("共选", lifecycleStage(2015, 2026).label)
        assertEquals("Graduation", lifecycleStage(2010, 2026).label)
    }

    @Test
    fun `pack and public world brief contribute through separate sources`() = runBlocking {
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy())
        val result = engine.discover(
            request(),
            sources = listOf(
                DeclarativePackOpportunitySource(listOf(starterOpportunityPack())),
                WorldBriefOpportunitySource(),
            ),
            worldBriefProvider = SyntheticWorldBriefProvider(),
            approvedWorldQuery = approvedWorldQuery(),
        )

        assertTrue(result.sourceKinds.contains("pack"))
        assertTrue(result.sourceKinds.contains("world-brief"))
        assertTrue(result.opportunities.selected.any { it.opportunity.sourceKind == "pack" })
        assertTrue(result.opportunities.selected.any { it.opportunity.sourceKind == "world-brief" })
    }

    @Test
    fun `pack does not turn an unrelated use of speed into a motorsport recommendation`() = runBlocking {
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request().copy(
                currentInterest = "孩子最近想把长篇故事读完，也问怎样提高阅读速度",
                goals = FamilyGoals(child = "继续享受阅读"),
            ),
            sources = listOf(DeclarativePackOpportunitySource(listOf(starterOpportunityPack()))),
        )

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `pack entry without specific trigger terms fails closed`() = runBlocking {
        val packWithoutTerms = starterOpportunityPack().let { pack ->
            pack.copy(opportunities = pack.opportunities.map { it.copy(triggerTerms = emptyList()) })
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(),
            sources = listOf(DeclarativePackOpportunitySource(listOf(packWithoutTerms))),
        )

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `world provider receives public query and never child interest`() = runBlocking {
        var received: PublicWorldQuery? = null
        val provider = object : WorldBriefProvider {
            override val id = "capture"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                capability.requireValid(id, kind, "fetch-public-world-brief")
                received = query
                return emptyList()
            }
        }

        FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(),
            listOf(LocalDemoOpportunitySource()),
            provider,
            approvedWorldQuery = approvedWorldQuery(),
        )

        assertEquals("北京", received?.region)
        assertEquals("next-14-days", received?.timeWindow)
        assertFalse(received.toString().contains("周末半天"))
        assertFalse(received.toString().contains("赛车为什么过弯更快"))
    }

    @Test
    fun `optional world service failure cannot take down available recommendation sources`() = runBlocking {
        val unavailableWorld = object : WorldBriefProvider {
            override val id = "unavailable-world"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                capability.requireValid(id, kind, "fetch-public-world-brief")
                error("synthetic outage")
            }
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(),
            listOf(LocalDemoOpportunitySource()),
            unavailableWorld,
            approvedWorldQuery = approvedWorldQuery(),
        )

        assertTrue(result.opportunities.selected.isNotEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        assertEquals(listOf("world-brief"), result.sourceIssues.map { it.sourceKind })
    }

    @Test
    fun `failed AI source is disclosed while another source can still produce candidates`() = runBlocking {
        val failedAi = object : OpportunitySource {
            override val id = "failed-ai"
            override val kind = "byok-llm"
            override val acceptedDataCategories = setOf("current-interest", "practical-constraints")
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose, acceptedDataCategories)
                error("synthetic invalid response")
            }
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request(),
            listOf(failedAi, LocalDemoOpportunitySource()),
        )

        assertFalse("byok-llm" in result.sourceKinds)
        assertTrue("offline-demo" in result.sourceKinds)
        assertTrue(result.sourceIssues.any { it.sourceId == "failed-ai" && it.sourceKind == "byok-llm" })
        assertTrue(result.opportunities.selected.isNotEmpty())
    }

    @Test
    fun `public source failure reason survives without copying provider text`() = runBlocking {
        val incomplete = object : OpportunitySource {
            override val id = "synthetic-incomplete"
            override val kind = "byok-llm"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                throw SourceFailureException(SourceFailureReason.RESPONSE_INCOMPLETE)
            }
        }
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(incomplete))

        assertEquals(SourceFailureReason.RESPONSE_INCOMPLETE.code, result.sourceIssues.single().reasonCode)
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `request cancellation is never downgraded to an unavailable source`() = runBlocking {
        val cancelled = object : OpportunitySource {
            override val id = "cancelled-source"
            override val kind = "byok-llm"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                throw CancellationException("synthetic cancellation")
            }
        }

        val failure = runCatching {
            FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(cancelled))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `world brief becomes a family candidate only after local topic matching`() = runBlocking {
        val provider = object : WorldBriefProvider {
            override val id = "public-fixture"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability) = listOf(
                WorldBrief(
                    id = "racing-event",
                    title = "公开赛车体验日",
                    summary = "公开活动摘要",
                    topics = listOf("赛车", "机械"),
                    region = query.region ?: "未指定",
                    sourceTitle = "合成公开来源",
                    sourceUrl = "https://example.test/racing",
                    retrievedAt = "2026-09-13T00:00:00Z",
                    verification = Verification.VERIFIED,
                    timeMinutes = 75,
                    costBand = CostBand.FREE,
                    caregiverEnergy = EnergyBand.LOW,
                    travelMinutes = 20,
                    minAge = 9,
                    maxAge = 14,
                    bookingRequired = true,
                    verificationNotes = listOf("确认名额"),
                    sponsorship = "合成赞助披露",
                    trackingWarning = true,
                ),
                WorldBrief(
                    id = "unrelated-event",
                    title = "诗歌朗读会",
                    summary = "与当次兴趣无关",
                    topics = listOf("诗歌"),
                    region = query.region ?: "未指定",
                    sourceTitle = "合成公开来源",
                    sourceUrl = "https://example.test/poetry",
                    retrievedAt = "2026-09-13T00:00:00Z",
                    verification = Verification.VERIFIED,
                ),
            )
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy(clock = { Instant.parse("2026-09-14T10:00:00Z") })).discover(
            request(),
            listOf(WorldBriefOpportunitySource()),
            provider,
            approvedWorldQuery = approvedWorldQuery(),
        )

        assertTrue(result.opportunities.selected.any { it.opportunity.opportunityId == "world:racing-event" })
        assertFalse(result.opportunities.selected.any { it.opportunity.opportunityId == "world:unrelated-event" })
        val selected = result.opportunities.selected.first { it.opportunity.opportunityId == "world:racing-event" }
        assertEquals(75, selected.opportunity.requirements.timeMinutes)
        assertEquals(CostBand.FREE, selected.opportunity.requirements.costBand)
        assertTrue(selected.opportunity.bookingRequired)
        assertTrue("sponsored-content" in selected.warnings)
        assertTrue("source-tracking-warning" in selected.warnings)
    }

    @Test
    fun `localized public terms match Chinese interest without disclosing it to World Brief`() = runBlocking {
        var receivedQuery: PublicWorldQuery? = null
        val provider = object : WorldBriefProvider {
            override val id = "localized-public-fixture"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                receivedQuery = query
                return listOf(WorldBrief(
                    id = "moon-event",
                    title = "合成观月夜",
                    summary = "现场情况需重新确认",
                    topics = listOf("astronomy", "moon"),
                    matchTerms = listOf("月亮", "观月"),
                    region = query.region ?: "未指定",
                    sourceTitle = "合成公开来源",
                    sourceUrl = "https://example.test/moon",
                    retrievedAt = "2026-09-13T00:00:00Z",
                    verification = Verification.LIKELY,
                ))
            }
        }
        val engine = FamilyOpportunityEngine(DefaultOpportunityPolicy(clock = { Instant.parse("2026-09-14T10:00:00Z") }))
        val wanted = engine.discover(
            request().copy(currentInterest = "孩子想看月亮"),
            listOf(WorldBriefOpportunitySource()), provider, approvedWorldQuery = approvedWorldQuery(),
        )
        assertTrue(wanted.opportunities.selected.any { it.opportunity.opportunityId == "world:moon-event" })
        assertEquals(approvedWorldQuery(), receivedQuery)

        val refused = engine.discover(
            request().copy(currentInterest = "不要月亮，但想看天文"),
            listOf(WorldBriefOpportunitySource()), provider, approvedWorldQuery = approvedWorldQuery(),
        )
        assertFalse(refused.opportunities.selected.any { it.opportunity.opportunityId == "world:moon-event" })
    }

    @Test
    fun `expired and malformed public opportunities cannot remain selectable`() = runBlocking {
        val now = Instant.parse("2026-09-25T10:00:00Z")
        val base = WorldBrief(
            id = "future",
            title = "合成赛车开放日",
            summary = "只用于时效测试的公开活动",
            topics = listOf("赛车"),
            region = "北京",
            expiresAt = "2026-09-26T10:00:00Z",
            sourceTitle = "合成来源",
            sourceUrl = "https://example.test/event",
            retrievedAt = "2026-09-25T00:00:00Z",
            verification = Verification.VERIFIED,
            timeMinutes = 30,
            costBand = CostBand.FREE,
            caregiverEnergy = EnergyBand.LOW,
            travelMinutes = 10,
        )
        val provider = object : WorldBriefProvider {
            override val id = "expiring-public-fixture"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                capability.requireValid(id, kind, "fetch-public-world-brief")
                return listOf(
                    base.copy(id = "past", expiresAt = "2026-09-24T10:00:00Z"),
                    base.copy(id = "malformed", expiresAt = "not-an-instant"),
                    base.copy(id = "stale", retrievedAt = "2026-09-01T00:00:00Z"),
                    base.copy(id = "future-retrieval", retrievedAt = "2026-09-26T00:00:00Z"),
                    base,
                )
            }
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy(clock = { now })).discover(
            request(),
            listOf(WorldBriefOpportunitySource()),
            provider,
            approvedWorldQuery = approvedWorldQuery(),
        )
        assertEquals(listOf("world:future"), result.opportunities.selected.map { it.opportunity.opportunityId })
        assertTrue(result.opportunities.rejected.any {
            it.opportunity.opportunityId == "world:past" && "expired-source" in it.reasons
        })
        assertTrue(result.opportunities.rejected.any {
            it.opportunity.opportunityId == "world:malformed" && "invalid-expiry" in it.reasons
        })
        assertTrue(result.opportunities.rejected.any {
            it.opportunity.opportunityId == "world:stale" && "stale-world-brief" in it.reasons
        })
        assertTrue(result.opportunities.rejected.any {
            it.opportunity.opportunityId == "world:future-retrieval" && "future-world-retrieval-time" in it.reasons
        })
        assertTrue(result.opportunities.nothing.isNothing)
    }

    @Test
    fun `a past child veto cannot reactivate a world brief as current interest`() = runBlocking {
        val source = WorldBriefOpportunitySource()
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request().copy(
                currentInterest = "孩子这次主动想看诗歌",
                goals = FamilyGoals(child = "看看诗歌"),
                recentEvidence = listOf("孩子明确不要：赛车体验日［world-event］"),
            ),
            listOf(source),
            SyntheticWorldBriefProvider(),
            approvedWorldQuery = approvedWorldQuery(),
        )

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)
        val disclosure = result.sourceDisclosures.getValue(source.id)
        assertTrue("current-interest" in disclosure.includedCategories)
        assertFalse("recent-evidence-summaries" in disclosure.includedCategories)
    }

    @Test
    fun `current explicit refusal cannot become Pack or World child pull`() = runBlocking {
        val refused = request().copy(
            currentInterest = "孩子明确不想再看赛车",
            goals = FamilyGoals(child = "尊重孩子这次不想看赛车"),
        )
        val sources = listOf(
            DeclarativePackOpportunitySource(listOf(starterOpportunityPack())),
            WorldBriefOpportunitySource(),
        )
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            refused, sources, SyntheticWorldBriefProvider(), approvedWorldQuery = approvedWorldQuery(),
        )
        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue(result.opportunities.nothing.isNothing)

        val mixed = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            refused.copy(currentInterest = "不想赛车，但想看看汽车"),
            sources,
            SyntheticWorldBriefProvider(),
            approvedWorldQuery = approvedWorldQuery(),
        )
        assertTrue(mixed.opportunities.selected.isEmpty())
        assertTrue(mixed.opportunities.nothing.isNothing)
    }

    @Test
    fun `adult plan or public background invokes no discovery provider`() = runBlocking {
        var aiCalls = 0
        var worldCalls = 0
        val ai = object : LLMProvider {
            override val id = "adult-plan-ai-fixture"
            override val kind = "byok-llm"
            override val modelId = "synthetic"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                aiCalls++
                return listOf(fixtureOpportunity("should-not-appear", "making", 0.8))
            }
        }
        val world = object : WorldBriefProvider {
            override val id = "adult-plan-world-fixture"
            override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
                worldCalls++
                return emptyList()
            }
        }
        val sources = listOf(ai, DeclarativePackOpportunitySource(listOf(starterOpportunityPack())), WorldBriefOpportunitySource())
        for (expression in listOf("家长想带孩子看赛车", "天气预报说今晚能看到赛车活动")) {
            val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
                request().copy(currentInterest = expression), sources, world,
                approvedWorldQuery = approvedWorldQuery(),
            )
            assertTrue(result.opportunities.selected.isEmpty())
            assertTrue(result.opportunities.nothing.isNothing)
            assertTrue(result.sourceIssues.any { it.sourceKind == "world-brief" && it.reasonCode == "no-current-child-pull" })
        }
        assertEquals(0, aiCalls)
        assertEquals(0, worldCalls)
    }

    @Test
    fun `refusing an unrelated task does not remove a child led entrance`() = runBlocking {
        val interested = request().copy(currentInterest = "不想做题，但想知道赛车为什么转弯")
        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            interested,
            listOf(DeclarativePackOpportunitySource(listOf(starterOpportunityPack())), WorldBriefOpportunitySource()),
            SyntheticWorldBriefProvider(),
            approvedWorldQuery = approvedWorldQuery(),
        )
        assertTrue(result.opportunities.selected.any { it.opportunity.sourceKind == "pack" })
        assertTrue(result.opportunities.selected.any { it.opportunity.sourceKind == "world-brief" })

        val childWantsSameTopic = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            interested.copy(currentInterest = "家长不想去赛车现场，但孩子想在家看赛车"),
            listOf(DeclarativePackOpportunitySource(listOf(starterOpportunityPack())), WorldBriefOpportunitySource()),
            SyntheticWorldBriefProvider(),
            approvedWorldQuery = approvedWorldQuery(),
        )
        assertTrue(childWantsSameTopic.opportunities.selected.any { it.opportunity.sourceKind == "pack" })
        assertTrue(childWantsSameTopic.opportunities.selected.any { it.opportunity.sourceKind == "world-brief" })
    }

    @Test
    fun `gate rejects generated lesson disguised as a natural entry`() = runBlocking {
        val forcedLesson = object : OpportunitySource {
            override val id = "generated-fixture"
            override val kind = "llm"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose)
                return listOf(
                Opportunity(
                    opportunityId = "forced-lesson",
                    title = "完成赛车物理知识点练习",
                    explanation = "覆盖速度公式和十道题",
                    whyNow = "家长希望提前教学",
                    ecosystem = "课程",
                    primaryGoal = GoalOwner.CAREGIVER,
                    childPull = false,
                    requirements = OpportunityRequirements(30, CostBand.FREE, EnergyBand.LOW, 0),
                    sourceKind = "llm",
                    sourceUrl = "javascript:alert('bad')",
                    verification = Verification.IDEA,
                    riskLevel = RiskLevel.LOW,
                    naturalEntry = false,
                    interventionPressure = RiskLevel.HIGH,
                    score = 0.9,
                ),
                )
            }
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(request(), listOf(forcedLesson))
        val reasons = result.opportunities.rejected.single().reasons
        assertTrue("forced-educational-connection" in reasons)
        assertTrue("caregiver-goal-without-child-pull" in reasons)
        assertTrue("excessive-intervention-pressure" in reasons)
        assertTrue("unsafe-source-url" in reasons)
    }

    @Test
    fun `versioned gate rejects a credential bearing source link even when the entry otherwise fits`() {
        val candidate = fixtureOpportunity("linked-door", "real-world", 0.7).copy(
            sourceUrl = "https://name:secret@example.test/event",
        )
        val decision = DefaultOpportunityPolicy().evaluate(candidate, request())
        assertFalse(decision.allowed)
        assertTrue("unsafe-source-url" in decision.reasons)
    }

    @Test
    fun `provider rejects capability issued for another target`() = runBlocking {
        val source = LocalDemoOpportunitySource()
        val context = DefaultContextFirewall().minimize(request(), emptyList()).context
        val wrongCapability = issueProviderCapability(
            providerId = "org.foe.some-other-provider",
            kind = source.kind,
            purpose = context.purpose,
            dataCategories = listOf("current-interest"),
        )

        val failure = runCatching { source.discover(context, wrongCapability) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("target mismatch") == true)
    }

    @Test
    fun `provider rejects expired capability`() = runBlocking {
        val source = LocalDemoOpportunitySource()
        val context = DefaultContextFirewall().minimize(request(), emptyList()).context
        val expired = issueProviderCapability(
            providerId = source.id,
            kind = source.kind,
            purpose = context.purpose,
            dataCategories = listOf("current-interest", "practical-constraints"),
            now = Instant.EPOCH,
        )

        val failure = runCatching { source.discover(context, expired) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("expired") == true)
    }

    @Test
    fun `engine gives external LLM only its declared and minimized context view`() = runBlocking {
        var captured: TaskContext? = null
        val llm = object : LLMProvider {
            override val id = "llm.capture"
            override val kind = "byok-llm"
            override val modelId = "synthetic"
            override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                capability.requireValid(id, kind, context.purpose, setOf("current-interest", "practical-constraints"))
                captured = context
                return emptyList()
            }
        }

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
            request().copy(recentEvidence = listOf("private historical words")),
            listOf(llm),
            SyntheticWorldBriefProvider(),
            approvedWorldQuery = approvedWorldQuery(),
        )

        assertEquals("赛车为什么过弯更快", captured?.currentInterest)
        assertEquals(listOf("private historical words"), captured?.recentEvidenceSummaries)
        assertTrue(captured?.publicWorldBriefs?.isEmpty() == true)
        assertTrue("recent-evidence-summaries" in result.sourceDisclosures.getValue(llm.id).includedCategories)
        assertFalse("public-world-briefs" in result.sourceDisclosures.getValue(llm.id).includedCategories)
    }

    @Test
    fun `provider audit receipt stores categories but never raw context`() {
        val receipt = ContextDisclosureReceipt(
            purpose = "discover-family-opportunities",
            includedCategories = listOf("current-interest", "practical-constraints"),
            excludedCategories = listOf("names", "provider-secrets"),
        )
        val payload = receipt.toEventPayload("provider.demo", "model.demo", contextPersisted = false)

        assertEquals("false", payload["rawValuesStored"])
        assertTrue(payload.values.none { "赛车为什么过弯更快" in it })
        assertTrue("current-interest" in payload.getValue("includedCategories"))
    }

    private fun request(
        constraints: FamilyConstraints = FamilyConstraints(),
        recentInterventionCount: Int = 0,
    ) = OpportunityDiscoveryRequest(
        currentInterest = "赛车为什么过弯更快",
        age = 11,
        lifecycleStage = LifecycleStage.CO_SELECT,
        goals = FamilyGoals(child = "继续理解赛车"),
        constraints = constraints,
        schoolWindow = "两周后接触速度和力",
        lifeContext = "周末半天",
        region = "北京",
        recentInterventionCount = recentInterventionCount,
    )

    private fun approvedWorldQuery() = PublicWorldQuery(region = request().region, timeWindow = "next-14-days")

    private fun fixtureOpportunity(id: String, ecosystem: String, score: Double) = Opportunity(
        opportunityId = id,
        title = "合成入口 $id",
        explanation = "用于验证同一生态的别名不会被当作不同入口。",
        whyNow = "合成测试",
        ecosystem = ecosystem,
        primaryGoal = GoalOwner.CHILD,
        childPull = true,
        requirements = OpportunityRequirements(10, CostBand.FREE, EnergyBand.LOW, 0),
        sourceKind = "fixture",
        verification = Verification.IDEA,
        riskLevel = RiskLevel.LOW,
        score = score,
    )
}
