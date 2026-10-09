// Tests for the Today flow. The invariants worth protecting are the ones a
// family would notice if they broke: the child's words are written down before
// anything is sent, one search yields at most one durable decision, a refusal is
// the young person's and not a caregiver's skip, and a later view says who
// actually wrote it.

import assert from "node:assert/strict";
import { describe, it, afterEach, beforeEach } from "node:test";
import { NasApi } from "../../apps/jianyu-web-nas/web/src/session.js";
import { NasFamilyVault } from "../../apps/jianyu-web-nas/web/src/nas-vault.js";
import {
  AuthorizationError,
  assertFamilyAction,
  authorizeFamilyAction
} from "../../apps/jianyu-web-nas/web/src/authorization.js";
import {
  TodayError,
  buildTodayRequest,
  laterViewLabel,
  offersLaterView,
  recordChoice,
  recordInterest,
  recordLaterView,
  recordVeto,
  signsOwnDecisions
} from "../../apps/jianyu-web-nas/web/src/today-service.js";
import { startServer, stopServer, createCookieFetch } from "./helpers.mjs";

const PASSPHRASE = "family-passphrase-2026";

function yearsAgo(years) {
  const date = new Date();
  date.setUTCFullYear(date.getUTCFullYear() - years);
  return date.toISOString().slice(0, 10);
}

function door(overrides = {}) {
  return {
    schema: "org.foe.opportunity/v1",
    opportunityId: "door-1",
    title: "一起拆开一只旧轮胎看看花纹",
    ecosystem: "making",
    entryPoint: { motivation: "child-current-pull", whyNow: "孩子自己在问抓地这件事", startupCost: "low" },
    goalAlignment: { primary: "child" },
    childPull: true,
    requirements: { timeMinutes: 45, costBand: "free-existing", caregiverEnergy: "low", travelMinutes: 0 },
    source: { kind: "pack", publisher: "org.jianyu.web.offline-demo", retrievedAt: new Date().toISOString() },
    verification: "idea",
    risks: [],
    score: null,
    explanation: "用家里已有的东西讲清一个真问题。",
    ...overrides
  };
}

const NOTHING = Object.freeze({
  schema: "org.foe.opportunity/v1",
  opportunityId: "nothing:family-choice",
  type: "nothing",
  title: "什么都不做",
  explanation: "今天不需要把兴趣变成安排。保留自由时间也是有效选择。",
  reason: "family-choice"
});

describe("authorization", () => {
  const state = {
    household: { id: "household-1" },
    members: [
      { id: "caregiver-1", role: "caregiver", displayName: "妈妈" },
      { id: "observer-1", role: "observer", displayName: "舅舅" },
      { id: "child-member-1", role: "child", subjectId: "child-1", displayName: "小隅" },
      { id: "child-member-2", role: "child", subjectId: "child-2", displayName: "大宝" }
    ]
  };

  it("lets a caregiver act for the household but a child only for themselves", () => {
    assert.deepEqual(authorizeFamilyAction(state, "caregiver-1", "opportunity.choose", "child-1").reason, "household-admin");
    assert.deepEqual(authorizeFamilyAction(state, "child-member-1", "opportunity.choose", "child-1").reason, "child-self-action");
    assert.equal(authorizeFamilyAction(state, "child-member-1", "opportunity.choose", "child-2").allowed, false);
    assert.equal(authorizeFamilyAction(state, "child-member-1", "member.add").allowed, false);
  });

  it("lets an observer add what they saw and nothing else", () => {
    assert.equal(authorizeFamilyAction(state, "observer-1", "interest.record", null).allowed, true);
    assert.equal(authorizeFamilyAction(state, "observer-1", "opportunity.choose", "child-1").allowed, false);
    assert.equal(authorizeFamilyAction(state, "observer-1", "feedback.record", "child-1").allowed, false);
  });

  it("refuses an unknown member rather than falling back to the first one", () => {
    assert.equal(authorizeFamilyAction(state, "nobody", "interest.record", null).reason, "unknown-actor");
    assert.throws(() => assertFamilyAction(state, "nobody", "interest.record", null), AuthorizationError);
  });
});

describe("today flow", () => {
  let instance;

  beforeEach(async () => {
    instance = await startServer();
  });

  afterEach(async () => {
    await stopServer(instance);
  });

  async function vaultWith(birthDate) {
    const store = new Map();
    const vault = await NasFamilyVault.create({
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
    return vault;
  }

  it("records the child's words and the approved scope before anything is sent", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const child = vault.state.children[0];
    const caregiver = vault.state.members.find((member) => member.role === "caregiver");

    const event = recordInterest(vault, {
      childId: child.id,
      expression: "  孩子最近主动说自己很喜欢赛车，想弄明白轮胎为什么能抓地  ",
      authorId: caregiver.id,
      approvedCategories: ["current-interest", "age-band"],
      sourceKind: "byok-ai"
    });

    assert.equal(vault.state.events.length, 1, "the words are in the vault before any call");
    assert.equal(event.eventType, "interest.observed");
    assert.equal(event.subjectId, child.id);
    assert.equal(event.payload.expression, "孩子最近主动说自己很喜欢赛车，想弄明白轮胎为什么能抓地");
    assert.deepEqual(event.payload.approvedCategories, ["current-interest", "age-band"]);
    assert.equal(event.payload.sourceKind, "byok-ai");
    // The record holds the scope, never the payload a provider would receive.
    assert.equal(JSON.stringify(event.payload).includes("taskContext"), false);
  });

  it("refuses an empty expression instead of inventing an entry point", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const caregiver = vault.state.members.find((member) => member.role === "caregiver");
    assert.throws(() => recordInterest(vault, {
      childId: vault.state.children[0].id,
      expression: "   ",
      authorId: caregiver.id
    }), /请先写一句/);
  });

  it("refuses a signature that may not act for this child", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const observer = vault.state.members.find((member) => member.role === "observer")
      ?? { id: "observer-1" };
    assert.throws(() => recordInterest(vault, {
      childId: vault.state.children[0].id,
      expression: "孩子说想研究赛车",
      authorId: observer.id
    }), AuthorizationError);
  });

  it("lets one search produce exactly one durable decision", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const caregiver = vault.state.members.find((member) => member.role === "caregiver");
    const child = vault.state.children[0];
    const sourceEventId = "interest-event-1";

    const first = recordChoice(vault, { childId: child.id, opportunity: door(), sourceEventId, authorId: caregiver.id });
    assert.equal(first.status, "chosen");
    assert.equal(vault.state.choices.length, 1);

    assert.throws(() => recordChoice(vault, {
      childId: child.id,
      opportunity: door({ opportunityId: "door-2", title: "另一扇门" }),
      sourceEventId,
      authorId: caregiver.id
    }), /已经有过一个决定/);

    // A fresh search is a fresh decision.
    const second = recordChoice(vault, {
      childId: child.id,
      opportunity: door({ opportunityId: "door-3", title: "再一次" }),
      sourceEventId: "interest-event-2",
      authorId: caregiver.id
    });
    assert.equal(vault.state.choices.length, 2);
    assert.equal(second.opportunity.title, "再一次");
  });

  it("records 留白 as a choice, not as a missing one", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const caregiver = vault.state.members.find((member) => member.role === "caregiver");
    const child = vault.state.children[0];

    const choice = recordChoice(vault, { childId: child.id, opportunity: NOTHING, sourceEventId: "e1", authorId: caregiver.id });
    assert.equal(choice.status, "nothing");
    const event = vault.state.events.at(-1);
    assert.equal(event.eventType, "opportunity.nothing-chosen");
    assert.equal(event.payload.title, "什么都不做");
    assert.equal(event.payload.ecosystem, "nothing");
  });

  it("writes a young person's refusal as their own durable evidence", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const child = vault.state.children[0];
    const childMember = vault.state.members.find((member) => member.subjectId === child.id);

    const veto = recordVeto(vault, {
      childId: child.id,
      opportunity: door(),
      sourceEventId: "e1",
      authorId: childMember.id
    });
    assert.equal(veto.status, "child-vetoed");
    assert.equal(veto.decidedBy, "child-veto");
    assert.equal(veto.witnessedByCaregiver, false);
    const event = vault.state.events.at(-1);
    assert.equal(event.eventType, "opportunity.child-vetoed");
    assert.equal(event.actorRole, "child", "the refusal keeps the young person as its author");
    assert.equal(event.subjectId, child.id);
  });

  it("labels a caregiver's witnessed refusal as witnessed, not as the child's own", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const child = vault.state.children[0];
    const caregiver = vault.state.members.find((member) => member.role === "caregiver");

    const veto = recordVeto(vault, {
      childId: child.id,
      opportunity: door(),
      sourceEventId: "e1",
      authorId: caregiver.id
    });
    assert.equal(veto.witnessedByCaregiver, true);
    assert.equal(veto.decidedBy, "caregiver-witnessed");
    assert.equal(vault.state.events.at(-1).actorRole, "caregiver");
    assert.equal(vault.state.events.at(-1).payload.witnessedByCaregiver, true);
  });

  it("labels a later view by who actually wrote it", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const caregiver = vault.state.members.find((member) => member.role === "caregiver");
    const child = vault.state.children[0];
    const choice = recordChoice(vault, { childId: child.id, opportunity: door(), sourceEventId: "e1", authorId: caregiver.id });

    const withView = recordLaterView(vault, { choiceId: choice.id, value: "后来孩子自己又去看了轮胎花纹", authorId: caregiver.id });
    assert.equal(withView.status, "reflected");
    assert.equal(withView.feedback.relayedByCaregiver, true);
    const event = vault.state.events.at(-1);
    assert.equal(event.payload.authoredByChild, false);
    assert.equal(event.payload.relayedByCaregiver, true);

    assert.throws(() => recordLaterView(vault, { choiceId: choice.id, value: "再写一次", authorId: caregiver.id }), /已经留下过看法/);
  });

  it("lets a 14-year-old sign their own view and refuses a caregiver's relay", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const teenVault = await vaultWith(yearsAgo(14));
    const child = teenVault.state.children[0];
    const childMember = teenVault.state.members.find((member) => member.subjectId === child.id);
    const caregiver = teenVault.state.members.find((member) => member.role === "caregiver");

    assert.equal(signsOwnDecisions(child), true);
    assert.equal(laterViewLabel(child), "我想留个看法");

    const choice = recordChoice(teenVault, { childId: child.id, opportunity: door(), sourceEventId: "e1", authorId: childMember.id });
    assert.throws(() => recordLaterView(teenVault, { choiceId: choice.id, value: "我觉得挺有意思", authorId: caregiver.id, relayedByCaregiver: true }), /由本人自己留下看法/);

    const withView = recordLaterView(teenVault, { choiceId: choice.id, value: "我觉得挺有意思", authorId: childMember.id });
    assert.equal(withView.feedback.relayedByCaregiver, false);
    assert.equal(teenVault.state.events.at(-1).visibility, "family");
  });

  it("offers no new childhood view at 16+", async () => {
    const vault = await vaultWith(yearsAgo(17));
    const child = vault.state.children[0];
    assert.equal(offersLaterView(child), false);
    assert.equal(signsOwnDecisions(child), true);
  });

  it("builds a request the engine accepts and refuses an unusable child", async () => {
    const vault = await vaultWith(yearsAgo(8));
    const child = vault.state.children[0];
    const request = buildTodayRequest(child, {
      interest: "孩子说自己喜欢赛车",
      constraints: { timeMinutes: 90 },
      goals: [{ owner: "shared", value: "周末一起做点事" }]
    });
    assert.equal(request.purpose, "find-family-opportunities");
    assert.equal(request.ageBand, "7-9");
    assert.equal(request.constraints.timeMinutes, 90);
    assert.deepEqual(request.goals, [{ owner: "shared", value: "周末一起做点事" }]);

    const young = await vaultWith(yearsAgo(2));
    assert.throws(() => buildTodayRequest(young.state.children[0], { interest: "呀呀" }), TodayError);
  });
});
