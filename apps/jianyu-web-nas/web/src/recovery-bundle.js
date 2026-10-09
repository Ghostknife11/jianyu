// The portable recovery bundle. A bundle is a plain JSON document plus one
// random recovery code shown once at export; nothing in it is readable without
// that code, and the code is the only recovery material besides the passphrase.
//
// Import is a two-step replacement, never a merge (ADR 0030): the file and the
// code are verified locally first, the bundled household is previewed, and a
// separate confirmation performs the replacement. A wrong code, or a local
// change after the preview, leaves the existing vault untouched.

import {
  deriveRecoveryKey,
  generateRecoveryCode,
  openState,
  recoveryCodeCheck,
  RECOVERY_INFO,
  sealState,
  KDF_ITERATIONS,
  KDF_NAME
} from "./nas-crypto.js";

export const RECOVERY_BUNDLE_FORMAT = "org.jianyu.web-recovery-bundle/v1";

/** Plain-language reason a bundle was refused; never leaks its contents. */
export class RecoveryBundleError extends Error {
  constructor(message) {
    super(message);
    this.name = "RecoveryBundleError";
  }
}

function assertString(value, name) {
  if (typeof value !== "string" || value === "") {
    throw new RecoveryBundleError(`恢复包缺少${name}`);
  }
  return value;
}

/**
 * Checks the shape before any key work, so a malformed or foreign file is
 * refused without deriving anything.
 */
export function assertRecoveryBundleShape(bundle) {
  if (!bundle || typeof bundle !== "object" || Array.isArray(bundle)) {
    throw new RecoveryBundleError("这不是一个见隅恢复包文件");
  }
  if (bundle.format !== RECOVERY_BUNDLE_FORMAT) {
    throw new RecoveryBundleError("恢复包格式不受支持");
  }
  if (bundle.kdf !== KDF_NAME) {
    throw new RecoveryBundleError("恢复包的密钥派生参数不受支持");
  }
  assertString(bundle.householdId, "家庭标识");
  assertString(bundle.salt, "盐值");
  assertString(bundle.recoveryCheck, "恢复码校验值");
  assertString(bundle.nonce, "密文");
  assertString(bundle.ciphertext, "密文");
  if (!Number.isInteger(bundle.iterations) || bundle.iterations < 1) {
    throw new RecoveryBundleError("恢复包的迭代次数无效");
  }
  if (!Number.isInteger(bundle.stateVersion) || bundle.stateVersion < 1) {
    throw new RecoveryBundleError("恢复包的状态版本无效");
  }
  return bundle;
}

/**
 * Exports a bundle. The state is re-sealed under a key derived from a fresh
 * recovery code, bound to the household identity that travels inside the
 * bundle rather than to any one server's handle, so the bundle stays openable
 * on a different NAS.
 */
export async function exportRecoveryBundle({ vault }) {
  if (!vault?.isUnlocked) throw new RecoveryBundleError("请先解锁家庭保险箱");
  const state = vault.state;
  const salt = vault.salt;
  if (typeof salt !== "string" || salt === "") {
    throw new RecoveryBundleError("恢复包需要家庭盐值，当前记录不完整");
  }
  const code = generateRecoveryCode();
  const recoveryKey = await deriveRecoveryKey(code, salt, KDF_ITERATIONS);
  const sealed = await sealState(recoveryKey, state, state.household.id, vault.stateVersion);
  return {
    bundle: {
      format: RECOVERY_BUNDLE_FORMAT,
      householdId: state.household.id,
      kdf: KDF_NAME,
      recoveryInfo: RECOVERY_INFO,
      iterations: KDF_ITERATIONS,
      salt,
      recoveryCheck: await recoveryCodeCheck(code, salt),
      stateVersion: vault.stateVersion,
      nonce: sealed.nonce,
      ciphertext: sealed.ciphertext,
      exportedAt: new Date().toISOString()
    },
    recoveryCode: code
  };
}

/**
 * Verifies a bundle and returns the preview. The cheap code check runs first so
 * a mistyped code is refused before the expensive derivation; the sealed state
 * is then opened and its household identity checked against the one the bundle
 * declares, so a mismatched or tampered file is refused.
 */
export async function previewRecoveryBundle(bundle, code) {
  const checked = assertRecoveryBundleShape(bundle);
  let check;
  try {
    check = await recoveryCodeCheck(code, checked.salt);
  } catch (error) {
    throw new RecoveryBundleError(error instanceof TypeError ? error.message : "恢复码格式不正确");
  }
  if (check !== checked.recoveryCheck) {
    throw new RecoveryBundleError("恢复码与这个恢复包不匹配");
  }
  const recoveryKey = await deriveRecoveryKey(code, checked.salt, checked.iterations);
  let state;
  try {
    state = await openState(recoveryKey, checked, checked.householdId, checked.stateVersion);
  } catch {
    throw new RecoveryBundleError("恢复码不正确，或恢复包已被篡改");
  }
  if (!state || typeof state !== "object" || state.household?.id !== checked.householdId) {
    throw new RecoveryBundleError("恢复包内容与它声明的家庭不一致");
  }
  const members = Array.isArray(state.members) ? state.members : [];
  const children = Array.isArray(state.children) ? state.children : [];
  return {
    householdId: checked.householdId,
    householdName: typeof state.household.name === "string" ? state.household.name : "",
    memberCount: members.length,
    childCount: children.length,
    childNames: children.map((child) => child?.displayName).filter((name) => typeof name === "string"),
    stateVersion: checked.stateVersion,
    salt: checked.salt,
    iterations: checked.iterations,
    state
  };
}

/** Serializes a bundle for download. */
export function serializeRecoveryBundle(bundle) {
  return `${JSON.stringify(bundle, null, 2)}\n`;
}

/** Parses a bundle from a file's text. */
export function parseRecoveryBundle(text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new RecoveryBundleError("恢复包文件不是有效的 JSON");
  }
  return assertRecoveryBundleShape(parsed);
}
