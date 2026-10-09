// Family state for the NAS web app: `org.jianyu.web-family-state/v1`.
//
// The collection names and ID fields are deliberately the ones
// `mergeFamilyState` already merges, so the public merge function applies to
// this format without modification. Events use the public
// `org.foe.event/v1` envelope and must pass `assertEvent` before appending.

import { assertEvent } from "../../../../packages/foe-schema/src/index.js";
import { lifecycleAuthority, resolveLifecycleStage } from "../../../../packages/policy-sdk/src/index.js";

export const STATE_SCHEMA = "org.jianyu.web-family-state/v1";
export const CURRENT_STATE_VERSION = 1;
const SCHEMA_PREFIX = "org.jianyu.web-family-state/v";
const COLLECTIONS = ["members", "children", "evidence", "hypotheses", "choices", "events", "tombstones"];
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/u;

function now() {
  return new Date().toISOString();
}

function newId() {
  return globalThis.crypto.randomUUID();
}

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function formatDate(year, month, day) {
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

/**
 * The calendar day a birthday falls on in a given year. A February 29 birth
 * date advances on March 1 in non-leap years (ADR 0011).
 */
function birthdayInYear(birth, year) {
  const [birthYear, month, day] = birth;
  if (month === 2 && day === 29 && !isLeapYear(year)) return { year, month: 3, day: 1 };
  return { year, month, day };
}

function parseBirthDate(value) {
  if (typeof value !== "string") return null;
  const match = value.trim().match(DATE_PATTERN);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  // Reject impossible calendar dates such as 2023-02-29.
  const probe = new Date(Date.UTC(year, month - 1, day));
  if (probe.getUTCMonth() !== month - 1 || probe.getUTCDate() !== day) return null;
  return [year, month, day];
}

function compareDays(left, right) {
  return left.year - right.year || left.month - right.month || left.day - right.day;
}

/** Completed years against the device's current local date. */
export function completedAge(child, today = new Date()) {
  const birth = parseBirthDate(child?.birthDate);
  if (!birth) {
    const year = Number(child?.birthYear);
    if (!Number.isInteger(year)) throw new TypeError("孩子记录缺少可用的出生信息");
    return { age: today.getFullYear() - year, approximate: true };
  }
  const reference = { year: today.getFullYear(), month: today.getMonth() + 1, day: today.getDate() };
  let age = reference.year - birth[0];
  if (compareDays(reference, birthdayInYear(birth, reference.year)) < 0) age -= 1;
  return { age, approximate: false };
}

/**
 * Lifecycle for one child. Below age 4 there is no stage and no discovery,
 * matching the reference app's boundary; the record itself is kept.
 */
export function childLifecycle(child, today = new Date()) {
  const { age, approximate } = completedAge(child, today);
  if (age < 4) {
    return { age, approximate, stage: null, authority: null, discoveryOffered: false };
  }
  const stage = resolveLifecycleStage(age);
  return { age, approximate, stage, authority: lifecycleAuthority(stage), discoveryOffered: true };
}

export function createInitialFamilyState({ familyName, caregiverName, childName, birthDate } = {}) {
  if (typeof familyName !== "string" || familyName.trim() === "") throw new TypeError("请填写家庭称呼");
  if (typeof caregiverName !== "string" || caregiverName.trim() === "") throw new TypeError("请填写一位家长称呼");
  const timestamp = now();
  const household = { id: newId(), name: familyName.trim(), createdAt: timestamp };
  const caregiver = { id: newId(), role: "caregiver", displayName: caregiverName.trim(), createdAt: timestamp };
  const members = [caregiver];
  const children = [];
  if (typeof childName === "string" && childName.trim() !== "") {
    const childId = newId();
    const memberId = newId();
    const birth = parseBirthDate(birthDate);
    if (!birth) throw new TypeError("孩子的出生日期需要是有效的 YYYY-MM-DD");
    children.push({
      id: childId,
      memberId,
      displayName: childName.trim(),
      birthDate: formatDate(birth[0], birth[1], birth[2]),
      birthYear: birth[0],
      createdAt: timestamp
    });
    members.push({
      id: memberId,
      role: "child",
      subjectId: childId,
      displayName: childName.trim(),
      createdAt: timestamp
    });
  }
  return {
    schema: STATE_SCHEMA,
    household,
    members,
    children,
    evidence: [],
    hypotheses: [],
    choices: [],
    events: [],
    tombstones: [],
    preferences: { theme: "auto" }
  };
}

export function assertFamilyState(state) {
  if (!state || typeof state !== "object" || Array.isArray(state)) throw new TypeError("家庭状态必须是一个对象");
  if (state.schema !== STATE_SCHEMA) throw new TypeError(`不受支持的家庭状态格式：${String(state.schema)}`);
  if (!state.household || typeof state.household.id !== "string" || state.household.id === "") {
    throw new TypeError("家庭状态缺少家庭标识");
  }
  for (const collection of COLLECTIONS) {
    if (!Array.isArray(state[collection])) throw new TypeError(`家庭状态的 ${collection} 结构无效`);
  }
  if (!state.preferences || typeof state.preferences !== "object") throw new TypeError("家庭状态缺少偏好设置");
  for (const event of state.events) assertEvent(event);
  return state;
}

/**
 * Builds one public event envelope. The author is resolved from the member
 * list: an explicit author first, then the first caregiver, then any member.
 */
export function createFamilyEvent(state, eventType, payload, { authorId, subjectId, visibility = "guardians", deviceId } = {}) {
  const author = state.members.find((member) => member.id === authorId)
    ?? state.members.find((member) => member.role === "caregiver")
    ?? state.members[0];
  if (!author) throw new TypeError("家庭里还没有可以署名的成员");
  const timestamp = now();
  return assertEvent({
    schema: "org.foe.event/v1",
    eventId: newId(),
    eventType,
    eventVersion: 1,
    householdId: state.household.id,
    authorId: author.id,
    actorRole: author.role,
    subjectId: subjectId ?? author.subjectId ?? null,
    deviceId: typeof deviceId === "string" && deviceId !== "" ? deviceId : "jianyu-web-local",
    occurredAt: timestamp,
    recordedAt: timestamp,
    visibility,
    payload
  });
}

/** Appends one validated event. Duplicate IDs are ignored, never duplicated. */
export function appendEvent(state, event) {
  assertFamilyState(state);
  assertEvent(event);
  if (state.events.some((existing) => existing.eventId === event.eventId)) return event;
  state.events.push(event);
  return event;
}

export function addChild(state, { displayName, birthDate, authorId }) {
  if (typeof displayName !== "string" || displayName.trim() === "") throw new TypeError("请填写孩子称呼");
  const birth = parseBirthDate(birthDate);
  if (!birth) throw new TypeError("孩子的出生日期需要是有效的 YYYY-MM-DD");
  const timestamp = now();
  const childId = newId();
  const memberId = newId();
  const child = {
    id: childId,
    memberId,
    displayName: displayName.trim(),
    birthDate: formatDate(birth[0], birth[1], birth[2]),
    birthYear: birth[0],
    createdAt: timestamp
  };
  state.children.push(child);
  state.members.push({ id: memberId, role: "child", subjectId: childId, displayName: child.displayName, createdAt: timestamp });
  appendEvent(state, createFamilyEvent(state, "family.child-added", { childId, displayName: child.displayName }, { authorId, subjectId: childId }));
  return child;
}

export function addCaregiver(state, { displayName, authorId }) {
  if (typeof displayName !== "string" || displayName.trim() === "") throw new TypeError("请填写成员称呼");
  const timestamp = now();
  const member = { id: newId(), role: "caregiver", displayName: displayName.trim(), createdAt: timestamp };
  state.members.push(member);
  appendEvent(state, createFamilyEvent(state, "family.member-added", { memberId: member.id, role: member.role }, { authorId }));
  return member;
}

const migrations = new Map();

/**
 * Deterministic, offline, idempotent. A state newer than this client
 * understands fails closed rather than guessing at unknown semantics.
 */
export function migrateFamilyState(input) {
  if (!input || typeof input !== "object") throw new TypeError("家庭状态缺失");
  const state = structuredClone(input);
  let version = stateVersionOf(state.schema);
  if (version > CURRENT_STATE_VERSION) {
    throw new Error("这个保险箱来自更高版本的见隅，请先升级应用");
  }
  let migrated = false;
  while (version < CURRENT_STATE_VERSION) {
    const migration = migrations.get(version);
    if (!migration) throw new Error(`缺少从 v${version} 开始的数据迁移`);
    migration(state);
    version += 1;
    state.schema = `${SCHEMA_PREFIX}${version}`;
    migrated = true;
  }
  assertFamilyState(state);
  return { state, migrated };
}

function stateVersionOf(schema) {
  if (typeof schema !== "string" || !schema.startsWith(SCHEMA_PREFIX)) {
    throw new TypeError("无法识别家庭状态数据版本");
  }
  const version = Number(schema.slice(SCHEMA_PREFIX.length));
  if (!Number.isInteger(version) || version < 1) throw new TypeError("家庭状态版本无效");
  return version;
}
