// Tests for the shared family footprint. The invariants worth protecting are
// the ones a screen must not be able to get wrong: a restricted record never
// reaches a shared view through a direct event, a dependent choice, or a count;
// a correction appends rather than rewrites; and deleting a choice writes the
// tombstones that stop an old copy from restoring its title.

import assert from "node:assert/strict";
import { describe, it, afterEach, beforeEach } from "node:test";
import { NasApi } from "../../apps/jianyu-web-nas/web/src/session.js";
import { NasFamilyVault } from "../../apps/jianyu-web-nas/web/src/nas-vault.js";
import { mergeFamilyState } from "../../packages/foe-vault/src/index.js";
import {
  TimelineError,
  deleteChoice,
  isSharedVisible,
  projectSharedTimeline,
  recordCorrection
} from "../../apps/jianyu-web-nas/web/src/timeline-service.js";
import { startServer, stopServer, createCookieFetch } from "./helpers.mjs";

const PASSPHRASE = "family-passphrase-2026";

function yearsAgo(years) {
  const date = new Date();
  date.setUTCFullYear(date.getUTCFullYear() - years);
  return date.toISOString().slice(0, 10);
}

const DOOR = Object.freeze({
  schema: "org.foe.opportunity/v1",
  opportunityId: "door-1",
  title: "一起拆开一只旧轮胎看看花纹",
  ecosystem: "making",
  entryPoint: { motivation: "child-current-pull", whyNow: "孩子自己在问抓地这件事", startupCost: "low" },
  goalAlignment: { primary: "child" },
  requirements: { timeMinutes: 45, costBand: "free-existing", caregiverEnergy: "low", travelMinutes: 0 },
  source: { kind: "pack", publisher: "org.jianyu.web.offline-demo", retrievedAt: "2026-10-01T00:00:00.000Z" },
  verification: "idea",
  explanation: "用家里已有的东西讲清一个真问题。"
});

describe("shared timeline projection", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  async function vaultWith(birthDate) {
    const store = new Map();
    return NasFamilyVault.create({
      passphrase: PASSPHRASE,
      familyName: "隅之家",
      caregiverName: "妈妈",
      childName: "小隅",
      birthDate
    }, {
      api: new NasApi({ baseUrl: instance.baseUrl, fetchImpl: createCookieFetch() }),
      store: {
        async read() { return store.get("primary") ?? null; },
        async write(record) { store.set("primary", structuredClone(record)); },
        async clear() { store.clear(); }
      }
    });
  }

  it("treats unknown visibility as restricted", () => {
    assert.equal(isSharedVisible({ visibility: "family" }), true);
    assert.equal(isSharedVisible({ visibility: "guardians" }), true);
    for (const visibility of ["private", "selected-members", "recommendation-only", "child-private", "some-future-value"]) {
      assert.equal(isSharedVisible({ visibility }), false, `${visibility} must fail closed`);
    }
    assert.equal(isSharedVisible(undefined), false);
  });

  it("shows the family's own records with their author and time", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];

    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "interest-1",
      eventType: "interest.observed",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T09:00:00.000Z",
      recordedAt: "2026-10-01T09:00:00.000Z",
      visibility: "guardians",
      payload: { expression: "孩子说自己很喜欢赛车" }
    });

    const view = projectSharedTimeline(state);
    assert.equal(view.entries.length, 1);
    assert.equal(view.entries[0].authorName, "妈妈");
    assert.equal(view.entries[0].childName, "小隅");
    assert.equal(view.entries[0].eventType, "interest.observed");
    assert.equal(view.hiddenRestricted, false);
  });

  it("hides a restricted event and says only that something is hidden", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];

    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "private-1",
      eventType: "interest.observed",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T09:00:00.000Z",
      recordedAt: "2026-10-01T09:00:00.000Z",
      visibility: "private",
      payload: { expression: "只有孩子自己知道的那句话" }
    });

    const view = projectSharedTimeline(state);
    assert.equal(view.entries.length, 0);
    assert.equal(view.hiddenRestricted, true);
    // The projection carries no count, subject, type, or content of what it hides.
    assert.equal(JSON.stringify(view).includes("只有孩子自己知道的那句话"), false);
    assert.equal(JSON.stringify(view).includes("private"), false);
  });

  it("hides a choice whose source event is restricted", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];

    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "source-1",
      eventType: "interest.observed",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T09:00:00.000Z",
      recordedAt: "2026-10-01T09:00:00.000Z",
      visibility: "selected-members",
      payload: { expression: "受限的那句话" }
    });
    state.choices.push({
      id: "choice-1",
      childId: child.id,
      opportunity: DOOR,
      sourceEventId: "source-1",
      status: "chosen",
      chosenAt: "2026-10-01T10:00:00.000Z",
      feedback: null
    });

    const view = projectSharedTimeline(state);
    assert.equal(view.entries.some((entry) => entry.kind === "choice"), false);
    assert.equal(view.hiddenRestricted, true);
    assert.equal(JSON.stringify(view).includes("一起拆开一只旧轮胎看看花纹"), false);
  });

  it("hides a choice that a restricted event names, and keeps one whose source is simply gone", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];

    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "restricted-namer",
      eventType: "opportunity.chosen",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T10:00:00.000Z",
      recordedAt: "2026-10-01T10:00:00.000Z",
      visibility: "recommendation-only",
      payload: { choiceId: "choice-named", title: "受限选择" }
    });
    state.choices.push(
      { id: "choice-named", childId: child.id, opportunity: DOOR, sourceEventId: null, status: "chosen", chosenAt: "2026-10-01T10:00:00.000Z", feedback: null },
      // A missing legacy source ID with no restricted event pointing at it stays
      // readable; there is nothing being protected by hiding it.
      { id: "choice-orphan", childId: child.id, opportunity: DOOR, sourceEventId: "absent-event", status: "chosen", chosenAt: "2026-10-01T11:00:00.000Z", feedback: null }
    );

    const view = projectSharedTimeline(state);
    const ids = view.entries.filter((entry) => entry.kind === "choice").map((entry) => entry.id);
    assert.deepEqual(ids, ["choice-orphan"]);
    assert.equal(view.hiddenRestricted, true);
  });

  it("does not apply a tombstone only in the merge", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];

    state.choices.push({
      id: "choice-1",
      childId: child.id,
      opportunity: DOOR,
      sourceEventId: "source-1",
      status: "chosen",
      chosenAt: "2026-10-01T10:00:00.000Z",
      feedback: null
    });
    state.tombstones.push({
      schema: "org.foe.deletion-tombstone/v1",
      tombstoneId: "tomb-1",
      householdId: state.household.id,
      targetType: "CHOICE",
      targetId: "choice-1",
      subjectId: child.id,
      authorId: caregiver.id,
      deviceId: "jianyu-web-local",
      deletedAt: "2026-10-02T00:00:00.000Z",
      reasonCode: "family-request"
    });

    const view = projectSharedTimeline(state);
    assert.equal(view.entries.some((entry) => entry.kind === "choice"), false,
      "a tombstoned choice must not appear on the shared screen");
  });

  it("appends a correction and leaves the original exactly as it was", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];
    const original = {
      schema: "org.foe.event/v1",
      eventId: "interest-1",
      eventType: "interest.observed",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T09:00:00.000Z",
      recordedAt: "2026-10-01T09:00:00.000Z",
      visibility: "guardians",
      payload: { expression: "孩子说想买一辆遥控车" }
    };
    state.events.push(original);
    const before = structuredClone(state.events);

    const correction = recordCorrection(state, {
      childId: child.id,
      targetEventId: "interest-1",
      correction: "  孩子的意思是想弄明白遥控车怎么转弯，不是想买  ",
      authorId: caregiver.id
    });

    assert.equal(correction.eventType, "evidence.corrected");
    assert.equal(correction.payload.correction, "孩子的意思是想弄明白遥控车怎么转弯，不是想买");
    assert.equal(correction.payload.preservesOriginal, true);
    assert.equal(correction.payload.targetEventId, "interest-1");
    // The original is untouched, so the record still says who said what and when.
    assert.deepEqual(state.events[0], before[0]);
    assert.equal(state.events.length, 2);
  });

  it("refuses a correction that has no target, belongs to another child, or says nothing", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];
    const other = { id: "child-2", memberId: "member-2", displayName: "二宝", birthDate: yearsAgo(5), birthYear: new Date().getFullYear() - 5, createdAt: "2026-01-01T00:00:00.000Z" };
    state.children.push(other);

    assert.throws(() => recordCorrection(state, { childId: child.id, targetEventId: "absent", correction: "x", authorId: caregiver.id }), TimelineError);
    assert.throws(() => recordCorrection(state, { childId: other.id, targetEventId: "absent", correction: "x", authorId: caregiver.id }), TimelineError);
    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "interest-1",
      eventType: "interest.observed",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T09:00:00.000Z",
      recordedAt: "2026-10-01T09:00:00.000Z",
      visibility: "guardians",
      payload: { expression: "孩子说想买一辆遥控车" }
    });
    assert.throws(() => recordCorrection(state, { childId: child.id, targetEventId: "interest-1", correction: "   ", authorId: caregiver.id }), /请先写一句/);
    assert.throws(() => recordCorrection(state, { childId: other.id, targetEventId: "interest-1", correction: "x", authorId: caregiver.id }), /不属于这个孩子/);
  });
});

describe("choice deletion", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  async function vaultWith(birthDate) {
    const store = new Map();
    return NasFamilyVault.create({
      passphrase: PASSPHRASE,
      familyName: "隅之家",
      caregiverName: "妈妈",
      childName: "小隅",
      birthDate
    }, {
      api: new NasApi({ baseUrl: instance.baseUrl, fetchImpl: createCookieFetch() }),
      store: {
        async read() { return store.get("primary") ?? null; },
        async write(record) { store.set("primary", structuredClone(record)); },
        async clear() { store.clear(); }
      }
    });
  }

  async function withChoice(birthDate) {
    const vault = await vaultWith(birthDate);
    const state = vault.state;
    const caregiver = state.members.find((member) => member.role === "caregiver");
    const child = state.children[0];
    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "interest-1",
      eventType: "interest.observed",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T09:00:00.000Z",
      recordedAt: "2026-10-01T09:00:00.000Z",
      visibility: "guardians",
      payload: { expression: "孩子说自己很喜欢赛车" }
    });
    state.choices.push({
      id: "choice-1",
      childId: child.id,
      opportunity: DOOR,
      sourceEventId: "interest-1",
      status: "chosen",
      chosenAt: "2026-10-01T10:00:00.000Z",
      feedback: { value: "孩子后来很喜欢", recordedAt: "2026-10-01T12:00:00.000Z" }
    });
    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "chosen-1",
      eventType: "opportunity.chosen",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T10:00:00.000Z",
      recordedAt: "2026-10-01T10:00:00.000Z",
      visibility: "family",
      payload: { choiceId: "choice-1", title: DOOR.title }
    });
    state.events.push({
      schema: "org.foe.event/v1",
      eventId: "feedback-1",
      eventType: "opportunity.feedback-recorded",
      eventVersion: 1,
      householdId: state.household.id,
      authorId: caregiver.id,
      actorRole: "caregiver",
      subjectId: child.id,
      deviceId: "jianyu-web-local",
      occurredAt: "2026-10-01T12:00:00.000Z",
      recordedAt: "2026-10-01T12:00:00.000Z",
      visibility: "family",
      payload: { choiceId: "choice-1", value: "孩子后来很喜欢" }
    });
    return { vault, caregiver, child };
  }

  it("removes the choice with its linked events and leaves the interest evidence", async () => {
    const { vault, caregiver } = await withChoice(yearsAgo(8));
    const state = vault.state;

    const result = deleteChoice(state, { choiceId: "choice-1", authorId: caregiver.id });

    assert.deepEqual([...result.removedEventIds], ["chosen-1", "feedback-1"]);
    assert.equal(state.choices.length, 0);
    assert.equal(state.events.some((event) => event.eventId === "chosen-1" || event.eventId === "feedback-1"), false);
    // The earlier interest evidence is an independent family record and stays.
    assert.ok(state.events.some((event) => event.eventId === "interest-1"));

    const view = projectSharedTimeline(state);
    assert.equal(JSON.stringify(view).includes(DOOR.title), false, "the deleted title is gone from the screen");
    assert.equal(JSON.stringify(view).includes("孩子后来很喜欢"), false, "the later view is gone too");
  });

  it("writes one CHOICE and one EVENT tombstone per linked event, and a content-free audit", async () => {
    const { vault, caregiver } = await withChoice(yearsAgo(8));
    const state = vault.state;

    const result = deleteChoice(state, { choiceId: "choice-1", authorId: caregiver.id });
    assert.equal(result.tombstones.length, 3);

    const byType = new Map(result.tombstones.map((item) => [item.targetType, item]));
    assert.ok(byType.has("CHOICE"));
    assert.equal(result.tombstones.filter((item) => item.targetType === "EVENT").length, 2);
    for (const tombstone of result.tombstones) {
      assert.equal(tombstone.schema, "org.foe.deletion-tombstone/v1");
      assert.equal(tombstone.householdId, state.household.id);
      assert.equal(tombstone.authorId, caregiver.id);
      assert.equal(tombstone.reasonCode, "family-request");
    }

    const audit = state.events.find((event) => event.eventType === "opportunity.choice-deleted");
    assert.ok(audit, "the deletion itself is auditable");
    const auditText = JSON.stringify(audit.payload);
    assert.equal(auditText.includes(DOOR.title), false);
    assert.equal(auditText.includes("孩子后来很喜欢"), false);
    assert.equal(audit.payload.choiceId, "choice-1");
    assert.equal(audit.payload.choiceTombstoneId, byType.get("CHOICE").tombstoneId);
    assert.equal(audit.payload.eventTombstoneIds.length, 2);
  });

  it("stops an old copy from restoring the deleted content", async () => {
    const { vault, caregiver } = await withChoice(yearsAgo(8));
    const state = vault.state;
    // What another device still holds: everything exactly as it was before the
    // deletion, arriving afterwards.
    const stale = {
      ...structuredClone(state),
      choices: structuredClone(state.choices),
      events: structuredClone(state.events)
    };

    deleteChoice(state, { choiceId: "choice-1", authorId: caregiver.id });

    const merged = mergeFamilyState(state, stale);
    assert.equal(merged.choices.length, 0, "the choice stays deleted after a merge");
    assert.equal(merged.events.some((event) => event.eventId === "chosen-1"), false);
    // Idempotent: merging the same stale copy again changes nothing.
    assert.deepEqual(mergeFamilyState(merged, stale), merged);
    assert.equal(JSON.stringify(projectSharedTimeline(merged)).includes(DOOR.title), false);
  });

  it("needs a caregiver for a young child and the young person themselves at 13+", async () => {
    const young = await withChoice(yearsAgo(8));
    assert.throws(() => deleteChoice(young.vault.state, { choiceId: "choice-1", authorId: "nobody" }), /请换一位记录者/);

    const older = await withChoice(yearsAgo(15));
    const olderState = older.vault.state;
    const childMember = olderState.children[0].memberId;
    assert.throws(() => deleteChoice(olderState, { choiceId: "choice-1", authorId: older.caregiver.id }),
      /13 岁以上的删除需要由孩子本人在共享设备上确认/);
    const result = deleteChoice(olderState, { choiceId: "choice-1", authorId: childMember });
    assert.equal(result.tombstones[0].authorId, childMember, "at 13+ the action is attributed to the young person");
  });

  it("refuses an unknown choice", async () => {
    const { vault, caregiver } = await withChoice(yearsAgo(8));
    assert.throws(() => deleteChoice(vault.state, { choiceId: "absent", authorId: caregiver.id }), TimelineError);
  });
});
