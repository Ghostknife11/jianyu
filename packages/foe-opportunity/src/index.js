import { assertOpportunity, makeNothingOption } from "../../foe-schema/src/index.js";
import { compareBand, defaultPolicy } from "../../policy-sdk/src/index.js";
import { isObviousDailyTask } from "./reference-patterns.js";

function hasBlockedRisk(candidate, policy) {
  return (candidate.risks ?? []).some((risk) => policy.blockedRiskLevels.includes(risk.level));
}

export function gateOpportunity(candidate, context, policy = defaultPolicy, now = new Date()) {
  assertOpportunity(candidate);
  const reasons = [];
  const warnings = [];
  let result = "allow";

  const requirements = candidate.requirements;
  const constraints = context.constraints ?? {};

  if (hasBlockedRisk(candidate, policy)) {
    result = "reject";
    reasons.push("blocked-safety-risk");
  }
  if (candidate.type === "nothing" || candidate.ecosystem === "nothing") {
    result = "reject";
    reasons.push("reserved-nothing-option");
  }
  if (constraints.timeMinutes <= 0) {
    result = "reject";
    reasons.push("no-available-time");
  }
  if (requirements.timeMinutes <= 0) {
    result = "reject";
    reasons.push("invalid-duration");
  }
  if (requirements.timeMinutes > constraints.timeMinutes) {
    result = "reject";
    reasons.push("exceeds-time");
  }
  if (requirements.travelMinutes > constraints.travelMinutesMax) {
    result = "reject";
    reasons.push("exceeds-travel");
  }
  if (compareBand(requirements.costBand, constraints.costBand, policy.costOrder) > 0) {
    result = "reject";
    reasons.push("exceeds-cost");
  }
  if (compareBand(requirements.caregiverEnergy, constraints.caregiverEnergy, policy.energyOrder) > 0) {
    result = "reject";
    reasons.push("exceeds-caregiver-energy");
  }
  if (candidate.childVeto === true) {
    result = "reject";
    reasons.push("child-veto");
  }
  if ([candidate.title, candidate.explanation, candidate.entryPoint.whyNow].some(isObviousDailyTask)) {
    result = "reject";
    reasons.push("daily-task-pressure");
  }
  if (
    candidate.goalAlignment.primary === "caregiver" &&
    candidate.childPull !== true &&
    !policy.allowCaregiverLedWithoutChildPull
  ) {
    result = "reject";
    reasons.push("caregiver-goal-without-child-pull");
  }
  if (
    candidate.childPull !== true &&
    (candidate.goalAlignment.primary !== "caregiver" || !policy.allowCaregiverLedWithoutChildPull)
  ) {
    result = "reject";
    reasons.push("insufficient-child-pull");
  }
  if (candidate.freshUntil && new Date(candidate.freshUntil) < now && result !== "reject") {
    result = policy.staleWorldResult;
    reasons.push("stale-world-information");
  }
  if (candidate.verification !== "verified") {
    warnings.push(`verification:${candidate.verification}`);
  }
  if (candidate.sponsorship) {
    warnings.push("sponsored");
  }

  if (result === "allow") {
    reasons.push("fits-declared-constraints");
    if (candidate.goalAlignment.primary === "child") reasons.push("child-led");
    if (candidate.entryPoint.startupCost === "low") reasons.push("low-startup-cost");
  }

  return Object.freeze({
    opportunityId: candidate.opportunityId,
    result,
    reasons,
    warnings,
    policyRef: { id: policy.id, version: policy.version }
  });
}

export function selectDiverse(evaluated, limit = 5) {
  const selected = [];
  const ecosystems = new Set();
  const entrySignatures = new Set();
  const deferredCaregiver = [];
  let childOrSharedCount = 0;
  let caregiverCount = 0;
  const eligible = evaluated
    .filter(({ decision }) => decision.result === "allow" || decision.result === "needs-verification");
  const sourceTurns = new Map();
  for (const item of eligible) {
    const kind = item.candidate.source.kind;
    if (!sourceTurns.has(kind)) sourceTurns.set(kind, []);
    sourceTurns.get(kind).push(item);
  }
  const turns = [...sourceTurns.values()].map((items) => ({ items, index: 0 }));
  const selectIfDistinct = (item) => {
    if (ecosystems.has(item.candidate.ecosystem)) return false;
    const title = item.candidate.title.normalize("NFKC").toLowerCase().replace(/[\p{P}\p{Z}\s]+/gu, "");
    // Only clear title re-labels are suppressed; different source URLs remain distinct.
    const signature = title.length >= 4 ? JSON.stringify([title, item.candidate.source.url?.trim() ?? ""]) : null;
    if (signature != null && entrySignatures.has(signature)) return false;
    ecosystems.add(item.candidate.ecosystem);
    if (signature != null) entrySignatures.add(signature);
    selected.push(item);
    return true;
  };
  const admitDeferredCaregiver = () => {
    while (selected.length < limit && caregiverCount < childOrSharedCount && deferredCaregiver.length > 0) {
      if (selectIfDistinct(deferredCaregiver.shift())) caregiverCount++;
    }
  };
  while (selected.length < limit && turns.some((turn) => turn.index < turn.items.length)) {
    for (const turn of turns) {
      if (selected.length >= limit) break;
      while (turn.index < turn.items.length) {
        const item = turn.items[turn.index++];
        if (item.candidate.goalAlignment.primary === "caregiver") {
          deferredCaregiver.push(item);
          break;
        }
        if (selectIfDistinct(item)) {
          childOrSharedCount++;
          admitDeferredCaregiver();
          break;
        }
      }
    }
  }
  admitDeferredCaregiver();

  return selected;
}

export function buildOptionSet(candidates, context, { policy = defaultPolicy, limit = 5, now } = {}) {
  const evaluated = candidates.map((candidate) => ({
    candidate,
    decision: gateOpportunity(candidate, context, policy, now)
  }));
  const selected = selectDiverse(evaluated, limit);
  const nothingReason = selected.length === 0 ? "no-natural-entry-point" : "family-choice";

  return Object.freeze({
    selected,
    nothing: makeNothingOption(nothingReason),
    evaluated
  });
}
