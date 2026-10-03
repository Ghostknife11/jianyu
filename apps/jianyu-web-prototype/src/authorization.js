const HOUSEHOLD_ADMIN_ROLES = new Set(["caregiver", "guardian"]);
const SELF_ACTIONS = new Set([
  "interest.record",
  "evidence.correct",
  "opportunity.choose",
  "opportunity.veto",
  "feedback.record"
]);

export function authorizeFamilyAction(state, actorId, action, subjectId) {
  const actor = state.members.find((member) => member.id === actorId);
  if (!actor) return { allowed: false, reason: "unknown-actor" };
  if (HOUSEHOLD_ADMIN_ROLES.has(actor.role)) return { allowed: true, reason: "household-admin" };
  if (actor.role === "observer" && action === "interest.record") {
    return { allowed: true, reason: "observer-can-record-authored-evidence" };
  }
  if (actor.role === "child" && SELF_ACTIONS.has(action) && actor.subjectId === subjectId) {
    return { allowed: true, reason: "child-self-action" };
  }
  return { allowed: false, reason: "role-not-authorized" };
}

export function assertFamilyAction(state, actorId, action, subjectId) {
  const decision = authorizeFamilyAction(state, actorId, action, subjectId);
  if (!decision.allowed) throw new Error("当前记录者没有执行这个操作的权限");
  return decision;
}
