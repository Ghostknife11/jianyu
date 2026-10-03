import { runOpportunityFlow } from "../../../packages/foe-core/src/index.js";
import { createDemoWorldBriefProvider } from "../../../examples/world-brief/weekend.demo.js";
import { issueProviderCapability } from "../../../packages/provider-sdk/src/index.js";
import { discoverLocalOpportunities, looksLikeMotorsport } from "./local-discovery.js";

let motorsportPack;

async function loadMotorsportPack() {
  if (motorsportPack) return motorsportPack;
  const response = await fetch("/examples/packs/motorsport.demo.json");
  if (!response.ok) throw new Error("无法读取本地体验包");
  motorsportPack = await response.json();
  return motorsportPack;
}

export async function discoverForFamily({ child, expression, constraints, schoolWindow, goals = {} }) {
  const localCandidates = discoverLocalOpportunities(expression);
  const providers = {};
  const providerCapabilities = {};

  if (looksLikeMotorsport(expression)) {
    const specificPack = await loadMotorsportPack();
    localCandidates.unshift(...specificPack.opportunities);
    providers.worldBrief = createDemoWorldBriefProvider();
    providerCapabilities.worldBrief = issueProviderCapability({
      kind: "world-brief",
      purpose: "find-family-opportunities",
      dataCategories: ["public-world-query"]
    });
  }

  const pack = {
    schema: "org.foe.pack/v1",
    id: "org.jianyu.session.local",
    version: "0.1.0",
    publisher: "Jianyu local discovery",
    license: "Apache-2.0",
    opportunities: localCandidates
  };

  const request = {
    purpose: "find-family-opportunities",
    currentInterest: expression,
    ageBand: ageBandFor(child.birthYear),
    goals: [
      { owner: "child", value: expression, source: "current-evidence" },
      ...(goals.caregiver ? [{ owner: "caregiver", value: goals.caregiver, source: "caregiver-stated" }] : []),
      ...(goals.shared ? [{ owner: "shared", value: goals.shared, source: "joint-stated" }] : [])
    ],
    constraints,
    recentEvidence: [{ kind: "child-current-pull", expression, topicHints: [] }],
    schoolWindow: schoolWindow ? [{ topic: schoolWindow, required: false }] : [],
    worldQuery: {
      region: "demo-city",
      timeWindow: "this-weekend",
      categories: ["sport", "technology"]
    },
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString()
  };

  // This World provider is a bundled fictional in-memory demo; a network provider needs a real per-call review.
  return runOpportunityFlow({
    request,
    pack,
    providers,
    providerCapabilities,
    approvedWorldQuery: providers.worldBrief ? request.worldQuery : undefined,
    options: { limit: 5 }
  });
}

export function ageFor(birthYear) {
  return Math.max(0, new Date().getFullYear() - Number(birthYear));
}

export function ageBandFor(birthYear) {
  const age = ageFor(birthYear);
  if (age <= 6) return "4-6";
  if (age <= 9) return "7-9";
  if (age <= 12) return "10-12";
  if (age <= 15) return "13-15";
  return "16+";
}

export function lifecycleLabel(birthYear) {
  const labels = { "4-6": "共玩", "7-9": "陪伴", "10-12": "共选", "13-15": "放权", "16+": "Graduation" };
  return labels[ageBandFor(birthYear)];
}
