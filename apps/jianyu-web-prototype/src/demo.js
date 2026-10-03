import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { runOpportunityFlow } from "../../../packages/foe-core/src/index.js";
import { createDemoWorldBriefProvider } from "../../../examples/world-brief/weekend.demo.js";
import { issueProviderCapability } from "../../../packages/provider-sdk/src/index.js";

const packPath = fileURLToPath(new URL("../../../examples/packs/motorsport.demo.json", import.meta.url));
const pack = JSON.parse(await readFile(packPath, "utf8"));

const request = {
  purpose: "find-weekend-opportunities",
  currentInterest: "最近主动研究赛车调校",
  ageBand: "10-12",
  goals: [
    { owner: "child", value: "想把赛车调得更快", source: "child-stated" },
    { owner: "caregiver", value: "以后遇到速度和力时不陌生", source: "caregiver-stated" },
    { owner: "shared", value: "周末一起做点有意思的", source: "joint" }
  ],
  constraints: {
    timeMinutes: 120,
    costBand: "low",
    caregiverEnergy: "low",
    travelMinutesMax: 30
  },
  recentEvidence: [
    {
      kind: "child-choice",
      expression: "最近主动研究赛车调校",
      topicHints: ["motorsport", "optimization"]
    }
  ],
  schoolWindow: [{ topic: "speed-and-time", startsInWeeks: 3, required: false }],
  worldQuery: {
    region: "demo-city",
    timeWindow: "this-weekend",
    categories: ["sport", "technology"]
  },
  expiresAt: "2099-09-13T00:00:00+08:00"
};

const result = await runOpportunityFlow({
  request,
  pack,
  providers: { worldBrief: createDemoWorldBriefProvider() },
  approvedWorldQuery: request.worldQuery, // Bundled fictional in-memory provider; no network request.
  providerCapabilities: {
    worldBrief: issueProviderCapability({
      kind: "world-brief",
      purpose: request.purpose,
      dataCategories: ["public-world-query"]
    })
  }
});

console.log("见隅 / FOE v0.1 合成演示\n");
for (const [index, item] of result.selected.entries()) {
  const { candidate, decision } = item;
  console.log(`${index + 1}. ${candidate.title}`);
  console.log(`   生态：${candidate.ecosystem}`);
  console.log(`   为什么现在：${candidate.entryPoint.whyNow}`);
  console.log(`   主要目标：${candidate.goalAlignment.primary}`);
  console.log(`   Gate：${decision.result} (${decision.reasons.join(", ")})`);
  if (decision.warnings.length) console.log(`   提醒：${decision.warnings.join(", ")}`);
  console.log();
}

console.log(`○ ${result.nothing.title}`);
console.log(`  ${result.nothing.explanation}\n`);

const rejected = result.evaluated.filter(({ decision }) => decision.result === "reject");
console.log("被 Gate 拦下的候选：");
for (const { candidate, decision } of rejected) {
  console.log(`- ${candidate.title}: ${decision.reasons.join(", ")}`);
}
