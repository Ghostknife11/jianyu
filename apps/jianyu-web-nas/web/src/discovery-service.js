// Discovery for the NAS web app.
//
// This module adapts the family's words into the public FOE flow and nothing
// more: the Gate, diversity, Nothing, and the Context Firewall all come from
// `packages/*`. It answers two questions separately, on purpose:
//
//   - `planDiscovery` says exactly what would be sent where, so the family can
//     confirm a network call before it happens;
//   - `runDiscovery` performs it.
//
// The offline demonstration is always available and never touches the network.
// AI and World Brief are opt-in per call, and each one only ever receives the
// minimized payload this module builds in the browser.

import { createTaskContext, runOpportunityFlow } from "../../../../packages/foe-core/src/index.js";
import { issueProviderCapability } from "../../../../packages/provider-sdk/src/index.js";
import { childLifecycle } from "./family-state.js";
import { createOfflineDemoPack } from "./offline-demo-pack.js";

export const DISCOVERY_PURPOSE = "find-family-opportunities";

// The categories a family may approve for an AI call. `current-interest` is
// required: without it there is nothing to start from.
export const AI_CONTEXT_CATEGORIES = Object.freeze([
  "current-interest",
  "age-band",
  "declared-goals",
  "practical-constraints",
  "school-window",
  "recent-evidence-summaries"
]);

const DEFAULT_CONSTRAINTS = Object.freeze({
  // Nothing declared means nothing is filtered out for an undeclared limit;
  // the family sees a wider set and narrows it themselves.
  timeMinutes: 1440,
  travelMinutesMax: 1440,
  costBand: "high",
  caregiverEnergy: "high"
});

const AGE_BANDS = [
  { maximum: 6, band: "4-6" },
  { maximum: 9, band: "7-9" },
  { maximum: 12, band: "10-12" },
  { maximum: 15, band: "13-15" },
  { maximum: Number.POSITIVE_INFINITY, band: "16+" }
];

export class DiscoveryError extends Error {
  constructor(message) {
    super(message);
    this.name = "DiscoveryError";
  }
}

/** The lifecycle band for one child, or null below age 4. */
export function ageBandFor(child, today = new Date()) {
  const lifecycle = childLifecycle(child, today);
  if (!lifecycle.stage) return null;
  return AGE_BANDS.find((entry) => lifecycle.age <= entry.maximum)?.band ?? "16+";
}

function boundedText(value, maximum) {
  if (typeof value !== "string") return "";
  return value.trim().slice(0, maximum);
}

/**
 * Builds the request the flow runs on. Every field is bounded here so a long
 * paste or a stray value cannot widen what any provider receives.
 */
export function buildDiscoveryRequest({
  child,
  interest,
  constraints,
  goals = [],
  recentEvidence = [],
  schoolWindow = [],
  worldQuery
}) {
  const currentInterest = boundedText(interest, 800);
  if (currentInterest === "") throw new DiscoveryError("请先写一句孩子当下在意的内容");
  const ageBand = ageBandFor(child);
  if (ageBand === null) throw new DiscoveryError("未满 4 岁的孩子暂不提供发现，记录本身会一直保留");
  const declared = { ...DEFAULT_CONSTRAINTS };
  for (const key of Object.keys(DEFAULT_CONSTRAINTS)) {
    const value = constraints?.[key];
    if (value !== undefined && value !== null && value !== "") declared[key] = value;
  }
  return Object.freeze({
    purpose: DISCOVERY_PURPOSE,
    currentInterest,
    ageBand,
    goals: (Array.isArray(goals) ? goals : [])
      .map((goal) => ({ owner: boundedText(goal?.owner, 20), value: boundedText(goal?.value, 300) }))
      .filter((goal) => ["child", "caregiver", "shared"].includes(goal.owner) && goal.value !== "")
      .slice(0, 3),
    constraints: Object.freeze(declared),
    recentEvidence: (Array.isArray(recentEvidence) ? recentEvidence : []).slice(0, 5),
    schoolWindow: (Array.isArray(schoolWindow) ? schoolWindow : []).slice(0, 3),
    worldQuery: Object.freeze({
      region: boundedText(worldQuery?.region, 120),
      timeWindow: boundedText(worldQuery?.timeWindow, 80) || "next-14-days",
      language: boundedText(worldQuery?.language, 35) || "zh-CN",
      categories: Object.freeze((Array.isArray(worldQuery?.categories) ? worldQuery.categories : []).slice(0, 20))
    })
  });
}

/**
 * What a call would send, before anything is sent. The task context is the
 * exact object the AI provider receives; the world query is the exact object
 * the World Brief provider receives. Anything not listed here is not sent.
 */
export function planDiscovery({ request, aiCategories = AI_CONTEXT_CATEGORIES }) {
  const approved = aiCategories.filter((category) => AI_CONTEXT_CATEGORIES.includes(category));
  if (!approved.includes("current-interest")) approved.unshift("current-interest");
  return Object.freeze({
    purpose: request.purpose,
    currentInterest: request.currentInterest,
    aiCategories: Object.freeze(approved),
    taskContext: createTaskContext(request, approved),
    worldQuery: request.worldQuery
  });
}

/**
 * Runs the flow. `sources.offline` is always allowed; `sources.ai` and
 * `sources.world` are providers the caller built only after the family
 * confirmed the call, so no network request can happen without that step.
 */
export async function runDiscovery({ request, sources = {}, options = {} }) {
  if (!request || typeof request !== "object") throw new DiscoveryError("发现请求不完整");
  const approvedAiCategories = Array.isArray(sources.aiCategories) && sources.aiCategories.length > 0
    ? sources.aiCategories
    : AI_CONTEXT_CATEGORIES;
  const plan = planDiscovery({ request, aiCategories: approvedAiCategories });
  const providers = {};
  const providerCapabilities = {};

  if (sources.ai) {
    providers.llm = sources.ai;
    // The capability names both the minimized-context boundary and every
    // category the family approved, because the flow checks both.
    providerCapabilities.llm = issueProviderCapability({
      kind: "llm",
      purpose: DISCOVERY_PURPOSE,
      dataCategories: ["minimized-task-context", ...plan.aiCategories]
    });
  }
  if (sources.world) {
    providers.worldBrief = sources.world;
    providerCapabilities.worldBrief = issueProviderCapability({
      kind: "world-brief",
      purpose: DISCOVERY_PURPOSE,
      dataCategories: ["public-world-query"]
    });
  }

  return Object.freeze({
    plan,
    result: await runOpportunityFlow({
      request,
      pack: sources.offline === false ? null : createOfflineDemoPack(request.currentInterest),
      providers,
      providerCapabilities,
      // The world provider only runs on the query this module built, which is
      // exactly the one shown to the family before the call.
      approvedWorldQuery: sources.world ? request.worldQuery : undefined,
      approvedLlmCategories: sources.ai ? plan.aiCategories : undefined,
      approvedLlmContext: sources.ai ? plan.taskContext : undefined,
      options: { limit: options.limit ?? 5, now: options.now }
    })
  });
}
