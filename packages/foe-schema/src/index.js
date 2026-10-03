const GOAL_OWNERS = new Set(["child", "caregiver", "shared"]);
const VERIFICATION_STATES = new Set(["verified", "likely", "idea"]);
const VISIBILITY = new Set([
  "private",
  "selected-members",
  "guardians",
  "family",
  "recommendation-only"
]);

function requireString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError(`${field} must be a non-empty string`);
  }
}

function requirePositiveVersion(value, field) {
  if (!Number.isInteger(value) || value < 1) {
    throw new TypeError(`${field} must be a positive integer`);
  }
}

export function assertEvent(event) {
  if (!event || typeof event !== "object" || Array.isArray(event)) {
    throw new TypeError("event must be an object");
  }

  requireString(event.schema, "event.schema");
  requireString(event.eventId, "event.eventId");
  requireString(event.eventType, "event.eventType");
  requirePositiveVersion(event.eventVersion, "event.eventVersion");
  requireString(event.householdId, "event.householdId");
  requireString(event.authorId, "event.authorId");
  requireString(event.actorRole, "event.actorRole");
  requireString(event.deviceId, "event.deviceId");
  requireString(event.occurredAt, "event.occurredAt");
  requireString(event.recordedAt, "event.recordedAt");

  if (!VISIBILITY.has(event.visibility)) {
    throw new TypeError("event.visibility is unsupported");
  }

  if (!event.payload || typeof event.payload !== "object" || Array.isArray(event.payload)) {
    throw new TypeError("event.payload must be an object");
  }

  return event;
}

export function assertOpportunity(opportunity) {
  if (!opportunity || typeof opportunity !== "object" || Array.isArray(opportunity)) {
    throw new TypeError("opportunity must be an object");
  }

  requireString(opportunity.schema, "opportunity.schema");
  requireString(opportunity.opportunityId, "opportunity.opportunityId");
  requireString(opportunity.title, "opportunity.title");
  requireString(opportunity.ecosystem, "opportunity.ecosystem");

  if (!opportunity.entryPoint || typeof opportunity.entryPoint !== "object") {
    throw new TypeError("opportunity.entryPoint must be an object");
  }
  requireString(opportunity.entryPoint.whyNow, "opportunity.entryPoint.whyNow");

  if (!GOAL_OWNERS.has(opportunity.goalAlignment?.primary)) {
    throw new TypeError("opportunity.goalAlignment.primary is unsupported");
  }
  if (!opportunity.requirements || typeof opportunity.requirements !== "object") {
    throw new TypeError("opportunity.requirements must be an object");
  }
  if (!opportunity.source || typeof opportunity.source !== "object") {
    throw new TypeError("opportunity.source must be an object");
  }
  if (!VERIFICATION_STATES.has(opportunity.verification)) {
    throw new TypeError("opportunity.verification is unsupported");
  }

  return opportunity;
}

export function makeNothingOption(reason = "family-choice") {
  return Object.freeze({
    schema: "org.foe.opportunity/v1",
    opportunityId: `nothing:${reason}`,
    type: "nothing",
    title: "什么都不做",
    explanation: "今天不需要把兴趣变成安排。保留自由时间也是有效选择。",
    reason
  });
}

export const schemaConstants = Object.freeze({
  goalOwners: [...GOAL_OWNERS],
  verificationStates: [...VERIFICATION_STATES],
  visibility: [...VISIBILITY]
});
