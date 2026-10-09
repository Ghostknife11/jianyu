// The 16+ hand-over. These tests hold the two rights apart — a portable copy of
// the person's own records, and a separately confirmed decision about what the
// household keeps — because collapsing them is the failure ADR 0007 and ADR 0010
// were written to prevent.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { mergeFamilyState } from "../../packages/foe-vault/src/index.js";
import { assertEvent } from "../../packages/foe-schema/src/index.js";
import { base64UrlFromBytes } from "../../apps/jianyu-web-nas/web/src/nas-crypto.js";
import { createInitialFamilyState } from "../../apps/jianyu-web-nas/web/src/family-state.js";
import {
  RETENTION_CONFIRMATION_PHRASE,
  RETENTION_OUTCOMES,
  applyRetentionOutcome,
  buildGraduationArchive,
  consentIsCurrent,
  createRetentionConsent,
  graduatedSubject,
  GraduationError,
  openGraduationBundle,
  renderGraduationArchiveMarkdown,
  sealGraduationBundle,
  subjectAfterOutcome
} from "../../apps/jianyu-web-nas/web/src/graduation-service.js";
import { projectSharedTimeline } from "../../apps/jianyu-web-nas/web/src/timeline-service.js";

/** A household with a 16+ subject, a younger sibling, and a caregiver. */
function graduatedHousehold() {
  const state = createInitialFamilyState({ familyName: "隅之家", caregiverName: "林晓" });
  const caregiver = state.members[0];
  const graduateId = "child-graduate";
  const graduateMemberId = "member-graduate";
  const siblingId = "child-sibling";
  const siblingMemberId = "member-sibling";
  state.children.push(
    { id: graduateId, memberId: graduateMemberId, displayName: "阿隅", birthDate: "2008-04-02", birthYear: 2008, createdAt: "2020-01-01T00:00:00.000Z" },
    { id: siblingId, memberId: siblingMemberId, displayName: "小隅", birthDate: "2013-09-15", birthYear: 2013, createdAt: "2020-01-01T00:00:00.000Z" }
  );
  state.members.push(
    { id: graduateMemberId, role: "child", subjectId: graduateId, displayName: "阿隅", createdAt: "2020-01-01T00:00:00.000Z" },
    { id: siblingMemberId, role: "child", subjectId: siblingId, displayName: "小隅", createdAt: "2020-01-01T00:00:00.000Z" }
  );
  return { state, caregiver, graduateId, graduateMemberId, siblingId, siblingMemberId };
}

function event(state, { eventType, payload, subjectId, authorId, visibility = "family" }) {
  const timestamp = "2026-01-05T09:00:00.000Z";
  return assertEvent({
    schema: "org.foe.event/v1",
    eventId: `event-${eventType}-${subjectId}-${state.events.length}`,
    eventType,
    eventVersion: 1,
    householdId: state.household.id,
    authorId,
    actorRole: state.members.find((member) => member.id === authorId)?.role ?? "child",
    subjectId,
    deviceId: "test-device",
    occurredAt: timestamp,
    recordedAt: timestamp,
    visibility,
    payload
  });
}

describe("graduation hand-over", () => {
  it("hands nothing over below 16", () => {
    const { state, siblingId } = graduatedHousehold();
    assert.equal(graduatedSubject(state, siblingId), null);
    assert.throws(() => buildGraduationArchive(state, siblingId), GraduationError);
    assert.throws(() => buildGraduationArchive(state, "not-a-child"), GraduationError);
  });

  it("hands nothing over when the birth date is unusable", () => {
    const state = createInitialFamilyState({ familyName: "隅之家", caregiverName: "林晓" });
    state.children.push({ id: "child-x", memberId: "member-x", displayName: "阿隅", createdAt: "2020-01-01T00:00:00.000Z" });
    state.members.push({ id: "member-x", role: "child", subjectId: "child-x", displayName: "阿隅", createdAt: "2020-01-01T00:00:00.000Z" });
    assert.equal(graduatedSubject(state, "child-x"), null);
    assert.throws(() => buildGraduationArchive(state, "child-x"), GraduationError);
  });

  it("refuses an archive when no child-role member is linked to the subject", () => {
    const { state, graduateId, graduateMemberId } = graduatedHousehold();
    state.members = state.members.filter((member) => member.id !== graduateMemberId);
    assert.throws(() => buildGraduationArchive(state, graduateId), /家庭成员关系/u);
  });

  it("archives only this person's records", () => {
    const { state, caregiver, graduateId, graduateMemberId, siblingId, siblingMemberId } = graduatedHousehold();
    state.evidence.push(
      { id: "evidence-1", childId: graduateId, kind: "interest", expression: "我想弄明白轮胎为什么能抓地", recordedAt: "2026-01-05T09:00:00.000Z" },
      { id: "evidence-2", childId: siblingId, kind: "interest", expression: "我喜欢恐龙", recordedAt: "2026-01-05T09:00:00.000Z" }
    );
    state.choices.push(
      { id: "choice-1", childId: graduateId, status: "chosen", chosenAt: "2026-01-06T10:00:00.000Z", opportunity: { opportunityId: "o-1", title: "拆一只旧轮胎" } },
      { id: "choice-2", childId: siblingId, status: "nothing", chosenAt: "2026-01-06T10:00:00.000Z", opportunity: { opportunityId: "o-2", title: "去公园" } }
    );
    state.events.push(
      event(state, { eventType: "interest.observed", payload: { expression: "我想弄明白轮胎为什么能抓地" }, subjectId: graduateId, authorId: caregiver.id }),
      event(state, { eventType: "interest.observed", payload: { expression: "我喜欢恐龙" }, subjectId: siblingId, authorId: caregiver.id })
    );

    const archive = buildGraduationArchive(state, graduateId);
    assert.equal(archive.schema, "org.foe.graduation-archive/v2");
    assert.equal(archive.subject.displayName, "阿隅");
    assert.equal(archive.subject.subjectId, graduateId);
    // The author table keeps roles without copying anyone else's display name.
    assert.deepEqual(
      [...archive.authors].map((author) => `${author.role}:${author.memberId}`).sort(),
      [`caregiver:${caregiver.id}`, `child:${graduateMemberId}`]
    );
    assert.equal(JSON.stringify(archive.authors).includes("林晓"), false, "no display name is copied in");
    assert.equal(archive.evidence.length, 1);
    assert.equal(archive.evidence[0].id, "evidence-1");
    assert.equal(archive.choices.length, 1);
    assert.equal(archive.choices[0].id, "choice-1");
    assert.equal(archive.events.length, 1);
    assert.equal(archive.events[0].subjectId, graduateId);
    for (const text of ["小隅", "evidence-2", "choice-2", "我喜欢恐龙"]) {
      assert.ok(!JSON.stringify(archive).includes(text), `the archive must not contain ${text}`);
    }
    // The canonical archive carries its own readable view, and the subject block
    // names this person and nobody else. (Included events keep their own
    // household envelope field; the sealed envelope outside reveals none of it.)
    assert.match(archive.humanReadableMarkdown, /这份导出是记录，不是评价/u);
    assert.deepEqual(Object.keys(archive.subject),
      ["subjectId", "memberId", "displayName", "birthYear", "birthDate"]);
    assert.equal(archive.subject.birthYear, 2008);
    assert.equal(archive.subject.birthDate, "2008-04-02");
    assert.equal(JSON.stringify(archive).includes(siblingMemberId), false, "no sibling identity is carried");
  });

  it("keeps authorship inside an included record", () => {
    const { state, caregiver, graduateId, graduateMemberId } = graduatedHousehold();
    state.events.push(
      event(state, { eventType: "evidence.corrected", payload: { correction: "我其实是说轮胎花纹" }, subjectId: graduateId, authorId: graduateMemberId })
    );
    const archive = buildGraduationArchive(state, graduateId);
    assert.equal(archive.events[0].authorId, graduateMemberId);
    const markdown = renderGraduationArchiveMarkdown(archive);
    assert.match(markdown, /本人记录/u);
    // A caregiver's own record keeps naming the caregiver rather than the person.
    state.events.push(
      event(state, { eventType: "interest.observed", payload: { expression: "家长记的一句" }, subjectId: graduateId, authorId: caregiver.id })
    );
    const withCaregiver = renderGraduationArchiveMarkdown(buildGraduationArchive(state, graduateId));
    assert.match(withCaregiver, /caregiver记录/u);
    assert.match(withCaregiver, /不包含任何关于能力、潜力或发展的评分/u);
  });

  it("seals an archive under a key the bundle never contains", async () => {
    const { state, graduateId } = graduatedHousehold();
    state.evidence.push({ id: "evidence-1", childId: graduateId, kind: "interest", expression: "我想弄明白轮胎为什么能抓地", recordedAt: "2026-01-05T09:00:00.000Z" });
    const archive = buildGraduationArchive(state, graduateId);
    const { bundle, recoveryKey } = await sealGraduationBundle(archive);

    assert.equal(bundle.format, "org.foe.encrypted-graduation-bundle/v1");
    assert.equal(bundle.cipher, "AES-256-GCM");
    assert.equal(bundle.bundleId, archive.archiveId, "the archive ID is the bundle ID");
    assert.equal(bundle.createdAt, archive.createdAt);
    for (const secret of [recoveryKey, "阿隅", "轮胎", graduateId, state.household.id]) {
      assert.ok(!JSON.stringify(bundle).includes(secret), `the bundle must not contain ${secret}`);
    }
    // base64url without padding, which is what the canonical envelope uses.
    for (const field of ["nonce", "ciphertext", "recoveryKey"]) {
      const value = field === "recoveryKey" ? recoveryKey : bundle[field];
      assert.match(value, /^[A-Za-z0-9_-]+$/u, `${field} must be unpadded base64url`);
      assert.equal(value.includes("="), false);
    }
    const opened = await openGraduationBundle(bundle, recoveryKey);
    assert.equal(opened.archiveId, archive.archiveId);
    assert.equal(opened.schema, "org.foe.graduation-archive/v2");
    assert.equal(opened.evidence[0].expression, "我想弄明白轮胎为什么能抓地");

    await assert.rejects(() => openGraduationBundle(bundle, Buffer.alloc(32).toString("base64")), GraduationError);
    // A well-formed but wrong key is an authentication failure, not a parse one.
    await assert.rejects(
      () => openGraduationBundle(bundle, base64UrlFromBytes(Buffer.alloc(32))),
      /密钥不正确|篡改/u);
    await assert.rejects(() => openGraduationBundle({ ...bundle, bundleId: "other" }, recoveryKey), GraduationError);
    await assert.rejects(() => openGraduationBundle({ format: "something-else" }, recoveryKey), GraduationError);
    // The AAD binds the envelope time, so a rewritten envelope cannot be opened.
    await assert.rejects(() => openGraduationBundle({ ...bundle, createdAt: "2020-01-01T00:00:00.000Z" }, recoveryKey), GraduationError);
    // An archive that does not match its own envelope fails closed too.
    await assert.rejects(() => openGraduationBundle({ ...bundle, bundleId: globalThis.crypto.randomUUID() }, recoveryKey), GraduationError);
    await assert.rejects(() => openGraduationBundle({ ...bundle, nonce: "not-base64url!" }, recoveryKey), GraduationError);
    await assert.rejects(() => openGraduationBundle({ ...bundle, cipher: "AES-128-GCM" }, recoveryKey), GraduationError);
  });

  it("refuses a consent from anyone but the person", () => {
    const { state, caregiver, graduateId, graduateMemberId } = graduatedHousehold();
    const ask = (authorId, phrase = RETENTION_CONFIRMATION_PHRASE) =>
      createRetentionConsent(state, { childId: graduateId, authorId, outcome: RETENTION_OUTCOMES.relationshipOnly, phrase });

    assert.equal(ask(graduateMemberId).memberId, graduateMemberId);
    assert.equal(ask(graduateMemberId).householdId, state.household.id);
    assert.throws(() => ask(caregiver.id), (error) => error instanceof GraduationError && /本人/u.test(error.message));
    assert.throws(() => ask(graduateMemberId, ""), /确认语/u);
    assert.throws(() => ask(graduateMemberId, "我确认删除"), /确认语/u);
    // The read-only default needs no confirmation at all.
    assert.throws(() => createRetentionConsent(state, {
      childId: graduateId, authorId: graduateMemberId, outcome: RETENTION_OUTCOMES.readOnly, phrase: RETENTION_CONFIRMATION_PHRASE
    }), /只读/u);
  });

  it("expires a consent after five minutes", () => {
    const { state, graduateId, graduateMemberId } = graduatedHousehold();
    const consent = createRetentionConsent(state, {
      childId: graduateId, authorId: graduateMemberId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, phrase: RETENTION_CONFIRMATION_PHRASE
    });
    assert.equal(consentIsCurrent(consent), true);
    assert.equal(consentIsCurrent({ ...consent, expiresAt: Date.now() - 1 }), false);
    assert.equal(consentIsCurrent(null), false);
    assert.throws(() => applyRetentionOutcome(state, {
      childId: graduateId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, consent: { ...consent, expiresAt: Date.now() - 1 }
    }), /过期/u);
  });

  it("keeps the read-only default a no-action outcome", () => {
    const { state, graduateId } = graduatedHousehold();
    state.evidence.push({ id: "evidence-1", childId: graduateId, kind: "interest", expression: "一句话", recordedAt: "2026-01-05T09:00:00.000Z" });
    const before = JSON.stringify(state);
    assert.equal(applyRetentionOutcome(state, { childId: graduateId, outcome: RETENTION_OUTCOMES.readOnly }), null);
    assert.equal(JSON.stringify(state), before, "read-only must not touch the state at all");
    assert.equal(subjectAfterOutcome(state, graduateId).retained, "records");
  });

  it("relationship-only keeps the person and drops their history", () => {
    const { state, caregiver, graduateId, graduateMemberId, siblingId } = graduatedHousehold();
    state.evidence.push({ id: "evidence-1", childId: graduateId, kind: "interest", expression: "我想弄明白轮胎为什么能抓地", recordedAt: "2026-01-05T09:00:00.000Z" });
    state.hypotheses.push({ id: "hypothesis-1", childId: graduateId, statement: "喜欢机械", recordedAt: "2026-01-05T09:00:00.000Z" });
    state.choices.push({ id: "choice-1", childId: graduateId, status: "chosen", chosenAt: "2026-01-06T10:00:00.000Z", opportunity: { opportunityId: "o-1", title: "拆一只旧轮胎" } });
    state.events.push(
      event(state, { eventType: "interest.observed", payload: { expression: "我想弄明白轮胎为什么能抓地" }, subjectId: graduateId, authorId: caregiver.id }),
      event(state, { eventType: "interest.observed", payload: { expression: "我喜欢恐龙" }, subjectId: siblingId, authorId: caregiver.id })
    );

    const consent = createRetentionConsent(state, {
      childId: graduateId, authorId: graduateMemberId, outcome: RETENTION_OUTCOMES.relationshipOnly, phrase: RETENTION_CONFIRMATION_PHRASE
    });
    const tombstone = applyRetentionOutcome(state, { childId: graduateId, outcome: RETENTION_OUTCOMES.relationshipOnly, consent });
    assert.equal(tombstone.targetType, "SUBJECT_CONTENT");
    assert.equal(tombstone.schema, "org.foe.deletion-tombstone/v2");
    assert.equal(tombstone.subjectId, tombstone.targetId);
    assert.equal(tombstone.authorId, graduateMemberId);
    assert.equal(tombstone.reasonCode, "graduation-relationship-only");

    assert.equal(subjectAfterOutcome(state, graduateId).retained, "relationship");
    assert.ok(state.children.some((child) => child.id === graduateId), "the relationship stays");
    assert.ok(state.members.some((member) => member.id === graduateMemberId), "the person stays a member");
    assert.equal(state.tombstones.length, 1);
    // The live state is reduced at once, not only after a merge.
    assert.equal(state.evidence.length, 0);
    assert.equal(state.hypotheses.length, 0);
    assert.equal(state.choices.length, 0);
    assert.equal(state.events.length, 1, "only the sibling's event remains in the live state");

    // The public merger agrees, and the sibling is untouched.
    const merged = mergeFamilyState(state, structuredClone(state));
    assert.equal(merged.evidence.length, 0);
    assert.equal(merged.hypotheses.length, 0);
    assert.equal(merged.choices.length, 0);
    assert.equal(merged.events.length, 1, "only the sibling's event survives");
    assert.equal(merged.events[0].subjectId, siblingId);

    // And so does the shared screen, which must not resurrect it either. The
    // sibling's own record stays: only this person's history is dropped.
    const view = projectSharedTimeline(state);
    assert.equal(view.entries.filter((entry) => entry.childId === graduateId).length, 0);
    assert.equal(view.entries.filter((entry) => entry.childId === siblingId).length, 1);
    assert.equal(view.hiddenRestricted, false);
  });

  it("deleting the household subject copy removes the relationship too", () => {
    const { state, caregiver, graduateId, graduateMemberId, siblingId } = graduatedHousehold();
    state.evidence.push({ id: "evidence-1", childId: graduateId, kind: "interest", expression: "一句话", recordedAt: "2026-01-05T09:00:00.000Z" });
    state.events.push(
      event(state, { eventType: "interest.observed", payload: { expression: "我想弄明白轮胎" }, subjectId: graduateId, authorId: caregiver.id }),
      event(state, { eventType: "interest.observed", payload: { expression: "我喜欢恐龙" }, subjectId: siblingId, authorId: caregiver.id })
    );

    const consent = createRetentionConsent(state, {
      childId: graduateId, authorId: graduateMemberId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, phrase: RETENTION_CONFIRMATION_PHRASE
    });
    const tombstone = applyRetentionOutcome(state, { childId: graduateId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, consent });
    assert.equal(tombstone.targetType, "SUBJECT");
    assert.equal(tombstone.reasonCode, "graduation-delete-subject-copy");
    assert.equal(subjectAfterOutcome(state, graduateId).retained, "none");
    // The relationship is gone from the live state as well, not just suppressed.
    assert.equal(state.children.length, 1);
    assert.equal(state.children[0].id, siblingId);
    assert.equal(state.members.some((member) => member.id === graduateMemberId), false);
    assert.equal(state.evidence.length, 0);
    assert.equal(state.events.length, 1);

    const merged = mergeFamilyState(state, structuredClone(state));
    assert.equal(merged.children.length, 1, "only the sibling remains a child");
    assert.equal(merged.children[0].id, siblingId);
    assert.equal(merged.events.length, 1);
    const view = projectSharedTimeline(state);
    assert.equal(view.entries.filter((entry) => entry.childId === graduateId).length, 0);
    assert.equal(view.entries.filter((entry) => entry.childId === siblingId).length, 1);
  });

  it("suppresses a stale copy that arrives after the outcome", () => {
    const { state, graduateId, graduateMemberId, siblingId } = graduatedHousehold();
    state.evidence.push({ id: "evidence-1", childId: graduateId, kind: "interest", expression: "一句话", recordedAt: "2026-01-05T09:00:00.000Z" });
    state.events.push(event(state, {
      eventType: "interest.observed", payload: { expression: "一句话" }, subjectId: graduateId, authorId: graduateMemberId
    }));
    const stale = structuredClone(state);

    const consent = createRetentionConsent(state, {
      childId: graduateId, authorId: graduateMemberId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, phrase: RETENTION_CONFIRMATION_PHRASE
    });
    applyRetentionOutcome(state, { childId: graduateId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, consent });

    // An offline device that never saw the outcome tries to bring it back.
    const merged = mergeFamilyState(state, stale);
    assert.equal(merged.evidence.length, 0);
    assert.equal(merged.events.length, 0);
    assert.equal(merged.children.length, 1, "only the sibling remains a child");
    assert.equal(merged.children[0].id, siblingId);
    assert.equal(merged.tombstones.length, 1);
    assert.equal(projectSharedTimeline(merged).entries.length, 0, "nothing is left to show for anyone");
  });

  it("refuses a consent that names a different outcome or person", () => {
    const { state, graduateId, graduateMemberId } = graduatedHousehold();
    const consent = createRetentionConsent(state, {
      childId: graduateId, authorId: graduateMemberId, outcome: RETENTION_OUTCOMES.relationshipOnly, phrase: RETENTION_CONFIRMATION_PHRASE
    });
    assert.throws(() => applyRetentionOutcome(state, {
      childId: graduateId, outcome: RETENTION_OUTCOMES.deleteSubjectCopy, consent
    }), /不一致/u);
    assert.throws(() => applyRetentionOutcome(state, {
      childId: graduateId, outcome: RETENTION_OUTCOMES.relationshipOnly, consent: { ...consent, subjectId: "someone-else" }
    }), /不是本人/u);
  });

  it("does not make export depend on a retention decision", () => {
    const { state, graduateId } = graduatedHousehold();
    state.evidence.push({ id: "evidence-1", childId: graduateId, kind: "interest", expression: "一句话", recordedAt: "2026-01-05T09:00:00.000Z" });
    // No consent exists at all, and the archive is still the person's to take.
    const archive = buildGraduationArchive(state, graduateId);
    assert.equal(archive.evidence.length, 1);
    assert.equal(state.tombstones.length, 0);
    assert.equal(subjectAfterOutcome(state, graduateId).retained, "records");
  });
});
