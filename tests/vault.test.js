import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  InMemoryEventStore,
  createHouseholdSyncKey,
  mergeFamilyState,
  openSyncEnvelope,
  sealSyncFrame
} from "../packages/foe-vault/src/index.js";

const event = {
  schema: "org.foe.event/v1",
  eventId: "evt_once",
  eventType: "decision.recorded",
  eventVersion: 1,
  householdId: "household_demo",
  subjectId: "child_demo",
  authorId: "child_demo",
  actorRole: "self",
  deviceId: "device_demo",
  occurredAt: "2026-09-12T10:00:00+08:00",
  recordedAt: "2026-09-12T10:00:01+08:00",
  visibility: "family",
  payload: { decision: "nothing" }
};

test("in-memory event prototype appends idempotently and exports", () => {
  const store = new InMemoryEventStore();
  assert.equal(store.append(event), true);
  assert.equal(store.append(event), false);
  assert.equal(store.list().length, 1);
  assert.equal(store.export().schema, "org.foe.export/v1");
});

test("public vault merge keeps distinct evidence and fails closed on same-id conflict", () => {
  const base = familyState([{ id: "local", childId: "child_demo", expression: "赛车" }]);
  const merged = mergeFamilyState(base, familyState([{ id: "remote", childId: "child_demo", expression: "机械" }]));
  assert.deepEqual(new Set(merged.evidence.map((item) => item.id)), new Set(["local", "remote"]));
  assert.throws(
    () => mergeFamilyState(base, familyState([{ id: "local", childId: "child_demo", expression: "不同内容" }])),
    /conflicting evidence object/
  );
});

test("public vault tombstone dominates stale content regardless of arrival order", () => {
  const deleted = { id: "deleted", childId: "child_demo", expression: "不应复活" };
  const tombstone = {
    schema: "org.foe.deletion-tombstone/v1",
    tombstoneId: "delete-1",
    householdId: "household_demo",
    targetType: "EVIDENCE",
    targetId: deleted.id,
    subjectId: "child_demo",
    authorId: "caregiver_demo",
    deviceId: "device_demo",
    deletedAt: "2026-09-14T00:00:00Z",
    reasonCode: "family-request"
  };
  const local = familyState([], [tombstone]);
  const merged = mergeFamilyState(local, familyState([deleted]));
  assert.equal(merged.evidence.length, 0);
  assert.deepEqual(merged.tombstones, [tombstone]);
  assert.deepEqual(mergeFamilyState(merged, familyState([deleted])), merged);
});

test("choice and linked event tombstones prevent old selection details from returning", () => {
  const oldChoice = { id: "choice-1", childId: "child_demo", opportunity: { title: "不应重现的入口" }, sourceEventId: "interest-event", status: "chosen", chosenAt: "2026-09-14T00:00:00Z" };
  const oldEvent = { eventId: "choice-event", subjectId: "child_demo", payload: { choiceId: "choice-1", title: "不应重现的入口" } };
  const marker = (targetType, targetId) => ({
    schema: "org.foe.deletion-tombstone/v1", tombstoneId: `delete-${targetId}`,
    householdId: "household_demo", targetType, targetId, subjectId: "child_demo",
    authorId: "caregiver_demo", deviceId: "device_demo", deletedAt: "2026-09-14T01:00:00Z",
    reasonCode: "family-request"
  });
  const local = { ...familyState(), tombstones: [marker("CHOICE", oldChoice.id), marker("EVENT", oldEvent.eventId)] };
  const stale = { ...familyState(), choices: [oldChoice], events: [oldEvent] };
  const merged = mergeFamilyState(local, stale);

  assert.equal(merged.choices.length, 0);
  assert.equal(merged.events.length, 0);
  assert.deepEqual(mergeFamilyState(merged, stale), merged);
});

test("subject-content tombstone keeps family relationship while stale personal history stays erased", () => {
  const relationship = {
    member: { id: "member_child", subjectId: "child_demo", role: "CHILD" },
    child: { id: "child_demo", memberId: "member_child", displayName: "本人", birthYear: 2010 }
  };
  const stale = {
    ...familyState([{ id: "stale", childId: "child_demo", expression: "不应复活" }]),
    members: [relationship.member],
    children: [relationship.child],
    events: [{ eventId: "stale-event", subjectId: "child_demo" }],
    choices: [{ id: "stale-choice", childId: "child_demo" }]
  };
  const tombstone = {
    schema: "org.foe.deletion-tombstone/v2",
    tombstoneId: "clear-subject-content",
    householdId: "household_demo",
    targetType: "SUBJECT_CONTENT",
    targetId: "child_demo",
    subjectId: "child_demo",
    authorId: "member_child",
    deviceId: "device_demo",
    deletedAt: "2026-09-15T00:00:00Z",
    reasonCode: "graduation-relationship-only"
  };
  const local = { ...stale, evidence: [], events: [], choices: [], tombstones: [tombstone] };
  const merged = mergeFamilyState(local, stale);

  assert.equal(merged.members.length, 1);
  assert.equal(merged.children.length, 1);
  assert.equal(merged.evidence.length, 0);
  assert.equal(merged.events.length, 0);
  assert.equal(merged.choices.length, 0);
  assert.throws(
    () => mergeFamilyState({ ...local, tombstones: [{ ...tombstone, schema: "org.foe.deletion-tombstone/v1" }] }, stale),
    /v2 subject-bound tombstone/
  );
});

test("public vault sync envelope hides frame semantics and opens with the household key", async () => {
  const key = createHouseholdSyncKey("opaque_sync_key_0001", Uint8Array.from({ length: 32 }, (_, index) => index + 1));
  const frame = new TextEncoder().encode(JSON.stringify({
    schema: "org.foe.sync-frame/v1",
    householdId: "household-private",
    deviceId: "device-private",
    expression: "孩子最近喜欢赛车"
  }));
  const encrypted = await sealSyncFrame(frame, key);
  const visible = new TextDecoder().decode(encrypted);

  assert.deepEqual(await openSyncEnvelope(encrypted, key), frame);
  assert.match(visible, /org\.foe\.encrypted-sync-envelope\/v1/);
  assert.doesNotMatch(visible, /household-private|device-private|赛车/);

  const second = await sealSyncFrame(frame, key);
  assert.notEqual(new TextDecoder().decode(second), visible);
  assert.deepEqual(await openSyncEnvelope(second, key), frame);
});

test("public vault sync envelope fails closed for wrong keys, tampering, and unknown fields", async () => {
  const key = createHouseholdSyncKey("opaque_sync_key_0001", new Uint8Array(32).fill(3));
  const wrongKey = createHouseholdSyncKey("opaque_sync_key_0001", new Uint8Array(32).fill(4));
  const encrypted = await sealSyncFrame(new TextEncoder().encode("synthetic frame"), key);

  await assert.rejects(openSyncEnvelope(encrypted, wrongKey), /wrong or the envelope was modified/);

  const envelope = JSON.parse(new TextDecoder().decode(encrypted));
  const changed = envelope.ciphertext[0] === "A" ? "B" : "A";
  envelope.ciphertext = changed + envelope.ciphertext.slice(1);
  await assert.rejects(
    openSyncEnvelope(new TextEncoder().encode(JSON.stringify(envelope)), key),
    /wrong or the envelope was modified/
  );

  envelope.ciphertext = JSON.parse(new TextDecoder().decode(encrypted)).ciphertext;
  envelope.unexpected = true;
  await assert.rejects(
    openSyncEnvelope(new TextEncoder().encode(JSON.stringify(envelope)), key),
    /missing or unknown fields/
  );
});

test("shared Web Crypto envelope fixture remains readable by public implementations", async () => {
  const fixture = JSON.parse(await readFile(
    new URL("../apps/jianyu-android/core/data/src/test/resources/interop/encrypted-sync-envelope-v1.json", import.meta.url),
    "utf8"
  ));
  assert.equal(fixture.testVector, "bytes-1-through-32");
  const key = createHouseholdSyncKey(fixture.keyId, Uint8Array.from({ length: 32 }, (_, index) => index + 1));
  const opened = await openSyncEnvelope(new TextEncoder().encode(JSON.stringify(fixture.envelope)), key);

  assert.deepEqual(JSON.parse(new TextDecoder().decode(opened)), fixture.frame);
});

function familyState(evidence = [], tombstones = []) {
  return {
    schema: "org.jianyu.family-vault/v4",
    household: { id: "household_demo", name: "Synthetic family", createdAt: "2026-01-01T00:00:00Z" },
    members: [],
    children: [],
    evidence,
    hypotheses: [],
    choices: [],
    events: [],
    tombstones
  };
}
