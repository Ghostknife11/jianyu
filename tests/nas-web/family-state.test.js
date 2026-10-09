import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  CURRENT_STATE_VERSION,
  STATE_SCHEMA,
  addCaregiver,
  addChild,
  appendEvent,
  assertFamilyState,
  childLifecycle,
  completedAge,
  createFamilyEvent,
  createInitialFamilyState,
  migrateFamilyState
} from "../../apps/jianyu-web-nas/web/src/family-state.js";
import { assertEvent } from "../../packages/foe-schema/src/index.js";
import { lifecycleAuthority } from "../../packages/policy-sdk/src/index.js";

function sampleState() {
  return createInitialFamilyState({
    familyName: "隅之家",
    caregiverName: "妈妈",
    childName: "小隅",
    birthDate: "2013-09-15"
  });
}

function at(date) {
  return new Date(`${date}T12:00:00`);
}

describe("web family state", () => {
  it("creates a household whose collections the public merge understands", () => {
    const state = sampleState();
    assert.equal(state.schema, STATE_SCHEMA);
    assert.equal(CURRENT_STATE_VERSION, 1);
    assert.deepEqual(Object.keys(state).sort(), [
      "children", "choices", "events", "evidence", "household", "hypotheses", "members", "preferences", "schema", "tombstones"
    ]);
    assert.equal(state.members.length, 2);
    assert.equal(state.children.length, 1);
    assert.equal(state.children[0].memberId, state.members[1].id, "the child member points back at the child record");
    assert.equal(state.members[1].subjectId, state.children[0].id);
    assert.equal(state.household.name, "隅之家");
  });

  it("requires a family name, a caregiver, and a valid birth date", () => {
    assert.throws(() => createInitialFamilyState({ caregiverName: "妈妈" }), TypeError);
    assert.throws(() => createInitialFamilyState({ familyName: "隅之家" }), TypeError);
    assert.throws(() => createInitialFamilyState({ familyName: "隅之家", caregiverName: "妈妈", childName: "小隅" }), TypeError);
    assert.throws(
      () => createInitialFamilyState({ familyName: "隅之家", caregiverName: "妈妈", childName: "小隅", birthDate: "2023-02-29" }),
      (error) => error instanceof TypeError && error.message.includes("出生日期")
    );
    assert.throws(
      () => createInitialFamilyState({ familyName: "隅之家", caregiverName: "妈妈", childName: "小隅", birthDate: "15/09/2013" }),
      TypeError
    );
  });

  it("changes the stage on the exact birthday, not on January first", () => {
    const state = sampleState();
    const child = state.children[0];
    assert.equal(childLifecycle(child, at("2026-09-14")).stage, "co-select");
    assert.equal(childLifecycle(child, at("2026-09-15")).stage, "hand-over");
    assert.equal(childLifecycle(child, at("2026-09-15")).authority.primary, "child");
    assert.equal(completedAge(child, at("2026-09-14")).age, 12);
    assert.equal(completedAge(child, at("2026-09-15")).age, 13);
  });

  it("advances a February 29 birthday on March 1 in a non-leap year", () => {
    const child = { birthDate: "2012-02-29", birthYear: 2012 };
    assert.equal(childLifecycle(child, at("2025-02-28")).age, 12);
    assert.equal(childLifecycle(child, at("2025-02-28")).stage, "co-select");
    assert.equal(childLifecycle(child, at("2025-03-01")).age, 13);
    assert.equal(childLifecycle(child, at("2025-03-01")).stage, "hand-over");
    assert.equal(childLifecycle(child, at("2024-02-29")).age, 12, "in a leap year the birthday is February 29 itself");
    assert.equal(childLifecycle(child, at("2024-02-28")).age, 11);
  });

  it("labels a year-only record as approximate instead of inventing a date", () => {
    const legacy = { birthYear: 2013 };
    const lifecycle = childLifecycle(legacy, at("2026-10-10"));
    assert.equal(lifecycle.age, 13);
    assert.equal(lifecycle.approximate, true);
    assert.equal(lifecycle.stage, "hand-over");
  });

  it("keeps a child below four in the record but offers no discovery", () => {
    const lifecycle = childLifecycle({ birthDate: "2024-06-01" }, at("2026-10-10"));
    assert.equal(lifecycle.age, 2);
    assert.equal(lifecycle.stage, null);
    assert.equal(lifecycle.authority, null);
    assert.equal(lifecycle.discoveryOffered, false);
    assert.throws(() => completedAge({}, at("2026-10-10")), TypeError);
  });

  it("keeps graduation open ended past sixteen", () => {
    const adult = childLifecycle({ birthDate: "1986-09-15" }, at("2026-10-10"));
    assert.equal(adult.age, 40);
    assert.equal(adult.stage, "graduation");
    assert.equal(adult.authority.primary, "self");
    assert.equal(lifecycleAuthority("graduation").stopCaregiverModeling, true);
  });

  it("appends public event envelopes and ignores duplicates", () => {
    const state = sampleState();
    const event = createFamilyEvent(state, "opportunity.choice-recorded", { choiceId: "c1" }, {
      authorId: state.members[0].id,
      subjectId: state.children[0].id
    });
    assertEvent(event);
    assert.equal(event.schema, "org.foe.event/v1");
    assert.equal(event.actorRole, "caregiver");
    assert.equal(event.visibility, "guardians");

    appendEvent(state, event);
    appendEvent(state, event);
    assert.equal(state.events.length, 1, "the same event ID must not be appended twice");
  });

  it("resolves the author from the member list and refuses an empty household", () => {
    const state = sampleState();
    const asChild = createFamilyEvent(state, "family.member-added", {}, { authorId: state.members[1].id });
    assert.equal(asChild.actorRole, "child", "an explicit child author keeps the child role");
    assert.equal(asChild.subjectId, state.children[0].id, "a child author's own subject is attached");

    const asCaregiver = createFamilyEvent(state, "family.member-added", {});
    assert.equal(asCaregiver.authorId, state.members[0].id, "the first caregiver is the default author");

    assert.throws(
      () => createFamilyEvent({ ...state, members: [] }, "family.member-added", {}),
      (error) => error instanceof TypeError && error.message.includes("署名")
    );
  });

  it("records added children and members as events", () => {
    const state = sampleState();
    const child = addChild(state, { displayName: "二宝", birthDate: "2019-05-02", authorId: state.members[0].id });
    assert.equal(state.children.length, 2);
    assert.equal(state.members.length, 3);
    assert.equal(state.events.at(-1).eventType, "family.child-added");
    assert.equal(state.events.at(-1).subjectId, child.id);

    const caregiver = addCaregiver(state, { displayName: "爸爸", authorId: state.members[0].id });
    assert.equal(caregiver.role, "caregiver");
    assert.equal(state.events.at(-1).eventType, "family.member-added");
  });

  it("rejects a state that is missing a collection or carries an invalid event", () => {
    const state = sampleState();
    assertFamilyState(state);
    assert.throws(() => assertFamilyState({ ...state, events: null }), (error) => error instanceof TypeError);
    assert.throws(() => assertFamilyState({ ...state, schema: "org.jianyu.family-vault/v2" }), TypeError);
    assert.throws(
      () => assertFamilyState({ ...state, events: [{ schema: "org.foe.event/v1" }] }),
      (error) => error instanceof TypeError
    );
    assert.throws(() => appendEvent(state, { schema: "not-an-event" }), TypeError);
  });

  it("migrates a current state without changing it and fails closed on a newer one", () => {
    const state = sampleState();
    const result = migrateFamilyState(structuredClone(state));
    assert.equal(result.migrated, false);
    assert.deepEqual(result.state, state);

    assert.throws(
      () => migrateFamilyState({ ...structuredClone(state), schema: "org.jianyu.web-family-state/v2" }),
      (error) => error instanceof Error && error.message.includes("更高版本")
    );
    assert.throws(() => migrateFamilyState({ schema: "org.jianyu.family-vault/v2" }), TypeError);
    assert.throws(() => migrateFamilyState(null), TypeError);
  });

  it("keeps unknown optional fields through a round trip", () => {
    const state = sampleState();
    state.preferences.futureField = { keep: true };
    const migrated = migrateFamilyState(structuredClone(state));
    assert.deepEqual(migrated.state.preferences.futureField, { keep: true });
  });
});
