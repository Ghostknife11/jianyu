import { createFamilyEvent } from "./event-factory.js";
import { assertFamilyAction } from "./authorization.js";

function clone(value) {
  return structuredClone(value);
}

export class FamilySession {
  #vault;
  #state;
  #onChange;
  #activeMemberId;

  constructor(vault, state, onChange = () => {}) {
    this.#vault = vault;
    this.#state = clone(state);
    this.#onChange = onChange;
    this.#activeMemberId = state.members.find((member) => member.role === "caregiver")?.id ?? state.members[0]?.id;
  }

  snapshot() {
    return clone(this.#state);
  }

  encryptedExport() {
    this.assertAllowed("vault.export");
    return this.#vault.exportEncrypted();
  }

  assertAllowed(action, subjectId) {
    return assertFamilyAction(this.#state, this.#activeMemberId, action, subjectId);
  }

  activeMemberId() {
    return this.#activeMemberId;
  }

  setActiveMember(memberId) {
    if (!this.#state.members.some((member) => member.id === memberId)) throw new Error("找不到这个家庭成员");
    this.#activeMemberId = memberId;
  }

  #event(state, eventType, payload, options = {}) {
    return createFamilyEvent(state, eventType, payload, { ...options, authorId: this.#activeMemberId });
  }

  async #commit(nextState) {
    await this.#vault.save(nextState);
    this.#state = nextState;
    this.#onChange(this.snapshot());
  }

  async addChild({ displayName, birthYear }) {
    this.assertAllowed("child.add");
    const next = this.snapshot();
    const child = {
      id: crypto.randomUUID(),
      memberId: crypto.randomUUID(),
      displayName: displayName.trim(),
      birthYear: Number(birthYear),
      createdAt: new Date().toISOString()
    };
    next.children.push(child);
    next.members.push({
      id: child.memberId,
      role: "child",
      subjectId: child.id,
      displayName: child.displayName,
      createdAt: child.createdAt
    });
    next.events.push(this.#event(next, "child.added", {
      displayName: child.displayName,
      birthYear: child.birthYear
    }, { subjectId: child.id }));
    await this.#commit(next);
    return child;
  }

  async addMember({ displayName, role }) {
    this.assertAllowed("member.add");
    const next = this.snapshot();
    const member = {
      id: crypto.randomUUID(),
      role,
      displayName: displayName.trim(),
      createdAt: new Date().toISOString()
    };
    next.members.push(member);
    next.events.push(this.#event(next, "member.added", {
      memberId: member.id,
      displayName: member.displayName,
      role: member.role
    }));
    await this.#commit(next);
    return member;
  }

  async recordInterest({ childId, expression, evidenceKind, constraints, schoolWindow, goals }) {
    this.assertAllowed("interest.record", childId);
    const next = this.snapshot();
    const event = this.#event(next, "interest.observed", {
      expression: expression.trim(),
      evidenceKind,
      constraints,
      schoolWindow: schoolWindow?.trim() || null,
      goals,
      interpretation: null,
      confidence: null
    }, { subjectId: childId });
    next.events.push(event);
    await this.#commit(next);
    return event;
  }

  async chooseOpportunity({ childId, opportunity, sourceEventId }) {
    this.assertAllowed("opportunity.choose", childId);
    const next = this.snapshot();
    const choice = {
      id: crypto.randomUUID(),
      childId,
      opportunity: clone(opportunity),
      sourceEventId,
      status: opportunity.type === "nothing" ? "nothing" : "chosen",
      chosenAt: new Date().toISOString(),
      feedback: null
    };
    next.choices.unshift(choice);
    next.events.push(this.#event(next, opportunity.type === "nothing" ? "opportunity.nothing-chosen" : "opportunity.chosen", {
      choiceId: choice.id,
      opportunityId: opportunity.opportunityId,
      title: opportunity.title,
      ecosystem: opportunity.ecosystem ?? "nothing",
      sourceEventId
    }, { subjectId: childId }));
    await this.#commit(next);
    return choice;
  }

  async vetoOpportunity({ childId, opportunity, sourceEventId }) {
    this.assertAllowed("opportunity.veto", childId);
    const next = this.snapshot();
    const choice = {
      id: crypto.randomUUID(),
      childId,
      opportunity: clone(opportunity),
      sourceEventId,
      status: "child-vetoed",
      chosenAt: new Date().toISOString(),
      feedback: null
    };
    next.choices.unshift(choice);
    next.events.push(this.#event(next, "opportunity.child-vetoed", {
      choiceId: choice.id,
      opportunityId: opportunity.opportunityId,
      title: opportunity.title,
      sourceEventId
    }, { subjectId: childId }));
    await this.#commit(next);
  }

  async correctEvidence({ childId, targetEventId, correction }) {
    this.assertAllowed("evidence.correct", childId);
    const next = this.snapshot();
    const target = next.events.find((event) => event.eventId === targetEventId && event.subjectId === childId);
    if (!target) throw new Error("找不到需要纠正的记录");
    next.events.push(this.#event(next, "evidence.corrected", {
      targetEventId,
      correction: correction.trim(),
      preservesOriginal: true
    }, { subjectId: childId, visibility: "family" }));
    await this.#commit(next);
  }

  async recordFeedback({ choiceId, value }) {
    const next = this.snapshot();
    const choice = next.choices.find((item) => item.id === choiceId);
    if (!choice) throw new Error("找不到这次选择");
    this.assertAllowed("feedback.record", choice.childId);
    choice.feedback = { value, recordedAt: new Date().toISOString() };
    choice.status = "reflected";
    next.events.push(this.#event(next, "opportunity.feedback-recorded", {
      choiceId,
      opportunityId: choice.opportunity.opportunityId,
      value
    }, { subjectId: choice.childId }));
    await this.#commit(next);
  }
}
