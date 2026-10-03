import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  assertEncryptedSyncObject,
  assertOpaqueSyncObjectId,
  assertPublicWorldQuery,
  assertSyncProvider,
  assertWorldBriefFeed,
  createOpaqueSyncObjectId,
  issueProviderCapability
} from "../packages/provider-sdk/src/index.js";
import { assertDeclarativePack, currentInterestHasNonRefusalClue, currentInterestRefusesTerm, opportunitiesFromPack } from "../packages/pack-sdk/src/index.js";
import { assertBrandConfig } from "../packages/brand-config/src/index.js";
import { createTaskContext, runOpportunityFlow } from "../packages/foe-core/src/index.js";
import { createDemoWorldBriefProvider } from "../examples/world-brief/weekend.demo.js";

test("World Brief public query rejects family context", () => {
  assert.throws(
    () => assertPublicWorldQuery({ region: "demo-city", interests: ["motorsport"] }),
    /private field: query.interests/
  );
  assert.throws(
    () => assertPublicWorldQuery({ filters: { childId: "child_demo" } }),
    /private field: query.filters.childId/
  );
  assert.throws(() => assertPublicWorldQuery({ currentInterest: "孩子的兴趣" }), /private field: query.currentInterest/);
  assert.throws(() => assertPublicWorldQuery({ topicHint: "private" }), /unsupported field: query.topicHint/);
  assert.throws(() => assertPublicWorldQuery({ categories: ["public", "private".repeat(30)] }), /bounded public string list/);
});

test("public SDK validates the World Brief feed consumed by Android", () => {
  const feed = {
    schema: "org.foe.world-brief-feed/v1",
    briefs: [{
      id: "synthetic-event",
      title: "Public astronomy evening",
      summary: "Synthetic public record for conformance testing.",
      topics: ["astronomy", "moon"],
      matchTerms: ["月亮", "观月"],
      region: "demo-city",
      sourceTitle: "Synthetic publisher",
      sourceUrl: "https://example.test/event",
      retrievedAt: "2026-09-14T00:00:00Z",
      verification: "VERIFIED",
      timeMinutes: 90,
      costBand: "LOW",
      caregiverEnergy: "MEDIUM",
      travelMinutes: 25,
      minAge: 8,
      maxAge: 13,
      bookingRequired: true,
      verificationNotes: ["Confirm availability and supervision"],
      sponsorship: "Synthetic sponsor disclosure",
      trackingWarning: true
    }]
  };
  assert.equal(assertWorldBriefFeed(feed), feed);
  assert.throws(
    () => assertWorldBriefFeed({ ...feed, briefs: [{ ...feed.briefs[0], matchTerms: [1] }] }),
    /matchTerms must contain/
  );
  assert.throws(
    () => assertWorldBriefFeed({ ...feed, briefs: [{ ...feed.briefs[0], sourceUrl: "javascript:alert(1)" }] }),
    /valid HTTPS URL/
  );
  for (const sourceUrl of [
    "https://name:secret@example.test/event",
    "https://example.test/event#private-fragment",
    " https://example.test/event"
  ]) {
    assert.throws(
      () => assertWorldBriefFeed({ ...feed, briefs: [{ ...feed.briefs[0], sourceUrl }] }),
      /valid HTTPS URL/
    );
  }
  assert.throws(
    () => assertWorldBriefFeed({ ...feed, briefs: [{ ...feed.briefs[0], minAge: 15, maxAge: 8 }] }),
    /minAge cannot exceed maxAge/
  );
});

test("v0.1 Pack is declarative while dynamic behavior belongs to Provider", () => {
  assert.throws(
    () => assertDeclarativePack({ schema: "org.foe.pack/v1", id: "bad", version: "1", script: "run()", opportunities: [] }),
    /must be declarative/
  );
});

test("Pack automatic matching fails closed and ignores an unrelated use of speed", async () => {
  const pack = JSON.parse(await readFile(new URL("../examples/packs/motorsport.demo.json", import.meta.url), "utf8"));

  assert.equal(opportunitiesFromPack(pack).length, 0);
  assert.equal(opportunitiesFromPack(pack, { currentInterest: "怎样提高阅读速度" }).length, 0);
  assert.ok(opportunitiesFromPack(pack, { currentInterest: "最近在研究赛车调校" }).length > 0);
  assert.equal(opportunitiesFromPack(pack, { currentInterest: "孩子明确不想再看赛车" }).length, 0);
  assert.equal(opportunitiesFromPack(pack, { currentInterest: "不想赛车，但想看看汽车" }).length, 0);
  assert.ok(opportunitiesFromPack(pack, { currentInterest: "不想做题，但想知道赛车为什么转弯" }).length > 0);
  assert.ok(opportunitiesFromPack(pack, { currentInterest: "孩子特别喜欢赛车" }).length > 0);
  assert.equal(opportunitiesFromPack(pack, { currentInterest: "家长想带孩子看赛车" }).length, 0);
  assert.equal(opportunitiesFromPack(pack, { currentInterest: "新闻报道周末有赛车比赛" }).length, 0);
  assert.ok(opportunitiesFromPack(pack, { currentInterest: "家长想上物理课，但孩子想看赛车" }).length > 0);
  assert.ok(opportunitiesFromPack(pack, { currentInterest: "家长不想去赛车现场，但孩子想在家看赛车" }).length > 0);
  assert.equal(opportunitiesFromPack(pack, { currentInterest: "孩子不想看赛车，但家长想去赛车现场" }).length, 0);
  assert.equal(currentInterestHasNonRefusalClue("parent doesn't want to watch racing"), false);
  assert.equal(currentInterestRefusesTerm("parent doesn't want racing, but child wants racing", "racing"), false);

  const missingTerms = structuredClone(pack);
  missingTerms.opportunities.forEach((entry) => { entry.triggerTerms = []; });
  assert.equal(opportunitiesFromPack(missingTerms, { currentInterest: "赛车" }).length, 0);
});

test("Android reference app ships the same public Pack v1 contract", async () => {
  const pack = JSON.parse(
    await readFile(
      new URL("../apps/jianyu-android/app/src/main/assets/packs/starter-motorsport.json", import.meta.url),
      "utf8"
    )
  );
  assert.equal(assertDeclarativePack(pack), pack);
  assert.equal(pack.schema, "org.foe.pack/v1");
  assert.ok(pack.opportunities.every((item) => item.schema === "org.foe.opportunity/v1"));
});

test("Android and public SDK consume the same Jianyu BrandConfig", async () => {
  const publicConfig = JSON.parse(await readFile(new URL("../packages/brand-config/jianyu.json", import.meta.url), "utf8"));
  const androidConfig = JSON.parse(
    await readFile(new URL("../apps/jianyu-android/app/src/main/assets/brand/jianyu.json", import.meta.url), "utf8")
  );
  assert.equal(assertBrandConfig(publicConfig), publicConfig);
  assert.deepEqual(androidConfig, publicConfig);
});

test("BrandConfig cannot reintroduce streak or missed-opportunity pressure", () => {
  assert.throws(
    () => assertBrandConfig({
      schema: "org.foe.brand-config/v1",
      id: "bad",
      productName: "Bad",
      displayNameZhCN: "坏配置",
      engineName: "FOE",
      taglineZhCN: "bad",
      heroZhCN: "bad",
      missionZhCN: "bad",
      features: { dailyStreak: true }
    }),
    /engagement pressure/
  );
});

test("Context Firewall projection omits direct identifiers and raw expressions", () => {
  const context = createTaskContext({
    purpose: "generate-candidates",
    ageBand: "10-12",
    currentInterest: "孩子主动想知道赛车如何过弯",
    legalName: "Synthetic Child",
    exactAddress: "Synthetic Address",
    goals: [{ owner: "child", value: "赛车", householdId: "private" }],
    constraints: { timeMinutes: 60, exactAddress: "private" },
    recentEvidence: [{ expression: "private raw words", topicHints: ["motorsport"], childName: "private" }]
  }, ["current-interest", "age-band", "declared-goals", "practical-constraints", "recent-evidence-summaries"]);
  assert.equal(context.currentInterest, "孩子主动想知道赛车如何过弯");
  assert.equal(context.legalName, undefined);
  assert.equal(context.exactAddress, undefined);
  assert.equal(context.goals[0].householdId, undefined);
  assert.equal(context.constraints.exactAddress, undefined);
  assert.equal(context.recentEvidence[0].expression, undefined);
  assert.equal(context.recentEvidence[0].childName, undefined);
  assert.deepEqual(context.recentEvidence[0].topicHints, ["motorsport"]);
  assert.throws(() => createTaskContext({ purpose: "test", currentInterest: "赛车" }), /current-interest approval/);
  assert.throws(() => createTaskContext({
    purpose: "test", currentInterest: "赛车", constraints: { timeMinutes: { exactAddress: "private" } }
  }, ["current-interest", "practical-constraints"]), /bounded minute count/);
});

test("AI reference Provider receives only the exact approved current-interest payload", async () => {
  const request = {
    purpose: "synthetic-ai-discovery",
    currentInterest: "孩子主动想知道赛车如何过弯",
    legalName: "Synthetic Child",
    exactAddress: "Synthetic Address",
    constraints: { timeMinutes: 60, costBand: "low", caregiverEnergy: "low", travelMinutesMax: 0 },
    goals: [{ owner: "child", value: "探索过弯", householdId: "private" }]
  };
  const categories = ["current-interest", "declared-goals", "practical-constraints"];
  const preview = createTaskContext(request, categories);
  const received = [];
  const pack = JSON.parse(await readFile(new URL("../examples/packs/motorsport.demo.json", import.meta.url), "utf8"));
  const fakeAiCandidate = {
    ...pack.opportunities[0],
    opportunityId: "synthetic-ai-entrance",
    source: { kind: "world-brief", publisher: "synthetic-test" }
  };
  const provider = {
    kind: "llm",
    describe() { return { id: "synthetic-llm", kind: "llm" }; },
    async generateCandidates(context) { received.push(context); return [fakeAiCandidate]; }
  };
  const run = (changedRequest, approvedLlmContext, approvedLlmCategories = categories) => runOpportunityFlow({
    request: changedRequest,
    providers: { llm: provider },
    providerCapabilities: {
      llm: issueProviderCapability({
        kind: "llm",
        purpose: request.purpose,
        dataCategories: ["minimized-task-context", ...categories]
      })
    },
    approvedLlmCategories,
    approvedLlmContext
  });

  const allowed = await run(request, preview);
  assert.equal(received.length, 1);
  assert.deepEqual(received[0], preview);
  assert.equal(JSON.stringify(received[0]).includes("Synthetic Child"), false);
  assert.equal(allowed.selected.length, 1);
  assert.equal(allowed.selected[0].candidate.source.kind, "llm");
  assert.equal(allowed.nothing.type, "nothing");
  const changed = await run({ ...request, currentInterest: "孩子这次想看火车" }, preview);
  assert.equal(changed.provenance.find((item) => item.source === "llm").reason, "outside-approved-scope");
  const missing = await run(request, null);
  assert.equal(missing.provenance.find((item) => item.source === "llm").reason, "outside-approved-scope");
  const noInterestApproval = await run(request, preview, ["declared-goals"]);
  assert.equal(noInterestApproval.provenance.find((item) => item.source === "llm").reason, "outside-approved-scope");
  const refused = await run({ ...request, currentInterest: "孩子明确不想再看赛车" }, preview);
  assert.equal(refused.provenance.find((item) => item.source === "llm").reason, "no-current-child-pull");
  assert.equal(refused.selected.length, 0);
  const neutralAfterRefusal = await run({ ...request, currentInterest: "不要赛车，今天下雨了" }, preview);
  assert.equal(neutralAfterRefusal.provenance.find((item) => item.source === "llm").reason, "no-current-child-pull");
  assert.equal(neutralAfterRefusal.selected.length, 0);
  const adultPlan = await run({ ...request, currentInterest: "家长想带孩子看赛车" }, preview);
  assert.equal(adultPlan.provenance.find((item) => item.source === "llm").reason, "no-current-child-pull");
  assert.equal(received.length, 1);
  const positiveRequest = { ...request, currentInterest: "不想做题，但想知道赛车如何过弯" };
  const positivePreview = createTaskContext(positiveRequest, categories);
  const positiveAfterRefusal = await run(positiveRequest, positivePreview);
  assert.equal(positiveAfterRefusal.provenance.find((item) => item.source === "llm").count, 1);
  assert.equal(positiveAfterRefusal.selected.length, 1);
  assert.equal(received.length, 2);
});

test("reference flow combines Pack and dynamic World Brief then keeps Nothing", async () => {
  const pack = JSON.parse(await readFile(new URL("../examples/packs/motorsport.demo.json", import.meta.url), "utf8"));
  const result = await runOpportunityFlow({
    request: {
      purpose: "weekend-opportunities",
      currentInterest: "最近主动比较赛车调校",
      ageBand: "10-12",
      goals: [{ owner: "child", value: "赛车" }],
      constraints: { timeMinutes: 120, costBand: "low", caregiverEnergy: "low", travelMinutesMax: 30 },
      recentEvidence: [{ expression: "private", topicHints: ["motorsport"] }],
      worldQuery: { region: "demo-city", timeWindow: "this-weekend" }
    },
    pack,
    providers: { worldBrief: createDemoWorldBriefProvider() },
    approvedWorldQuery: { region: "demo-city", timeWindow: "this-weekend" },
    providerCapabilities: {
      worldBrief: issueProviderCapability({
        kind: "world-brief",
        purpose: "weekend-opportunities",
        dataCategories: ["public-world-query"]
      })
    }
  });

  assert.ok(result.provenance.some((item) => item.source === "world-brief"));
  assert.ok(result.selected.some((item) => item.candidate.ecosystem === "live-sport"));
  assert.equal(result.nothing.type, "nothing");
  assert.ok(result.evaluated.some((item) => item.decision.reasons.includes("caregiver-goal-without-child-pull")));
});

test("public World Brief stays dynamic but needs local positive topic matching", async () => {
  const provider = createDemoWorldBriefProvider();
  let receivedQuery;
  let calls = 0;
  const capturingProvider = {
    ...provider,
    async getBrief(query) {
      calls += 1;
      receivedQuery = query;
      return provider.getBrief(query);
    }
  };
  const run = (currentInterest, approvedWorldQuery = { region: "demo-city", timeWindow: "this-weekend" }) => runOpportunityFlow({
    request: {
      purpose: "world-topic-check",
      currentInterest,
      constraints: { timeMinutes: 120, costBand: "low", caregiverEnergy: "low", travelMinutesMax: 30 },
      worldQuery: { region: "demo-city", timeWindow: "this-weekend" }
    },
    providers: { worldBrief: capturingProvider },
    approvedWorldQuery,
    providerCapabilities: {
      worldBrief: issueProviderCapability({
        kind: "world-brief",
        purpose: "world-topic-check",
        dataCategories: ["public-world-query"]
      })
    }
  });

  const refused = await run("孩子明确不想再看赛车");
  assert.equal(refused.provenance.find((item) => item.source === "world-brief").count, 0);
  assert.equal(refused.selected.length, 0);
  assert.equal(refused.nothing.type, "nothing");
  const mixed = await run("不想赛车，但想看看汽车");
  assert.equal(mixed.provenance.find((item) => item.source === "world-brief").count, 0);
  const interested = await run("不想做题，但想知道赛车为什么转弯");
  assert.equal(interested.provenance.find((item) => item.source === "world-brief").count, 1);
  assert.equal(interested.selected.length, 1);
  const adultPlan = await run("家长想带孩子看赛车");
  assert.equal(adultPlan.provenance.find((item) => item.source === "world-brief").reason, "no-current-child-pull");
  const news = await run("新闻报道周末有赛车比赛");
  assert.equal(news.provenance.find((item) => item.source === "world-brief").reason, "no-current-child-pull");
  const childOverParent = await run("家长不想去赛车现场，但孩子想在家看赛车");
  assert.equal(childOverParent.provenance.find((item) => item.source === "world-brief").count, 1);
  const mismatched = await run("赛车", { region: "another-city", timeWindow: "this-weekend" });
  assert.equal(mismatched.provenance.find((item) => item.source === "world-brief").reason, "outside-approved-scope");
  const unapproved = await run("赛车", null);
  assert.equal(unapproved.provenance.find((item) => item.source === "world-brief").reason, "outside-approved-scope");
  assert.equal(calls, 3);
  assert.deepEqual(receivedQuery, { region: "demo-city", timeWindow: "this-weekend" });
});

test("public candidate uses optional localized terms on-device and respects refusal", async () => {
  const publicCandidate = { ...(await createDemoWorldBriefProvider().getBrief({ region: "demo-city" }))[0],
    topics: ["astronomy", "moon"], matchTerms: ["月亮", "观月"] };
  const received = [];
  const provider = {
    ...createDemoWorldBriefProvider(),
    async getBrief(query) { received.push(query); return [publicCandidate]; }
  };
  const run = (currentInterest) => runOpportunityFlow({
    request: {
      purpose: "localized-world-topic-check", currentInterest,
      worldQuery: { region: "demo-city", timeWindow: "this-weekend" }
    },
    providers: { worldBrief: provider },
    approvedWorldQuery: { region: "demo-city", timeWindow: "this-weekend" },
    providerCapabilities: { worldBrief: issueProviderCapability({
      kind: "world-brief", purpose: "localized-world-topic-check", dataCategories: ["public-world-query"]
    }) }
  });
  const wanted = await run("孩子想看月亮");
  assert.equal(wanted.provenance.find((item) => item.source === "world-brief").count, 1);
  const refused = await run("不要月亮，但想看天文");
  assert.equal(refused.provenance.find((item) => item.source === "world-brief").count, 0);
  assert.deepEqual(received, [
    { region: "demo-city", timeWindow: "this-weekend" },
    { region: "demo-city", timeWindow: "this-weekend" }
  ]);
});

test("public Search also requires exact query approval and local topic matching", async () => {
  const publicCandidate = (await createDemoWorldBriefProvider().getBrief({ region: "demo-city" }))[0];
  const received = [];
  const search = {
    kind: "search",
    describe() { return { id: "synthetic-public-search", kind: "search" }; },
    async searchPublic(query) { received.push(query); return [publicCandidate]; }
  };
  const request = {
    purpose: "synthetic-public-search",
    currentInterest: "孩子想看看赛车",
    constraints: { timeMinutes: 120, costBand: "low", caregiverEnergy: "low", travelMinutesMax: 30 },
    worldQuery: { region: "demo-city", timeWindow: "this-weekend" }
  };
  const run = (interest, approval) => runOpportunityFlow({
    request: { ...request, currentInterest: interest },
    providers: { search },
    approvedWorldQuery: approval,
    providerCapabilities: {
      search: issueProviderCapability({
        kind: "search", purpose: request.purpose, dataCategories: ["public-world-query"]
      })
    }
  });
  const unapproved = await run("赛车", null);
  assert.equal(unapproved.provenance.find((item) => item.source === "search").reason, "outside-approved-scope");
  assert.equal(received.length, 0);
  const refused = await run("孩子明确不想再看赛车", request.worldQuery);
  assert.equal(refused.selected.length, 0);
  const interested = await run("孩子想看看赛车", request.worldQuery);
  assert.equal(interested.selected.length, 1);
  assert.deepEqual(received, [request.worldQuery]);
});

test("provider execution is denied without a short-lived purpose capability", async () => {
  await assert.rejects(
    () => runOpportunityFlow({
      request: {
        purpose: "public-world-test",
        constraints: { timeMinutes: 30, costBand: "free", caregiverEnergy: "low", travelMinutesMax: 0 },
        worldQuery: { region: "demo-city", timeWindow: "today" }
      },
      providers: { worldBrief: createDemoWorldBriefProvider() }
    }),
    /no issued capability/
  );
});

test("public SyncProvider boundary accepts only opaque IDs and bounded ciphertext", () => {
  const objectId = createOpaqueSyncObjectId();
  assert.equal(objectId.length, 24);
  assert.equal(assertOpaqueSyncObjectId(objectId), objectId);
  assert.throws(() => assertOpaqueSyncObjectId("../../family.json"), /opaque Base64URL/);
  assert.throws(() => assertOpaqueSyncObjectId("550e8400-e29b-41d4-a716-446655440000"), /opaque Base64URL/);

  const encrypted = new TextEncoder().encode("opaque encrypted envelope");
  assert.equal(assertEncryptedSyncObject(encrypted), encrypted);
  assert.throws(() => assertEncryptedSyncObject("plaintext"), /bounded Uint8Array/);

  const provider = {
    kind: "sync",
    describe() { return { id: "test-sync" }; },
    listOpaqueObjects() {},
    putEncryptedObject() {},
    getEncryptedObject() {},
    deleteEncryptedObject() {}
  };
  assert.equal(assertSyncProvider(provider), provider);
  assert.throws(() => assertSyncProvider({ ...provider, getEncryptedObject: null }), /getEncryptedObject/);
});
