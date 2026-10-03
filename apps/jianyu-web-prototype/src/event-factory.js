import { assertEvent } from "../../../packages/foe-schema/src/index.js";

export function createFamilyEvent(state, eventType, payload, { authorId, subjectId, visibility = "guardians" } = {}) {
  const author = state.members.find((member) => member.id === authorId)
    ?? state.members.find((member) => member.role === "caregiver")
    ?? state.members[0];
  const timestamp = new Date().toISOString();
  return assertEvent({
    schema: "org.foe.event/v1",
    eventId: crypto.randomUUID(),
    eventType,
    eventVersion: 1,
    householdId: state.household.id,
    authorId: author.id,
    actorRole: author.role,
    subjectId,
    deviceId: "jianyu-browser-local",
    occurredAt: timestamp,
    recordedAt: timestamp,
    visibility,
    payload
  });
}
