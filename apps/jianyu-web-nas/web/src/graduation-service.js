// The 16+ hand-over (成年交接). Two independent rights, deliberately not
// bundled together: the person can take a portable copy of the records about
// them, and separately can decide what the household keeps (ADR 0010).
//
// The archive is subject-scoped. Siblings, caregivers, household administration,
// and anything unrelated to this person are excluded by construction rather than
// filtered at render time, and authorship inside the included records is left
// alone so a caregiver's interpretation is never rewritten as the person's own
// statement (ADR 0007).
//
// The archive and its envelope are the canonical `org.foe.graduation-archive/v2`
// inside `org.foe.encrypted-graduation-bundle/v1` defined in DATA-SCHEMA.md §13,
// including the base64url encoding and the AAD binding the Android reference App
// uses, so the container a family holds is the one the schema describes. The
// records inside are this app's own `org.foe.event/v1` vocabulary, which the
// shared schema permits but another App's reader may narrow, so handing a bundle
// to the Android App is not claimed.

import { childLifecycle } from "./family-state.js";
import { randomBytes, base64UrlFromBytes, base64UrlToBytes } from "./nas-crypto.js";

export const GRADUATION_ARCHIVE_FORMAT = "org.foe.graduation-archive/v2";
/** Readers keep accepting the v1 archive, which carries no exact birth date. */
export const GRADUATION_ARCHIVE_V1_FORMAT = "org.foe.graduation-archive/v1";
export const SUPPORTED_GRADUATION_ARCHIVES = Object.freeze([
  GRADUATION_ARCHIVE_V1_FORMAT,
  GRADUATION_ARCHIVE_FORMAT
]);
export const GRADUATION_BUNDLE_FORMAT = "org.foe.encrypted-graduation-bundle/v1";
export const GRADUATION_CONSENT_FORMAT = "org.foe.graduation-retention-consent/v1";
export const GRADUATION_CIPHER = "AES-256-GCM";
/** The typed phrase a destructive retention outcome requires. */
export const RETENTION_CONFIRMATION_PHRASE = "我确认删除这份家庭副本";
const CONSENT_TTL_MS = 5 * 60 * 1000;

const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });

export class GraduationError extends Error {
  constructor(message) {
    super(message);
    this.name = "GraduationError";
  }
}

function newId() {
  return globalThis.crypto.randomUUID();
}

/** The 16+ subject for a child record, or null when they have not graduated. */
export function graduatedSubject(state, childId) {
  const child = state.children.find((item) => item.id === childId);
  if (!child) return null;
  let stage;
  try {
    stage = childLifecycle(child);
  } catch {
    // Without usable birth information there is no completed age, so there is no
    // authority to hand anything over.
    return null;
  }
  if (stage.age < 16 || !stage.stage) return null;
  return child;
}

/**
 * A subject-scoped archive: everything that belongs to this person, and nothing
 * that belongs to anyone else. The shape is the canonical v2 archive, so the
 * `archiveId` becomes the bundle ID and the generated Markdown view travels
 * inside the sealed archive rather than only beside it.
 */
export function buildGraduationArchive(state, childId) {
  const child = graduatedSubject(state, childId);
  if (!child) throw new GraduationError("只有 16 岁以上的人可以做成年交接");
  // ADR 0007 requires a child-role member linked to this subject, so the archive
  // can name the person without guessing which member record is theirs.
  const subjectMember = state.members.find((member) =>
    member.id === child.memberId && member.role === "child" && member.subjectId === child.id);
  if (!subjectMember) throw new GraduationError("这个人的家庭成员关系不完整，无法交接");

  const evidence = state.evidence.filter((item) => item.childId === child.id).map((item) => structuredClone(item));
  const hypotheses = state.hypotheses.filter((item) => item.childId === child.id).map((item) => structuredClone(item));
  const choices = state.choices.filter((item) => item.childId === child.id).map((item) => structuredClone(item));
  // A record this person wrote about someone else is theirs to take; it keeps
  // naming its own subject so the archive never reassigns it.
  const events = state.events.filter((event) =>
    event.subjectId === child.id || event.authorId === child.memberId).map((item) => structuredClone(item));

  const recordId = (item) => item.eventId ?? item.id;
  const includedIds = new Set([
    child.id,
    ...evidence.map(recordId),
    ...hypotheses.map(recordId),
    ...choices.map(recordId),
    ...events.map(recordId)
  ]);
  const tombstones = (state.tombstones ?? []).filter((item) =>
    item.subjectId === child.id ||
    (String(item.targetType).toUpperCase() === "SUBJECT" && item.targetId === child.id) ||
    includedIds.has(item.targetId)).map((item) => structuredClone(item));

  // The author table preserves roles without copying anyone else's display name.
  const authorIds = new Set([
    subjectMember.id,
    ...evidence.map((item) => item.authorId),
    ...events.map((event) => event.authorId),
    ...tombstones.map((item) => item.authorId)
  ].filter((id) => typeof id === "string" && id !== ""));
  const authors = state.members.filter((member) => authorIds.has(member.id))
    .map((member) => Object.freeze({ memberId: member.id, role: member.role }))
    .sort((left, right) => left.memberId.localeCompare(right.memberId));

  const birthYear = child.birthYear ??
    (typeof child.birthDate === "string" ? Number.parseInt(child.birthDate.slice(0, 4), 10) : null);
  if (!Number.isInteger(birthYear)) throw new GraduationError("这个人的出生信息不完整，无法交接");

  const createdAt = new Date().toISOString();
  const subject = Object.freeze({
    subjectId: child.id,
    memberId: subjectMember.id,
    displayName: child.displayName,
    birthYear,
    birthDate: child.birthDate ?? null
  });

  return Object.freeze({
    schema: GRADUATION_ARCHIVE_FORMAT,
    archiveId: newId(),
    createdAt,
    subject,
    authors: Object.freeze(authors),
    evidence: Object.freeze(evidence),
    hypotheses: Object.freeze(hypotheses),
    choices: Object.freeze(choices),
    events: Object.freeze(events),
    tombstones: Object.freeze(tombstones),
    humanReadableMarkdown: renderGraduationArchiveMarkdown({ createdAt, subject, events, choices })
  });
}

/**
 * A human-readable chronology, carried inside the sealed archive as
 * `humanReadableMarkdown`. It is a provenance summary, not a personality,
 * intelligence, potential, compliance, coverage, or development score.
 */
export function renderGraduationArchiveMarkdown(archive) {
  const lines = [
    `# ${archive.subject.displayName}的见隅记录`,
    "",
    `导出于 ${archive.createdAt}。这里只有关于本人的记录，不包含其他家庭成员的记录。`,
    "",
    "## 时间线",
    ""
  ];
  const ordered = [...archive.events].sort((left, right) =>
    String(left.occurredAt).localeCompare(String(right.occurredAt)));
  for (const event of ordered) {
    const author = archive.subject.memberId === event.authorId ? "本人" : event.actorRole;
    const detail = event.payload?.expression ?? event.payload?.correction ??
      event.payload?.title ?? event.eventType;
    lines.push(`- ${event.occurredAt} · ${event.eventType} · ${author}记录 · ${detail}`);
  }
  lines.push("", "## 保存过的选择", "");
  if (archive.choices.length === 0) lines.push("- 没有保存过的选择");
  for (const choice of archive.choices) {
    lines.push(`- ${choice.chosenAt} · ${choice.opportunity?.title ?? "一个选择"}`);
  }
  lines.push(
    "",
    "这份导出是记录，不是评价。它不包含任何关于能力、潜力或发展的评分。",
    ""
  );
  return lines.join("\n");
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/iu;
const MAX_CIPHERTEXT_BYTES = 16 * 1024 * 1024 + 16;

function bundleAad(bundleId, createdAt) {
  return encoder.encode(`${GRADUATION_BUNDLE_FORMAT}|${bundleId}|${createdAt}|graduation-archive`);
}

function boundedBytes(value, label, { exact = null, maximum = Infinity } = {}) {
  let bytes;
  try {
    bytes = base64UrlToBytes(value);
  } catch {
    throw new GraduationError(`交接包${label}格式不正确`);
  }
  if (exact !== null ? bytes.length !== exact : bytes.length < 1 || bytes.length > maximum) {
    throw new GraduationError(`交接包${label}长度不正确`);
  }
  return bytes;
}

/**
 * Seals an archive under a fresh, independent 256-bit recovery key. The key is
 * returned so the caller can show it once; it is never written into the bundle.
 * The archive ID becomes the bundle ID, and the envelope carries no subject
 * identity and no record count.
 */
export async function sealGraduationBundle(archive) {
  if (!archive || archive.schema !== GRADUATION_ARCHIVE_FORMAT) {
    throw new GraduationError("成年交接包内容不正确");
  }
  if (typeof archive.archiveId !== "string" || !UUID_PATTERN.test(archive.archiveId)) {
    throw new GraduationError("成年交接包标识不正确");
  }
  const bundleId = archive.archiveId;
  const createdAt = archive.createdAt;
  const recoveryKey = randomBytes(32);
  const nonce = randomBytes(12);
  const key = await globalThis.crypto.subtle.importKey("raw", recoveryKey, "AES-GCM", false, ["encrypt"]);
  const ciphertext = new Uint8Array(await globalThis.crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: bundleAad(bundleId, createdAt), tagLength: 128 },
    key,
    encoder.encode(JSON.stringify(archive))
  ));
  return Object.freeze({
    bundle: Object.freeze({
      format: GRADUATION_BUNDLE_FORMAT,
      cipher: GRADUATION_CIPHER,
      bundleId,
      createdAt,
      nonce: base64UrlFromBytes(nonce),
      ciphertext: base64UrlFromBytes(ciphertext)
    }),
    // Shown once and never stored anywhere the app can read again.
    recoveryKey: base64UrlFromBytes(recoveryKey)
  });
}

/**
 * Opens a bundle with its recovery key, so a family can verify what they hold.
 * Every check fails closed: an unknown version, a malformed field, a wrong key,
 * or an archive that does not match its own envelope is refused rather than
 * partially read.
 */
export async function openGraduationBundle(bundle, recoveryKey) {
  if (!bundle || typeof bundle !== "object" || Array.isArray(bundle)) {
    throw new GraduationError("这不是一个见隅成年交接包");
  }
  if (bundle.format !== GRADUATION_BUNDLE_FORMAT || bundle.cipher !== GRADUATION_CIPHER) {
    throw new GraduationError("这个交接包的版本不受支持");
  }
  if (typeof bundle.bundleId !== "string" || !UUID_PATTERN.test(bundle.bundleId)) {
    throw new GraduationError("交接包标识不正确");
  }
  if (typeof bundle.createdAt !== "string" || Number.isNaN(Date.parse(bundle.createdAt))) {
    throw new GraduationError("交接包时间不正确");
  }
  const keyBytes = boundedBytes(recoveryKey, "密钥", { exact: 32 });
  const nonce = boundedBytes(bundle.nonce, "随机数", { exact: 12 });
  const ciphertext = boundedBytes(bundle.ciphertext, "密文", { maximum: MAX_CIPHERTEXT_BYTES });

  const key = await globalThis.crypto.subtle.importKey("raw", keyBytes, "AES-GCM", false, ["decrypt"]);
  let plaintext;
  try {
    plaintext = await globalThis.crypto.subtle.decrypt(
      { name: "AES-GCM", iv: nonce, additionalData: bundleAad(bundle.bundleId, bundle.createdAt), tagLength: 128 },
      key,
      ciphertext
    );
  } catch {
    throw new GraduationError("交接密钥不正确，或交接包已被篡改");
  }
  let archive;
  try {
    archive = JSON.parse(decoder.decode(plaintext));
  } catch {
    throw new GraduationError("交接包内容无法读取");
  }
  if (!archive || typeof archive !== "object" || !SUPPORTED_GRADUATION_ARCHIVES.includes(archive.schema)) {
    throw new GraduationError("交接包内容格式不受支持");
  }
  if (archive.archiveId !== bundle.bundleId || archive.createdAt !== bundle.createdAt) {
    throw new GraduationError("交接包内容和它的外壳不一致");
  }
  return archive;
}

/** What the household keeps. Read-only is the default and changes nothing. */
export const RETENTION_OUTCOMES = Object.freeze({
  readOnly: "read-only",
  relationshipOnly: "relationship-only",
  deleteSubjectCopy: "delete-subject-copy"
});

/**
 * A subject-matched consent, valid for five minutes. The screen must obtain a
 * fresh one for each destructive outcome; the archive export never depends on
 * it, because portability and erasure are independent rights (ADR 0010).
 */
export function createRetentionConsent(state, { childId, authorId, outcome, phrase }) {
  const child = graduatedSubject(state, childId);
  if (!child) throw new GraduationError("只有 16 岁以上的人可以做成年交接");
  if (authorId !== child.memberId) {
    throw new GraduationError("这个决定需要由本人确认，不能由别人代选");
  }
  if (outcome === RETENTION_OUTCOMES.readOnly) {
    throw new GraduationError("只读保留不需要确认，它不改变任何记录");
  }
  if (phrase !== RETENTION_CONFIRMATION_PHRASE) {
    throw new GraduationError("请先输入确认语，再继续");
  }
  return Object.freeze({
    format: GRADUATION_CONSENT_FORMAT,
    consentId: newId(),
    householdId: state.household.id,
    subjectId: child.id,
    memberId: child.memberId,
    outcome,
    grantedAt: Date.now(),
    expiresAt: Date.now() + CONSENT_TTL_MS
  });
}

export function consentIsCurrent(consent) {
  const now = Date.now();
  return Boolean(consent) && consent.grantedAt <= now && consent.expiresAt > now;
}

/**
 * Applies a retention outcome. Both destructive outcomes append one content-free
 * tombstone rather than a narrative event, and the marker travels with the
 * state, so it keeps working offline (ADR 0010).
 *
 * The tombstone is written first and the live collections are then reduced to
 * match what the public merger would produce, so the screens a family is looking
 * at stop showing the history immediately, and a stale device cannot bring it
 * back. This is removal from the active household copy, not cryptographic
 * erasure: old remote ciphertext, filesystem remnants, separate exports, and
 * provider-held plaintext are outside its reach.
 */
export function applyRetentionOutcome(state, { childId, outcome, consent }) {
  const child = graduatedSubject(state, childId);
  if (!child) throw new GraduationError("只有 16 岁以上的人可以做成年交接");
  if (outcome === RETENTION_OUTCOMES.readOnly) return null;
  if (!consentIsCurrent(consent)) throw new GraduationError("确认已过期，请重新确认");
  if (consent.subjectId !== child.id || consent.memberId !== child.memberId) {
    throw new GraduationError("这个确认不是本人做出的");
  }
  if (consent.outcome !== outcome) throw new GraduationError("确认的结果和要做的操作不一致");

  const subjectContent = outcome === RETENTION_OUTCOMES.relationshipOnly;
  const tombstone = Object.freeze({
    // v2 is required for SUBJECT_CONTENT; SUBJECT keeps working under it too.
    schema: "org.foe.deletion-tombstone/v2",
    tombstoneId: newId(),
    householdId: state.household.id,
    targetType: subjectContent ? "SUBJECT_CONTENT" : "SUBJECT",
    targetId: child.id,
    subjectId: child.id,
    authorId: child.memberId,
    deviceId: "jianyu-web-local",
    deletedAt: new Date().toISOString(),
    reasonCode: subjectContent ? "graduation-relationship-only" : "graduation-delete-subject-copy"
  });
  state.tombstones.push(tombstone);

  state.evidence = state.evidence.filter((item) => item.childId !== child.id);
  state.hypotheses = state.hypotheses.filter((item) => item.childId !== child.id);
  state.choices = state.choices.filter((item) => item.childId !== child.id);
  state.events = state.events.filter((item) => item.subjectId !== child.id);
  if (!subjectContent) {
    state.children = state.children.filter((item) => item.id !== child.id);
    state.members = state.members.filter((item) =>
      item.id !== child.memberId && item.subjectId !== child.id);
  }
  return tombstone;
}

/**
 * What the household copy still holds after an outcome. Read-only keeps the
 * person's history; relationship-only keeps the relationship and drops the
 * history; deletion removes both.
 */
export function subjectAfterOutcome(state, childId) {
  const subjectTombstones = (state.tombstones ?? []).filter((item) => item.subjectId === childId);
  const subjectDeleted = subjectTombstones.some((item) =>
    String(item.targetType).toUpperCase() === "SUBJECT");
  if (subjectDeleted) return Object.freeze({ retained: "none" });
  const contentCleared = subjectTombstones.some((item) =>
    String(item.targetType).toUpperCase() === "SUBJECT_CONTENT");
  if (contentCleared) return Object.freeze({ retained: "relationship" });
  return Object.freeze({ retained: "records" });
}

/**
 * What the family-facing screens say once someone has graduated: the household
 * stops adding records about this person, and the hand-over belongs to them.
 */
export function graduationNotice(child, today = new Date()) {
  let stage;
  try {
    stage = childLifecycle(child, today);
  } catch {
    return null;
  }
  if (stage.age < 16 || !stage.stage) return null;
  return "16 岁以上，家庭这边不再替这个人新增记录。成年交接由本人决定。";
}
