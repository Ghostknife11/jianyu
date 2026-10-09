// The shared family footprint (家庭足迹) and the two actions a family takes on
// it: correcting what a record says, and deleting one saved choice.
//
// Three rules from the accepted ADRs are enforced here rather than in the
// renderer, because they are the parts a screen must not be able to get wrong:
//
//   ADR 0012 — the shared screen consumes a projection, never the raw
//   collections. An event is visible only when its visibility is `guardians` or
//   `family`; every other value, including one a future version adds, fails
//   closed. A choice is hidden when its own source event is restricted or when a
//   restricted event names it. The projection reports only *that* records are
//   hidden — never how many, whose, or what kind.
//
//   ADR 0012 — a correction appends a new event. The original is never
//   rewritten, so the record keeps saying who said what and when.
//
//   ADR 0026 — deleting a choice removes it together with every same-subject
//   event that refers to its `choiceId`, then writes one `CHOICE` tombstone and
//   one `EVENT` tombstone per removed event so an old copy cannot restore the
//   content. The audit event carries opaque IDs only.
//
//   ADR 0010 — a 16+ retention outcome is a `SUBJECT` or `SUBJECT_CONTENT`
//   tombstone, and it suppresses this person's records on every projection too.

import { appendEvent, childLifecycle, createFamilyEvent } from "./family-state.js";
import { AuthorizationError } from "./authorization.js";

const SHARED_VISIBLE = new Set(["guardians", "family"]);
const TOMBSTONE_SCHEMA = "org.foe.deletion-tombstone/v1";
const TOMBSTONE_REASON = "family-request";

export class TimelineError extends Error {
  constructor(message) {
    super(message);
    this.name = "TimelineError";
  }
}

/** True when an event may appear on a shared screen. Unknown values fail closed. */
export function isSharedVisible(event) {
  return SHARED_VISIBLE.has(event?.visibility);
}

function memberName(state, memberId) {
  const member = state.members.find((item) => item.id === memberId);
  if (!member) return "未知记录者";
  return member.displayName;
}

function childName(state, childId) {
  const child = state.children.find((item) => item.id === childId);
  return child ? child.displayName : null;
}

function newId() {
  return globalThis.crypto.randomUUID();
}

function now() {
  return new Date().toISOString();
}

/**
 * The shared-screen view of the family footprint. Tombstones are applied here as
 * well as in the merge, so a client that renders this projection directly can
 * never resurrect content another device already deleted.
 */
export function projectSharedTimeline(state) {
  const tombstonedChoices = new Set(
    (state.tombstones ?? [])
      .filter((item) => String(item.targetType).toUpperCase() === "CHOICE")
      .map((item) => item.targetId)
  );
  const tombstonedEvents = new Set(
    (state.tombstones ?? [])
      .filter((item) => String(item.targetType).toUpperCase() === "EVENT")
      .map((item) => item.targetId)
  );
  // ADR 0010: a 16+ retention outcome is a subject-level tombstone. It is applied
  // here as well as in the merge, so this screen can never resurrect the history
  // a person asked the household to drop. A subject-content tombstone that is not
  // subject-bound is invalid, and fails closed by still suppressing the target.
  const subjectTombstones = (state.tombstones ?? []).filter((item) => {
    const type = String(item.targetType).toUpperCase();
    return type === "SUBJECT" || type === "SUBJECT_CONTENT";
  });
  const deletedSubjects = new Set(
    subjectTombstones.filter((item) => String(item.targetType).toUpperCase() === "SUBJECT")
      .map((item) => item.targetId)
  );
  const clearedSubjects = new Set(subjectTombstones.map((item) => item.targetId));
  const subjectScoped = (subjectId) =>
    typeof subjectId === "string" && (deletedSubjects.has(subjectId) || clearedSubjects.has(subjectId));

  const events = state.events.filter((event) =>
    !tombstonedEvents.has(event.eventId) && !subjectScoped(event.subjectId));
  const restrictedEvents = events.filter((event) => !isSharedVisible(event));
  const entries = [];
  let hidden = restrictedEvents.length > 0;

  for (const choice of state.choices) {
    if (tombstonedChoices.has(choice.id)) continue;
    if (subjectScoped(choice.childId)) continue;
    const source = choice.sourceEventId
      ? events.find((event) => event.eventId === choice.sourceEventId) ?? null
      : null;
    // A missing source ID stays readable: there is no restricted stored event
    // pointing at this choice, so nothing is being protected by hiding it.
    const sourceRestricted = source !== null && !isSharedVisible(source);
    const namedByRestricted = restrictedEvents.some((event) => event.payload?.choiceId === choice.id);
    if (sourceRestricted || namedByRestricted) {
      hidden = true;
      continue;
    }
    entries.push({
      kind: "choice",
      id: choice.id,
      childId: choice.childId,
      childName: childName(state, choice.childId),
      occurredAt: choice.chosenAt,
      authorName: memberName(state, source?.authorId ?? null),
      status: choice.status,
      title: choice.opportunity?.title ?? "",
      ecosystem: choice.opportunity?.ecosystem ?? null,
      laterView: choice.feedback?.value ?? null,
      sourceEventId: choice.sourceEventId ?? null
    });
  }

  for (const event of events) {
    if (!isSharedVisible(event)) continue;
    if (event.eventType === "opportunity.chosen" || event.eventType === "opportunity.nothing-chosen" ||
        event.eventType === "opportunity.child-vetoed" || event.eventType === "opportunity.feedback-recorded") {
      // Already represented by the choice entry above; showing it twice would
      // make one decision look like two.
      continue;
    }
    entries.push({
      kind: "event",
      id: event.eventId,
      eventType: event.eventType,
      childId: event.subjectId ?? null,
      childName: childName(state, event.subjectId),
      occurredAt: event.occurredAt,
      authorName: memberName(state, event.authorId),
      actorRole: event.actorRole,
      visibility: event.visibility,
      payload: event.payload
    });
  }

  entries.sort((left, right) => String(right.occurredAt).localeCompare(String(left.occurredAt)));
  return Object.freeze({
    entries: Object.freeze(entries),
    // The only thing the screen may say about what it is not showing.
    hiddenRestricted: hidden
  });
}

/**
 * Appends a correction. The original record stays exactly as it was and keeps
 * its author and time; the new event says what the child actually meant, under
 * the name of whoever is recording now.
 */
export function recordCorrection(state, { childId, targetEventId, correction, authorId }) {
  const target = state.events.find((event) => event.eventId === targetEventId);
  if (!target) throw new TimelineError("找不到需要纠正的记录");
  if (target.subjectId !== childId) throw new TimelineError("这条记录不属于这个孩子");
  const text = typeof correction === "string" ? correction.trim() : "";
  if (text === "") throw new TimelineError("请先写一句孩子真正的意思");
  if (text.length > 500) throw new TimelineError("纠正请写在 500 个字符以内");

  const event = createFamilyEvent(state, "evidence.corrected", {
    targetEventId,
    correction: text,
    // Stated in the record itself, not only on screen.
    preservesOriginal: true
  }, { authorId, subjectId: childId, visibility: "family" });
  return appendEvent(state, event);
}

/**
 * Deletes one saved choice with its linked events. Returns the opaque IDs the
 * audit event refers to, so a caller can report what happened without the
 * deleted title ever re-entering a render tree.
 */
export function deleteChoice(state, { choiceId, authorId }) {
  const choice = state.choices.find((item) => item.id === choiceId);
  if (!choice) throw new TimelineError("找不到这次选择");

  const child = state.children.find((item) => item.id === choice.childId);
  if (!child) throw new TimelineError("找不到这个孩子的记录");

  // ADR 0026: under 13 a caregiver or guardian acts; at 13+ the subject must
  // confirm the deletion themselves and it is attributed to them.
  const stage = childLifecycle(child);
  const subjectMember = state.members.find((member) => member.id === child.memberId);
  const actor = state.members.find((member) => member.id === authorId);
  if (!actor) throw new AuthorizationError("当前记录者不能替这个人做这个决定，请换一位记录者");
  if (stage.age >= 13) {
    if (actor.id !== subjectMember?.id) {
      throw new AuthorizationError("13 岁以上的删除需要由孩子本人在共享设备上确认");
    }
  } else if (actor.role !== "caregiver" && actor.role !== "guardian") {
    throw new AuthorizationError("这个删除需要由家长来完成");
  }

  const linked = state.events.filter((event) =>
    event.subjectId === choice.childId && event.payload?.choiceId === choice.id);

  const deletedAt = now();
  const tombstoneFor = (targetType, targetId) => Object.freeze({
    schema: TOMBSTONE_SCHEMA,
    tombstoneId: newId(),
    householdId: state.household.id,
    targetType,
    targetId,
    subjectId: choice.childId,
    authorId: actor.id,
    deviceId: "jianyu-web-local",
    deletedAt,
    reasonCode: TOMBSTONE_REASON
  });

  const choiceTombstone = tombstoneFor("CHOICE", choice.id);
  const eventTombstones = linked.map((event) => tombstoneFor("EVENT", event.eventId));

  state.choices = state.choices.filter((item) => item.id !== choice.id);
  const removedEventIds = new Set(linked.map((event) => event.eventId));
  state.events = state.events.filter((event) => !removedEventIds.has(event.eventId));
  state.tombstones.push(choiceTombstone, ...eventTombstones);

  // Content-free by construction: opaque target and tombstone IDs only.
  const audit = createFamilyEvent(state, "opportunity.choice-deleted", {
    choiceId: choice.id,
    choiceTombstoneId: choiceTombstone.tombstoneId,
    eventTombstoneIds: eventTombstones.map((item) => item.tombstoneId)
  }, { authorId: actor.id, subjectId: choice.childId, visibility: "guardians" });
  appendEvent(state, audit);

  return Object.freeze({
    choiceId: choice.id,
    removedEventIds: Object.freeze([...removedEventIds]),
    tombstones: Object.freeze([choiceTombstone, ...eventTombstones])
  });
}
