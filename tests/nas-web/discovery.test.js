// Tests for the browser discovery chain. The point is the boundary: what the
// offline demonstration sends (nothing), what the AI and World Brief providers
// are allowed to receive (exactly the minimized payload), and what the engine
// still refuses to do even when a provider offers it.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import {
  AI_CONTEXT_CATEGORIES,
  DiscoveryError,
  ageBandFor,
  buildDiscoveryRequest,
  planDiscovery,
  runDiscovery
} from "../../apps/jianyu-web-nas/web/src/discovery-service.js";
import { createLlmProvider } from "../../apps/jianyu-web-nas/web/src/ai-provider.js";
import { createWorldBriefProvider } from "../../apps/jianyu-web-nas/web/src/world-brief-client.js";

const INTEREST = "孩子最近主动说自己很喜欢赛车，想弄明白轮胎为什么能抓地";

function yearsAgo(years) {
  const date = new Date();
  date.setUTCFullYear(date.getUTCFullYear() - years);
  return date.toISOString().slice(0, 10);
}

const CHILD = { id: "child-1", displayName: "小隅", birthDate: yearsAgo(8) };

function requestFor(overrides = {}) {
  return buildDiscoveryRequest({
    child: CHILD,
    interest: INTEREST,
    constraints: { timeMinutes: 90 },
    worldQuery: { region: "demo-city", categories: ["sport", "technology"] },
    ...overrides
  });
}

/** A provider key and endpoint that never reach a real service in these tests. */
// Assembled from parts so no credential-shaped literal lands in the repository.
const SYNTHETIC_KEY = ["synthetic", "not", "a", "real", "key"].join("-");
const CONNECTION = Object.freeze({
  endpoint: "https://api.example.invalid/v1/chat/completions",
  apiKey: SYNTHETIC_KEY,
  model: "synthetic-model",
  route: "proxy"
});

function fakeAiApi(reply) {
  const calls = [];
  return {
    calls,
    async proxyAiCompletion({ endpoint, apiKey, payload }) {
      calls.push({ endpoint, apiKey, payload });
      return { proxied: true, ok: true, status: 200, body: JSON.stringify({ choices: [{ message: { content: reply } }] }) };
    }
  };
}

const AI_REPLY = JSON.stringify({
  opportunities: [
    {
      title: "一起拆开一只旧轮胎看看花纹",
      ecosystem: "making",
      entryPoint: { motivation: "real-machines", whyNow: "孩子自己在问抓地这件事", startupCost: "low" },
      goalAlignment: { primary: "child" },
      childPull: true,
      requirements: { timeMinutes: 45, costBand: "free-existing", caregiverEnergy: "low", travelMinutes: 0 },
      verification: "idea",
      explanation: "用家里已有的东西讲清一个真问题，不引入新课程。",
      topics: ["赛车", "轮胎"],
      source: { kind: "world-brief", publisher: "forged-by-the-model" }
    },
    {
      title: "在平地上试不同花纹的抓地",
      ecosystem: "experiment",
      entryPoint: { motivation: "test-a-hypothesis", whyNow: "把问题变成一个能试的小实验", startupCost: "low" },
      goalAlignment: { primary: "child" },
      childPull: true,
      requirements: { timeMinutes: 30, costBand: "free-existing", caregiverEnergy: "low", travelMinutes: 0 },
      verification: "idea",
      explanation: "只比较观察，不给结论打分。",
      topics: ["抓地", "摩擦"]
    }
  ]
});

function worldProviderFor(replies = {}) {
  const queries = [];
  const inner = createWorldBriefProvider({
    feedUrl: "https://feeds.example.invalid/weekend.json",
    api: {
      async proxyFeed({ url }) {
        replies.feedCalls?.push(url);
        return {
          proxied: true,
          ok: true,
          status: 200,
          body: replies.body ?? readFileSync("examples/world-brief/weekend.demo.json", "utf8")
        };
      }
    }
  });
  // A recording wrapper rather than a patch: the provider itself is frozen.
  const provider = {
    kind: inner.kind,
    describe: () => inner.describe(),
    getBrief: async (query) => {
      queries.push(query);
      return inner.getBrief(query);
    }
  };
  provider.queries = queries;
  return provider;
}

describe("discovery request building", () => {
  it("maps a child to the lifecycle band and refuses below age 4", () => {
    assert.equal(ageBandFor(CHILD), "7-9");
    assert.equal(ageBandFor({ birthDate: yearsAgo(3) }), null);
    assert.throws(() => buildDiscoveryRequest({ child: { birthDate: yearsAgo(3) }, interest: INTEREST }), DiscoveryError);
  });

  it("refuses an empty interest instead of inventing an entry point", () => {
    assert.throws(() => buildDiscoveryRequest({ child: CHILD, interest: "   " }), /请先写一句/);
  });

  it("sends only the approved categories in the AI task context", () => {
    const request = requestFor();
    const plan = planDiscovery({ request });
    assert.deepEqual([...plan.aiCategories].sort(), [...AI_CONTEXT_CATEGORIES].sort());
    const serialized = JSON.stringify(plan.taskContext);
    for (const forbidden of ["childId", "householdId", "birthDate", "displayName", "小隅", "deviceId"]) {
      assert.equal(serialized.includes(forbidden), false, `the task context must not contain ${forbidden}`);
    }
    assert.equal(plan.taskContext.currentInterest, INTEREST);
    assert.equal(plan.taskContext.ageBand, "7-9");
  });

  it("drops categories the family did not approve", () => {
    const plan = planDiscovery({ request: requestFor(), aiCategories: ["current-interest"] });
    assert.deepEqual([...plan.aiCategories], ["current-interest"]);
    assert.equal(plan.taskContext.ageBand, undefined);
    assert.equal(plan.taskContext.constraints, undefined);
  });
});

describe("offline demonstration", () => {
  it("offers doors from different ecosystems plus留白 without any network", async () => {
    const request = requestFor();
    const { result, plan } = await runDiscovery({ request, sources: { offline: true } });

    assert.equal(plan.purpose, "find-family-opportunities");
    assert.ok(result.selected.length >= 2, "the demonstration offers more than one door");
    const ecosystems = new Set(result.selected.map((item) => item.candidate.ecosystem));
    assert.ok(ecosystems.size >= 2, "doors come from different ecosystems, not variants of one activity");
    assert.equal(result.nothing.reason, "family-choice", "留白 is offered alongside the doors");
    assert.equal(result.nothing.title, "什么都不做");
    assert.ok(
      result.selected.some((item) => item.candidate.title.includes("赛车")),
      "one door names the child's own words"
    );
    assert.deepEqual(result.provenance, [{ source: "pack", count: 5 }]);
    for (const item of result.selected) {
      assert.equal(item.candidate.source.kind, "pack");
      assert.equal(item.candidate.verification, "idea");
    }
  });

  it("manufactures nothing from a refusal", async () => {
    const request = requestFor({ interest: "不想上学了，也不要再提赛车" });
    const { result } = await runDiscovery({ request, sources: { offline: true } });
    assert.equal(result.selected.length, 0);
    assert.equal(result.nothing.reason, "no-natural-entry-point");
    assert.equal(result.provenance[0].count, 0);
  });
});

describe("AI source", () => {
  it("sends exactly the approved task context and rebinds the source kind", async () => {
    const request = requestFor();
    const api = fakeAiApi(AI_REPLY);
    const provider = createLlmProvider({ connection: CONNECTION, api });
    const { result, plan } = await runDiscovery({ request, sources: { offline: false, ai: provider } });

    assert.equal(api.calls.length, 1, "one call for one confirmation");
    const { endpoint, apiKey, payload } = api.calls[0];
    assert.equal(endpoint, CONNECTION.endpoint);
    assert.equal(apiKey, CONNECTION.apiKey);
    assert.equal(payload.model, CONNECTION.model);
    assert.equal(JSON.parse(payload.messages[1].content).currentInterest, plan.taskContext.currentInterest);
    assert.deepEqual(JSON.parse(payload.messages[1].content), plan.taskContext, "the payload is the reviewed context");
    assert.equal(payload.messages[1].content.includes("小隅"), false, "the child's name is not in the payload");

    const kinds = new Set(result.selected.map((item) => item.candidate.source.kind));
    assert.deepEqual([...kinds], ["llm"], "a candidate cannot claim another source's kind");
    assert.equal(result.selected[0].candidate.source.model, CONNECTION.model);
    assert.equal(result.provenance.find((entry) => entry.source === "llm").count, 2);
  });

  it("drops an unusable answer instead of showing half of it", async () => {
    const api = fakeAiApi(JSON.stringify({ opportunities: [{ title: "只有标题" }] }));
    const provider = createLlmProvider({ connection: CONNECTION, api });
    await assert.rejects(
      () => runDiscovery({ request: requestFor(), sources: { offline: false, ai: provider } }),
      /AI 服务返回的选项无法使用/
    );
  });

  it("is not called at all when the interest carries no child pull", async () => {
    const api = fakeAiApi(AI_REPLY);
    const provider = createLlmProvider({ connection: CONNECTION, api });
    const { result } = await runDiscovery({
      request: requestFor({ interest: "天气预报说周末有雨" }),
      sources: { offline: true, ai: provider }
    });
    assert.equal(api.calls.length, 0, "a paid call must not happen without a child's pull");
    assert.equal(result.provenance.find((entry) => entry.source === "llm").reason, "no-current-child-pull");
  });
});

describe("World Brief source", () => {
  it("sends only the approved public query and filters by region locally", async () => {
    const request = requestFor();
    const provider = worldProviderFor();
    const { result } = await runDiscovery({ request, sources: { offline: false, world: provider } });

    assert.equal(provider.queries.length, 1);
    assert.deepEqual({ ...provider.queries[0] }, { ...request.worldQuery }, "the provider sees only the approved query");
    assert.deepEqual(Object.keys(provider.queries[0]).sort(), ["categories", "language", "region", "timeWindow"]);
    const world = result.selected.filter((item) => item.candidate.source.kind === "world-brief");
    assert.equal(world.length, 1, "the feed's other-region brief is filtered out before matching");
    assert.equal(world[0].candidate.opportunityId, "demo-weekend-race-screening");
    assert.equal(world[0].candidate.verification, "likely");
    assert.equal(world[0].candidate.minAge, 6);
    assert.equal(result.provenance.find((entry) => entry.source === "world-brief").count, 1);
  });

  it("drops briefs whose public terms do not match the child's words", async () => {
    const request = requestFor({
      interest: "孩子主动说想研究星空和望远镜",
      // The demonstration night runs longer than the default allowance in this
      // suite, so the case states its own budget rather than having the Gate
      // reject it for a reason this test is not about.
      constraints: { timeMinutes: 180 }
    });
    const provider = worldProviderFor();
    const { result } = await runDiscovery({ request, sources: { offline: false, world: provider } });
    const world = result.selected.filter((item) => item.candidate.source.kind === "world-brief");
    assert.equal(world.length, 1);
    assert.equal(world[0].candidate.opportunityId, "demo-weekend-library-astronomy");
  });

  it("refuses a feed that is not a World Brief feed", async () => {
    const provider = worldProviderFor({ body: JSON.stringify({ schema: "something-else", briefs: [] }) });
    await assert.rejects(
      () => runDiscovery({ request: requestFor(), sources: { offline: false, world: provider } }),
      /unsupported World Brief feed schema/
    );
  });
});
