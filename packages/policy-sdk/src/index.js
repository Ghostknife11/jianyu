export const defaultPolicy = Object.freeze({
  id: "org.jianyu.opportunity-default",
  version: "0.1.4",
  blockedRiskLevels: ["high", "critical"],
  costOrder: ["free-existing", "free", "low", "medium", "high"],
  energyOrder: ["none", "low", "medium", "high"],
  allowCaregiverLedWithoutChildPull: false,
  staleWorldResult: "needs-verification"
});

export function compareBand(actual, maximum, order) {
  if (actual == null || maximum == null) return 0;
  const actualIndex = order.indexOf(actual);
  const maximumIndex = order.indexOf(maximum);
  if (actualIndex < 0 || maximumIndex < 0) return 0;
  return actualIndex - maximumIndex;
}

export function resolveLifecycleStage(age) {
  if (!Number.isInteger(age) || age < 4) {
    throw new RangeError("FOE lifecycle begins at age 4");
  }
  if (age <= 6) return "co-play";
  if (age <= 9) return "accompany";
  if (age <= 12) return "co-select";
  if (age <= 15) return "hand-over";
  return "graduation";
}

export function lifecycleAuthority(stage) {
  const rules = {
    "co-play": { primary: "caregiver", childCanVeto: true, privateChildContext: false },
    accompany: { primary: "caregiver", childCanVeto: true, privateChildContext: false },
    "co-select": { primary: "joint", childCanVeto: true, privateChildContext: true },
    "hand-over": { primary: "child", childCanVeto: true, privateChildContext: true },
    graduation: { primary: "self", childCanVeto: true, privateChildContext: true, stopCaregiverModeling: true }
  };
  if (!rules[stage]) throw new RangeError(`unknown lifecycle stage: ${stage}`);
  return Object.freeze({ ...rules[stage] });
}
