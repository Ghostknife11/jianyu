export const CURRENT_FAMILY_STATE_VERSION = 2;
const SCHEMA_PREFIX = "org.jianyu.family-vault/v";

const migrations = new Map([
  [1, (state) => {
    const existingMemberIds = new Set(state.members.map((member) => member.id));
    for (const child of state.children) {
      const memberId = child.memberId ?? `child-member:${child.id}`;
      child.memberId = memberId;
      if (!existingMemberIds.has(memberId)) {
        state.members.push({
          id: memberId,
          role: "child",
          subjectId: child.id,
          displayName: child.displayName,
          createdAt: child.createdAt
        });
        existingMemberIds.add(memberId);
      }
    }
    return state;
  }]
]);

function schemaVersion(schema) {
  if (typeof schema !== "string" || !schema.startsWith(SCHEMA_PREFIX)) {
    throw new TypeError("无法识别家庭保险箱数据版本");
  }
  const version = Number(schema.slice(SCHEMA_PREFIX.length));
  if (!Number.isInteger(version) || version < 1) throw new TypeError("家庭保险箱版本无效");
  return version;
}

function assertMinimumShape(state) {
  if (!state?.household?.id || !Array.isArray(state.members) || !Array.isArray(state.children)) {
    throw new TypeError("家庭保险箱缺少必要数据");
  }
  if (!Array.isArray(state.events) || !Array.isArray(state.choices)) {
    throw new TypeError("家庭保险箱的记录结构无效");
  }
}

export function migrateFamilyState(input) {
  let state = structuredClone(input);
  let version = schemaVersion(state.schema);
  if (version > CURRENT_FAMILY_STATE_VERSION) {
    throw new Error("这个保险箱来自更高版本的见隅，请先升级 App");
  }
  let migrated = false;
  while (version < CURRENT_FAMILY_STATE_VERSION) {
    const migration = migrations.get(version);
    if (!migration) throw new Error(`缺少从 v${version} 开始的数据迁移`);
    state = migration(state);
    version += 1;
    state.schema = `${SCHEMA_PREFIX}${version}`;
    migrated = true;
  }
  assertMinimumShape(state);
  return { state, migrated };
}
