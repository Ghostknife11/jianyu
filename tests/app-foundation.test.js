import test from "node:test";
import assert from "node:assert/strict";
import { FamilySession } from "../apps/jianyu-web-prototype/src/app-state.js";
import { createInitialFamilyState } from "../apps/jianyu-web-prototype/src/browser-vault.js";
import { discoverLocalOpportunities } from "../apps/jianyu-web-prototype/src/local-discovery.js";
import { ageBandFor, lifecycleLabel } from "../apps/jianyu-web-prototype/src/opportunity-service.js";
import { migrateFamilyState } from "../apps/jianyu-web-prototype/src/vault-migrations.js";
import { authorizeFamilyAction } from "../apps/jianyu-web-prototype/src/authorization.js";

test("local discovery follows the current expression across multiple ecosystems", () => {
  const options = discoverLocalOpportunities("恐龙为什么会消失");
  assert.equal(options.length, 4);
  assert.ok(options.every((option) => option.title.includes("恐龙为什么会消失")));
  assert.equal(new Set(options.map((option) => option.ecosystem)).size, options.length);
  assert.ok(options.every((option) => option.childPull));
});

test("reference app lifecycle keeps authority bands explicit", () => {
  const year = new Date().getFullYear();
  assert.equal(ageBandFor(year - 5), "4-6");
  assert.equal(lifecycleLabel(year - 11), "共选");
  assert.equal(ageBandFor(year - 16), "16+");
});

test("FamilySession persists a versioned choice event through its vault boundary", async () => {
  const state = createInitialFamilyState({ familyName: "合成家庭", caregiverName: "家长", childName: "小见", birthYear: 2016 });
  const writes = [];
  const vault = {
    async save(next) { writes.push(structuredClone(next)); },
    exportEncrypted() { return { format: "synthetic" }; }
  };
  const session = new FamilySession(vault, state);
  await session.chooseOpportunity({
    childId: state.children[0].id,
    sourceEventId: "event-source",
    opportunity: {
      schema: "org.foe.opportunity/v1",
      opportunityId: "nothing:family-choice",
      type: "nothing",
      title: "什么都不做",
      explanation: "保留自由时间",
      reason: "family-choice"
    }
  });
  assert.equal(writes.length, 1);
  assert.equal(writes[0].choices[0].status, "nothing");
  assert.equal(writes[0].events[0].eventType, "opportunity.nothing-chosen");
  assert.equal(writes[0].events[0].eventVersion, 1);
});

test("correction appends a new authored event without overwriting evidence", async () => {
  const state = createInitialFamilyState({ familyName: "合成家庭", caregiverName: "家长", childName: "小见", birthYear: 2016 });
  const writes = [];
  const vault = { async save(next) { writes.push(structuredClone(next)); }, exportEncrypted() { return {}; } };
  const session = new FamilySession(vault, state);
  const observed = await session.recordInterest({
    childId: state.children[0].id,
    expression: "我以为孩子想学赛车物理",
    evidenceKind: "caregiver-observed",
    constraints: {},
    schoolWindow: "物理"
  });
  await session.correctEvidence({
    childId: state.children[0].id,
    targetEventId: observed.eventId,
    correction: "孩子只是想和朋友比赛"
  });
  const finalState = writes.at(-1);
  assert.equal(finalState.events.length, 2);
  assert.equal(finalState.events[0].payload.expression, "我以为孩子想学赛车物理");
  assert.equal(finalState.events[1].eventType, "evidence.corrected");
  assert.equal(finalState.events[1].payload.targetEventId, observed.eventId);
});

test("v1 family state migrates deterministically to separately authored child members", () => {
  const v1 = {
    schema: "org.jianyu.family-vault/v1",
    household: { id: "household-1" },
    members: [{ id: "caregiver-1", role: "caregiver", displayName: "家长" }],
    children: [{ id: "child-1", displayName: "小见", birthYear: 2016, createdAt: "2026-01-01T00:00:00Z" }],
    events: [],
    choices: []
  };
  const first = migrateFamilyState(v1);
  const second = migrateFamilyState(v1);
  assert.equal(first.state.schema, "org.jianyu.family-vault/v2");
  assert.equal(first.state.children[0].memberId, "child-member:child-1");
  assert.deepEqual(first.state, second.state);
});

test("active family member remains the author of new evidence", async () => {
  const state = createInitialFamilyState({ familyName: "合成家庭", caregiverName: "家长", childName: "小见", birthYear: 2016 });
  const writes = [];
  const vault = { async save(next) { writes.push(structuredClone(next)); }, exportEncrypted() { return {}; } };
  const session = new FamilySession(vault, state);
  const childMember = state.members.find((member) => member.role === "child");
  session.setActiveMember(childMember.id);
  await session.recordInterest({
    childId: state.children[0].id,
    expression: "我想继续试试",
    evidenceKind: "child-stated",
    constraints: {},
    schoolWindow: null,
    goals: { caregiver: null, shared: null }
  });
  assert.equal(writes[0].events[0].authorId, childMember.id);
  assert.equal(writes[0].events[0].actorRole, "child");
});

test("child authority permits self-expression but denies household administration", () => {
  const state = createInitialFamilyState({ familyName: "合成家庭", caregiverName: "家长", childName: "小见", birthYear: 2016 });
  const child = state.children[0];
  assert.equal(authorizeFamilyAction(state, child.memberId, "opportunity.veto", child.id).allowed, true);
  assert.equal(authorizeFamilyAction(state, child.memberId, "evidence.correct", child.id).allowed, true);
  assert.equal(authorizeFamilyAction(state, child.memberId, "vault.erase").allowed, false);
  assert.equal(authorizeFamilyAction(state, child.memberId, "member.add").allowed, false);
});
