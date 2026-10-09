// The Today flow: what a family can decide, and what gets written down.
//
// Everything here runs against the unlocked vault in the browser and is saved by
// the caller. The order of the steps is the product: the child's words and the
// scope of any outgoing call are written to the encrypted vault *before* a
// provider is contacted, so a failed write means no call happens at all
// (docs/UI-SYSTEM.md §197). One discovery yields at most one durable decision —
// a door, 留白, or a confirmed veto — and a later view is optional, never owed.

import { assertFamilyAction, AuthorizationError } from "./authorization.js";
import {
  appendEvent,
  childLifecycle,
  createFamilyEvent
} from "./family-state.js";
import { ageBandFor, buildDiscoveryRequest, DiscoveryError } from "./discovery-service.js";
export class TodayError extends Error {
  constructor(message) {
    super(message);
    this.name = "TodayError";
  }
}

const MAX_EXPRESSION = 800;

// The lifecycle band whose holder is addressed directly and signs their own
// decisions. Below it a caregiver may relay a view the young person expressed.
const SELF_SIGNING_STAGES = new Set(["hand-over", "graduation"]);

/** A young person at this stage signs their own choice and later view. */
export function signsOwnDecisions(child, today = new Date()) {
  const { stage } = childLifecycle(child, today);
  return stage !== null && SELF_SIGNING_STAGES.has(stage);
}

function boundedText(value, maximum) {
  if (typeof value !== "string") return "";
  return value.trim().slice(0, maximum);
}

/**
 * Records the child's own words. This is the entry point of the whole flow, so
 * an empty expression is refused rather than filled in with a guess.
 */
export function recordInterest(vault, {
  childId,
  expression,
  authorId,
  evidenceKind = "caregiver-relayed",
  constraints,
  schoolWindow = "",
  goals = [],
  approvedCategories = [],
  sourceKind = "offline-demo"
}) {
  const state = vault.state;
  const child = state.children.find((item) => item.id === childId);
  if (!child) throw new TodayError("请先选择一个孩子");
  assertFamilyAction(state, authorId, "interest.record", childId);

  const text = boundedText(expression, MAX_EXPRESSION);
  if (text === "") throw new TodayError("请先写一句孩子当下在意的内容");

  const event = createFamilyEvent(state, "interest.observed", {
    expression: text,
    evidenceKind: boundedText(evidenceKind, 40) || "caregiver-relayed",
    constraints: constraints ?? null,
    schoolWindow: boundedText(schoolWindow, 200) || null,
    goals: Array.isArray(goals) ? goals.slice(0, 3) : [],
    // What the family approved for this attempt, before anything is sent. The
    // categories are recorded; the payload itself is never stored here.
    approvedCategories: Array.isArray(approvedCategories) ? approvedCategories.slice(0, 8) : [],
    sourceKind: boundedText(sourceKind, 40) || "offline-demo",
    interpretation: null,
    confidence: null
  }, { authorId, subjectId: childId, visibility: "guardians" });
  appendEvent(state, event);
  return event;
}

/**
 * Builds the discovery request for one child. The words come from the family's
 * own input, never from a stored interpretation: the engine starts from what the
 * child said this time.
 */
export function buildTodayRequest(child, { interest, constraints, goals = [], recentEvidence = [], schoolWindow = [], worldQuery }) {
  try {
    return buildDiscoveryRequest({ child, interest, constraints, goals, recentEvidence, schoolWindow, worldQuery });
  } catch (error) {
    if (error instanceof DiscoveryError) throw new TodayError(error.message);
    throw error;
  }
}

function findChoice(state, choiceId) {
  return state.choices.find((item) => item.id === choiceId) ?? null;
}

/**
 * Who the record says made this decision: the young person themselves, or the
 * family together. Derived from the author, so the caller cannot claim the
 * child decided something they did not.
 */
function decisionMaker(state, authorId, child) {
  const author = state.members.find((member) => member.id === authorId);
  return author?.role === "child" && author.subjectId === child.id ? "child" : "family";
}

function assertOneDecision(state, sourceEventId) {
  if (typeof sourceEventId !== "string" || sourceEventId === "") return;
  if (state.choices.some((item) => item.sourceEventId === sourceEventId)) {
    throw new TodayError("这次寻找已经有过一个决定了，请重新开始一次");
  }
}

/**
 * Writes the family's decision. A door and 留白 are the same kind of record:
 * both are a choice the family made, and neither is owed, ranked, or scored.
 * `decidedBy` is derived from who is holding the device, never claimed by the
 * caller, so a caregiver's tap cannot be written as the child's own decision.
 */
export function recordChoice(vault, { childId, opportunity, sourceEventId, authorId }) {
  const state = vault.state;
  const child = state.children.find((item) => item.id === childId);
  if (!child) throw new TodayError("请先选择一个孩子");
  if (!opportunity || typeof opportunity.title !== "string" || opportunity.title === "") {
    throw new TodayError("这个入口已经失效，请重新寻找");
  }
  assertFamilyAction(state, authorId, "opportunity.choose", childId);
  assertOneDecision(state, sourceEventId);

  const nothing = opportunity.type === "nothing";
  const choice = {
    id: globalThis.crypto.randomUUID(),
    childId,
    opportunity: structuredClone(opportunity),
    sourceEventId: sourceEventId ?? null,
    status: nothing ? "nothing" : "chosen",
    decidedBy: decisionMaker(state, authorId, child),
    chosenAt: new Date().toISOString(),
    feedback: null
  };
  state.choices.unshift(choice);
  appendEvent(state, createFamilyEvent(state, nothing ? "opportunity.nothing-chosen" : "opportunity.chosen", {
    choiceId: choice.id,
    opportunityId: opportunity.opportunityId,
    title: opportunity.title,
    ecosystem: opportunity.ecosystem ?? "nothing",
    sourceEventId: sourceEventId ?? null,
    decidedBy: choice.decidedBy
  }, { authorId, subjectId: childId }));
  return choice;
}

/**
 * A young person's explicit refusal. It is durable negative evidence about that
 * person, which is a different thing from a caregiver deciding not to bother
 * with an option, and it must never be written as a score. A caregiver who
 * watched the child refuse may record it, and the record says so rather than
 * implying the child tapped it themselves.
 */
export function recordVeto(vault, { childId, opportunity, sourceEventId, authorId }) {
  const state = vault.state;
  const child = state.children.find((item) => item.id === childId);
  if (!child) throw new TodayError("请先选择一个孩子");
  if (!opportunity || typeof opportunity.title !== "string" || opportunity.title === "") {
    throw new TodayError("这个入口已经失效，请重新寻找");
  }
  assertFamilyAction(state, authorId, "opportunity.veto", childId);
  assertOneDecision(state, sourceEventId);

  const author = state.members.find((member) => member.id === authorId);
  const witnessedByCaregiver = author.role !== "child";
  const choice = {
    id: globalThis.crypto.randomUUID(),
    childId,
    opportunity: structuredClone(opportunity),
    sourceEventId: sourceEventId ?? null,
    status: "child-vetoed",
    decidedBy: witnessedByCaregiver ? "caregiver-witnessed" : "child-veto",
    witnessedByCaregiver,
    chosenAt: new Date().toISOString(),
    feedback: null
  };
  state.choices.unshift(choice);
  appendEvent(state, createFamilyEvent(state, "opportunity.child-vetoed", {
    choiceId: choice.id,
    opportunityId: opportunity.opportunityId,
    title: opportunity.title,
    sourceEventId: sourceEventId ?? null,
    witnessedByCaregiver
  }, { authorId, subjectId: childId }));
  return choice;
}

/**
 * The optional later view. At 13+ the young person signs it themselves; below
 * that a caregiver may relay what the child said and the record says so. At 16+
 * nothing new is appended about childhood, so the caller must not offer it.
 */
export function recordLaterView(vault, { choiceId, value, authorId, relayedByCaregiver = false }) {
  const state = vault.state;
  const choice = findChoice(state, choiceId);
  if (!choice) throw new TodayError("找不到这次选择");
  if (choice.feedback !== null && choice.feedback !== undefined) {
    throw new TodayError("这次选择已经留下过看法了");
  }
  const child = state.children.find((item) => item.id === choice.childId);
  if (!child) throw new TodayError("找不到这个选择对应的孩子");

  const selfSigned = signsOwnDecisions(child);
  if (selfSigned && relayedByCaregiver) {
    throw new TodayError("这个阶段由本人自己留下看法，不需要别人代记");
  }
  assertFamilyAction(state, authorId, "feedback.record", choice.childId);

  const text = boundedText(value, 500);
  if (text === "") throw new TodayError("请先写一句当下的看法，也可以什么都不写");

  const authoredByChild = selfSigned;
  const feedback = {
    value: text,
    recordedAt: new Date().toISOString(),
    // A caregiver-relayed view is labelled as such rather than presented as the
    // child's own words.
    relayedByCaregiver: !authoredByChild
  };
  choice.feedback = feedback;
  choice.status = "reflected";
  appendEvent(state, createFamilyEvent(state, "opportunity.feedback-recorded", {
    choiceId: choice.id,
    opportunityId: choice.opportunity.opportunityId,
    value: text,
    authoredByChild,
    relayedByCaregiver: !authoredByChild
  }, { authorId, subjectId: choice.childId, visibility: selfSigned ? "family" : "guardians" }));
  return choice;
}

/** The label for the optional-view control, which changes with the stage. */
export function laterViewLabel(child, today = new Date()) {
  return signsOwnDecisions(child, today) ? "我想留个看法" : "代记孩子的看法";
}

/** True when this stage appends no new view about childhood at all. */
export function offersLaterView(child, today = new Date()) {
  const { stage } = childLifecycle(child, today);
  return stage !== "graduation";
}

/** Throws when a caller reaches for an action the current author may not take. */
export { AuthorizationError };

/** Re-exported so the UI can show a stage's band without importing two modules. */
export { ageBandFor };
