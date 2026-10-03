import test from "node:test";
import assert from "node:assert/strict";
import { buildOptionSet, gateOpportunity } from "../packages/foe-opportunity/src/index.js";

function candidate(overrides = {}) {
  return {
    schema: "org.foe.opportunity/v1",
    opportunityId: "opp_demo",
    title: "合成机会",
    entryPoint: { whyNow: "孩子最近主动选择", startupCost: "low" },
    ecosystem: "making",
    goalAlignment: { primary: "child", secondary: [] },
    childPull: true,
    requirements: {
      timeMinutes: 30,
      costBand: "free",
      caregiverEnergy: "low",
      travelMinutes: 0
    },
    source: { kind: "pack", publisher: "demo" },
    verification: "idea",
    risks: [],
    score: 0.5,
    ...overrides
  };
}

const context = {
  constraints: {
    timeMinutes: 60,
    costBand: "low",
    caregiverEnergy: "low",
    travelMinutesMax: 30
  }
};

test("Gate rejects an infeasible high-energy candidate", () => {
  const result = gateOpportunity(
    candidate({ requirements: { timeMinutes: 30, costBand: "free", caregiverEnergy: "high", travelMinutes: 0 } }),
    context
  );
  assert.equal(result.result, "reject");
  assert.ok(result.reasons.includes("exceeds-caregiver-energy"));
});

test("Gate treats zero available time and zero-duration activity claims as a real pause", () => {
  const noTime = gateOpportunity(candidate({
    requirements: { timeMinutes: 0, costBand: "free-existing", caregiverEnergy: "none", travelMinutes: 0 },
  }), { constraints: { ...context.constraints, timeMinutes: 0, caregiverEnergy: "none" } });
  assert.equal(noTime.result, "reject");
  assert.ok(noTime.reasons.includes("no-available-time"));
  assert.ok(noTime.reasons.includes("invalid-duration"));

  const fakeInstant = gateOpportunity(candidate({
    requirements: { timeMinutes: 0, costBand: "free-existing", caregiverEnergy: "none", travelMinutes: 0 },
  }), context);
  assert.equal(fakeInstant.result, "reject");
  assert.ok(fakeInstant.reasons.includes("invalid-duration"));
});

test("Provider cannot inject an extra Nothing candidate", () => {
  const forged = gateOpportunity(candidate({ ecosystem: "nothing", type: "nothing" }), context);
  assert.equal(forged.result, "reject");
  assert.ok(forged.reasons.includes("reserved-nothing-option"));
});

test("Gate rejects caregiver curriculum goal without child pull", () => {
  const result = gateOpportunity(
    candidate({ goalAlignment: { primary: "caregiver", secondary: [] }, childPull: false }),
    context
  );
  assert.equal(result.result, "reject");
  assert.ok(result.reasons.includes("caregiver-goal-without-child-pull"));
});

test("Gate rejects a child-labeled candidate without real child pull", () => {
  const result = gateOpportunity(candidate({ childPull: false, score: 0.99 }), context);
  assert.equal(result.result, "reject");
  assert.ok(result.reasons.includes("insufficient-child-pull"));
});

test("Gate does not trust a child-led label on an explicit daily worksheet", () => {
  const worksheet = candidate({ title: "每天做三页速度练习", childPull: true, goalAlignment: { primary: "child", secondary: [] } });
  const rejected = gateOpportunity(worksheet, context);
  assert.equal(rejected.result, "reject");
  assert.ok(rejected.reasons.includes("daily-task-pressure"));
  const chosenStudy = gateOpportunity(candidate({ title: "孩子自己想弄懂速度公式" }), context);
  assert.equal(chosenStudy.result, "allow");
});

test("Gate notices a daily assignment hidden after a natural title without treating negation as a task", () => {
  const candidateWithTask = candidate({
    title: "用纸板观察赛车转弯",
    explanation: "先做一个纸板弯道，然后每天做三页速度练习。",
  });
  const rejected = gateOpportunity(candidateWithTask, context);
  assert.equal(rejected.result, "reject");
  assert.ok(rejected.reasons.includes("daily-task-pressure"));
  const voluntary = gateOpportunity(candidate({
    title: candidateWithTask.title,
    explanation: "孩子自己想知道为什么会打滑，不需要每天做三页练习。",
  }), context);
  assert.equal(voluntary.result, "allow");
  const taskInWhyNow = gateOpportunity(candidate({
    title: candidateWithTask.title,
    entryPoint: { whyNow: "孩子想看转弯；家长要求孩子每天做三页速度练习。", startupCost: "low" },
  }), context);
  assert.ok(taskInWhyNow.reasons.includes("daily-task-pressure"));
});

test("Diversity returns one option per ecosystem and always includes Nothing", () => {
  const options = buildOptionSet(
    [
      candidate({ opportunityId: "a", title: "试纸板弯道", ecosystem: "making", score: 0.9 }),
      candidate({ opportunityId: "b", title: "试纸板弯道", ecosystem: "making", score: 0.8 }),
      candidate({ opportunityId: "c", title: "家里聊真实车", ecosystem: "family-life", score: 0.7 })
    ],
    context
  );
  assert.deepEqual(options.selected.map((item) => item.candidate.opportunityId), ["a", "c"]);
  assert.equal(options.nothing.type, "nothing");
});

test("Diversity gives feasible sources turns instead of treating source scores as a quality ranking", () => {
  const ai = ["making", "media", "reading", "sport", "nature"].map((ecosystem, index) =>
    candidate({
      opportunityId: `ai-${index}`,
      ecosystem,
      title: `合成入口-${ecosystem}`,
      source: { kind: "byok-llm", publisher: "synthetic" },
      score: 0.99 - index * 0.01,
    }),
  );
  const pack = candidate({
    opportunityId: "pack-family",
    title: "家里比较弯道",
    ecosystem: "family-life",
    source: { kind: "pack", publisher: "synthetic" },
    score: 0.2,
  });
  const world = candidate({
    opportunityId: "world-event",
    title: "查看公开赛车信息",
    ecosystem: "world-event",
    source: { kind: "world-brief", publisher: "synthetic" },
    score: 0.1,
  });
  const options = buildOptionSet([...ai, pack, world], context);
  assert.deepEqual(
    options.selected.map((item) => item.candidate.opportunityId),
    ["ai-0", "pack-family", "world-event", "ai-1", "ai-2"],
  );
  assert.equal(options.nothing.type, "nothing");
});

test("Diversity does not let caregiver-primary doors outnumber child and shared doors", () => {
  const adult = ["making", "media", "reading", "sport"].map((ecosystem, index) => candidate({
    opportunityId: `adult-${index}`,
    title: `合成家长入口${index}`,
    ecosystem,
    goalAlignment: { primary: "caregiver", secondary: ["child"] },
    source: { kind: "byok-llm", publisher: "synthetic" },
    score: 0.99 - index * 0.01,
  }));
  const child = candidate({
    opportunityId: "child-nature", title: "合成自然入口", ecosystem: "nature", score: 0.2,
    source: { kind: "pack", publisher: "synthetic" },
  });
  const shared = candidate({
    opportunityId: "shared-family", title: "合成家庭入口", ecosystem: "family-life", score: 0.1,
    goalAlignment: { primary: "shared", secondary: [] },
    source: { kind: "pack", publisher: "synthetic" },
  });
  const result = buildOptionSet([...adult, child, shared], context);
  const visible = result.selected.map(({ candidate: item }) => item);
  assert.ok(visible.some((item) => item.opportunityId === "child-nature"));
  assert.ok(visible.some((item) => item.opportunityId === "shared-family"));
  assert.ok(visible.filter((item) => item.goalAlignment.primary === "caregiver").length <=
    visible.filter((item) => item.goalAlignment.primary !== "caregiver").length);
  assert.equal(result.nothing.type, "nothing");

  const adultOnly = buildOptionSet(adult, context);
  assert.equal(adultOnly.selected.length, 0);
  assert.ok(adultOnly.evaluated.every(({ decision }) => decision.result === "allow"));
  assert.equal(adultOnly.nothing.type, "nothing");
});

test("Diversity suppresses a relabeled duplicate while keeping a different route", () => {
  const options = buildOptionSet([
    candidate({ opportunityId: "first", title: "一起看真实弯道", ecosystem: "media" }),
    candidate({ opportunityId: "relabeled", title: "一起看真实弯道！", ecosystem: "making" }),
    candidate({ opportunityId: "different", title: "用纸板试一段弯道", ecosystem: "making" }),
  ], context);
  assert.deepEqual(options.selected.map((item) => item.candidate.opportunityId), ["first", "different"]);
  assert.equal(options.nothing.type, "nothing");
});

test("Diversity retains identically named public items from distinct original URLs", () => {
  const options = buildOptionSet([
    candidate({ opportunityId: "venue-a", title: "周末开放日", ecosystem: "place", source: { kind: "pack", publisher: "demo", url: "https://example.org/venue-a" } }),
    candidate({ opportunityId: "venue-b", title: "周末开放日！", ecosystem: "world-event", source: { kind: "pack", publisher: "demo", url: "https://example.org/venue-b" } }),
  ], context);
  assert.deepEqual(options.selected.map((item) => item.candidate.opportunityId), ["venue-a", "venue-b"]);
});
