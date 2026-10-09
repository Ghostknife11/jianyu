// Who may record what. These are pure functions over the family state, ported
// from the reference prototype (ADR 0002 forbids the Core from depending on it,
// and this client only borrows the interaction rules).
//
// The decision is about the record's signature, never about identity: on a
// shared device a signature is a statement of who is holding it now, and the UI
// must say so rather than implying that a tap authenticates anyone.

const HOUSEHOLD_ADMIN_ROLES = new Set(["caregiver", "guardian"]);

// Actions a young person may take about themselves, and only themselves.
const SELF_ACTIONS = new Set([
  "interest.record",
  "opportunity.choose",
  "opportunity.veto",
  "feedback.record"
]);

export class AuthorizationError extends Error {
  constructor(message) {
    super(message);
    this.name = "AuthorizationError";
  }
}

export function authorizeFamilyAction(state, actorId, action, subjectId) {
  const actor = state.members.find((member) => member.id === actorId);
  if (!actor) return { allowed: false, reason: "unknown-actor" };
  if (HOUSEHOLD_ADMIN_ROLES.has(actor.role)) return { allowed: true, reason: "household-admin" };
  // An observer may add what they saw, under their own name, and nothing else.
  if (actor.role === "observer" && action === "interest.record") {
    return { allowed: true, reason: "observer-can-record-authored-evidence" };
  }
  if (actor.role === "child" && SELF_ACTIONS.has(action) && actor.subjectId === subjectId) {
    return { allowed: true, reason: "child-self-action" };
  }
  return { allowed: false, reason: "role-not-authorized" };
}

/** Throws a family-facing sentence instead of returning a code. */
export function assertFamilyAction(state, actorId, action, subjectId) {
  const decision = authorizeFamilyAction(state, actorId, action, subjectId);
  if (!decision.allowed) {
    throw new AuthorizationError("当前记录者不能替这个人做这个决定，请换一位记录者");
  }
  return decision;
}
