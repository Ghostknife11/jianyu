import { currentInterestHasNonRefusalClue, currentInterestMentionsTerm, currentInterestRefusesTerm, opportunitiesFromPack } from "../../pack-sdk/src/index.js";
import { assertProvider, assertProviderCapability, assertPublicWorldQuery } from "../../provider-sdk/src/index.js";
import { buildOptionSet } from "../../foe-opportunity/src/index.js";

const LLM_CONTEXT_CATEGORIES = new Set([
  "current-interest", "age-band", "declared-goals", "practical-constraints",
  "school-window", "recent-evidence-summaries"
]);

function boundedText(value, maximum) {
  return typeof value === "string" ? value.trim().slice(0, maximum) : "";
}

function projectConstraints(source) {
  const constraints = {};
  for (const key of ["timeMinutes", "travelMinutesMax"]) {
    if (source?.[key] === undefined) continue;
    if (!Number.isInteger(source[key]) || source[key] < 0 || source[key] > 1440) {
      throw new TypeError(`AI context ${key} must be a bounded minute count`);
    }
    constraints[key] = source[key];
  }
  for (const [key, allowed] of [
    ["costBand", ["free-existing", "free", "low", "medium", "high"]],
    ["caregiverEnergy", ["none", "low", "medium", "high"]]
  ]) {
    if (source?.[key] === undefined) continue;
    if (!allowed.includes(source[key])) throw new TypeError(`AI context ${key} is invalid`);
    constraints[key] = source[key];
  }
  return constraints;
}

function freezeProjection(value) {
  if (Array.isArray(value)) return Object.freeze(value.map(freezeProjection));
  if (value && typeof value === "object") {
    return Object.freeze(Object.fromEntries(Object.entries(value).map(([key, child]) => [key, freezeProjection(child)])));
  }
  return value;
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function publicCandidateMatches(candidate, currentInterest) {
  const topics = Array.isArray(candidate?.topics) ? candidate.topics : [];
  if (topics.length === 0 || topics.length > 20 ||
      topics.some((term) => typeof term !== "string" || term.trim().length < 2 || term.trim().length > 80)) return false;
  const aliases = candidate?.matchTerms;
  if (aliases !== undefined && (!Array.isArray(aliases) || aliases.length > 20 ||
      aliases.some((term) => typeof term !== "string" || term.trim().length < 2 || term.trim().length > 80))) return false;
  const terms = [...topics, ...(aliases ?? [])];
  return topics.length > 0 &&
    terms.every((term) => !currentInterestRefusesTerm(currentInterest, term)) &&
    terms.some((term) => currentInterestMentionsTerm(currentInterest, term));
}

// A Provider's own payload cannot impersonate a different source in Gate, diversity, or UI.
function bindSourceKind(candidate, kind) {
  return { ...candidate, source: { ...candidate.source, kind } };
}

/** Constructs the exact client-reviewable AI payload from approved categories only. */
export function createTaskContext(request, approvedCategories = []) {
  if (!Array.isArray(approvedCategories) || approvedCategories.some((category) => !LLM_CONTEXT_CATEGORIES.has(category))) {
    throw new TypeError("AI context requires known approved categories");
  }
  const approved = new Set(approvedCategories);
  if (!approved.has("current-interest")) throw new Error("AI context requires current-interest approval");
  const interest = boundedText(request.currentInterest, 800);
  if (!interest) throw new Error("AI context requires a current interest");
  const context = { purpose: boundedText(request.purpose, 100), currentInterest: interest };
  if (approved.has("age-band")) context.ageBand = boundedText(request.ageBand, 20);
  if (approved.has("declared-goals")) {
    context.goals = (Array.isArray(request.goals) ? request.goals : []).slice(0, 3)
      .map((goal) => ({ owner: boundedText(goal?.owner, 20), value: boundedText(goal?.value, 300) }))
      .filter((goal) => ["child", "caregiver", "shared"].includes(goal.owner) && goal.value);
  }
  if (approved.has("practical-constraints")) {
    context.constraints = projectConstraints(request.constraints);
  }
  if (approved.has("school-window")) {
    context.schoolWindow = (Array.isArray(request.schoolWindow) ? request.schoolWindow : []).slice(0, 3)
      .map((item) => ({
        topic: boundedText(item?.topic, 240),
        startsInWeeks: Number.isInteger(item?.startsInWeeks) && item.startsInWeeks >= 0 && item.startsInWeeks <= 520
          ? item.startsInWeeks : null
      }))
      .filter((item) => item.topic);
  }
  if (approved.has("recent-evidence-summaries")) {
    context.recentEvidence = (Array.isArray(request.recentEvidence) ? request.recentEvidence : []).slice(0, 5)
      .map((item) => ({
        kind: boundedText(item?.kind, 60),
        topicHints: (Array.isArray(item?.topicHints) ? item.topicHints : []).slice(0, 5)
          .map((hint) => boundedText(hint, 80)).filter(Boolean)
      }));
  }
  return freezeProjection(context);
}

export async function runOpportunityFlow({ request, pack, providers = {}, providerCapabilities = {}, approvedWorldQuery, approvedLlmCategories, approvedLlmContext, options = {} }) {
  const candidates = [];
  const provenance = [];

  if (pack) {
    const packCandidates = opportunitiesFromPack(pack, { currentInterest: request.currentInterest });
    candidates.push(...packCandidates.map((candidate) => bindSourceKind(candidate, "pack")));
    provenance.push({ source: "pack", count: packCandidates.length });
  }

  if (providers.worldBrief) {
    assertProvider(providers.worldBrief, "world-brief");
    assertProviderCapability(providerCapabilities.worldBrief, {
      kind: "world-brief",
      purpose: request.purpose,
      requiredCategories: ["public-world-query"]
    });
    const publicQuery = assertPublicWorldQuery(request.worldQuery ?? {});
    const approvedQuery = approvedWorldQuery == null ? null : assertPublicWorldQuery(approvedWorldQuery);
    if (approvedQuery == null || canonicalJson(approvedQuery) !== canonicalJson(publicQuery)) {
      provenance.push({ source: "world-brief", count: 0, reason: "outside-approved-scope" });
    } else if (!currentInterestHasNonRefusalClue(request.currentInterest)) {
      provenance.push({ source: "world-brief", count: 0, reason: "no-current-child-pull" });
    } else {
      const publicCandidates = await providers.worldBrief.getBrief(publicQuery);
      if (!Array.isArray(publicCandidates)) throw new TypeError("WorldBriefProvider must return public candidates");
      const worldCandidates = publicCandidates.filter((candidate) => publicCandidateMatches(candidate, request.currentInterest));
      candidates.push(...worldCandidates.map((candidate) => bindSourceKind(candidate, "world-brief")));
      provenance.push({ source: "world-brief", count: worldCandidates.length });
    }
  }

  if (providers.search) {
    assertProvider(providers.search, "search");
    assertProviderCapability(providerCapabilities.search, {
      kind: "search",
      purpose: request.purpose,
      requiredCategories: ["public-world-query"]
    });
    const publicQuery = assertPublicWorldQuery(request.worldQuery ?? {});
    const approvedQuery = approvedWorldQuery == null ? null : assertPublicWorldQuery(approvedWorldQuery);
    if (approvedQuery == null || canonicalJson(approvedQuery) !== canonicalJson(publicQuery)) {
      provenance.push({ source: "search", count: 0, reason: "outside-approved-scope" });
    } else if (!currentInterestHasNonRefusalClue(request.currentInterest)) {
      provenance.push({ source: "search", count: 0, reason: "no-current-child-pull" });
    } else {
      const publicCandidates = await providers.search.searchPublic(publicQuery);
      if (!Array.isArray(publicCandidates)) throw new TypeError("SearchProvider must return public candidates");
      const searchCandidates = publicCandidates.filter((candidate) => publicCandidateMatches(candidate, request.currentInterest));
      candidates.push(...searchCandidates.map((candidate) => bindSourceKind(candidate, "search")));
      provenance.push({ source: "search", count: searchCandidates.length });
    }
  }

  if (providers.llm) {
    assertProvider(providers.llm, "llm");
    assertProviderCapability(providerCapabilities.llm, {
      kind: "llm",
      purpose: request.purpose,
      requiredCategories: ["minimized-task-context"]
    });
    if (!currentInterestHasNonRefusalClue(request.currentInterest)) {
      provenance.push({ source: "llm", count: 0, reason: "no-current-child-pull" });
    } else if (!Array.isArray(approvedLlmCategories) || !approvedLlmCategories.includes("current-interest")) {
      provenance.push({ source: "llm", count: 0, reason: "outside-approved-scope" });
    } else {
      const taskContext = createTaskContext(request, approvedLlmCategories);
      if (approvedLlmContext == null || canonicalJson(taskContext) !== canonicalJson(approvedLlmContext)) {
        provenance.push({ source: "llm", count: 0, reason: "outside-approved-scope" });
      } else {
        assertProviderCapability(providerCapabilities.llm, {
          kind: "llm",
          purpose: request.purpose,
          requiredCategories: approvedLlmCategories
        });
        const llmCandidates = await providers.llm.generateCandidates(taskContext);
        if (!Array.isArray(llmCandidates)) throw new TypeError("LLMProvider must return candidates");
        candidates.push(...llmCandidates.map((candidate) => bindSourceKind(candidate, "llm")));
        provenance.push({ source: "llm", count: llmCandidates.length });
      }
    }
  }

  const optionSet = buildOptionSet(candidates, request, options);
  return Object.freeze({
    purpose: request.purpose,
    provenance,
    ...optionSet
  });
}
