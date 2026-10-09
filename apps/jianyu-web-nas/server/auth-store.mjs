import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { mkdir, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

const HOUSEHOLD_SCHEMA = "org.jianyu.nas-household/v1";
const KDF_ID = "PBKDF2-SHA-256";
const HOUSEHOLD_ID_PATTERN = /^[A-Za-z0-9_-]{24}$/;
const VERIFIER_PATTERN = /^[a-f0-9]{64}$/;
const SALT_PATTERN = /^[A-Za-z0-9+/]{22}==$/;
const ITERATION_BOUNDS = { min: 100_000, max: 2_000_000 };

function hashVerifier(verifier) {
  return createHash("sha256").update(Buffer.from(verifier, "hex")).digest("hex");
}

/**
 * Constant-time comparison for two hex digests.
 *
 * The unlock path is the only place the server compares secrets, so it must not
 * leak timing about how many leading bytes matched.
 */
function digestsMatch(left, right) {
  if (typeof left !== "string" || typeof right !== "string") return false;
  if (left.length !== right.length) return false;
  try {
    return timingSafeEqual(Buffer.from(left, "hex"), Buffer.from(right, "hex"));
  } catch {
    return false;
  }
}

/**
 * Delay-only throttling.
 *
 * A failed attempt costs the guesser time but never locks a family out, so a
 * mistyped passphrase can never strand data. Counters are keyed by the caller
 * (source address, optionally combined with household) so one noisy neighbour
 * cannot stall another family.
 */
class FailureThrottle {
  #attempts = new Map();

  constructor({ windowMs, threshold = 2, baseDelayMs, maxDelayMs }) {
    this.windowMs = windowMs;
    this.threshold = threshold;
    this.baseDelayMs = baseDelayMs;
    this.maxDelayMs = maxDelayMs;
  }

  note(key) {
    const now = Date.now();
    const recent = (this.#attempts.get(key) ?? []).filter((at) => now - at < this.windowMs);
    recent.push(now);
    this.#attempts.set(key, recent);
    return recent.length;
  }

  clear(key) {
    this.#attempts.delete(key);
  }

  delayFor(keys) {
    const worst = Math.max(0, ...keys.map((key) => this.#attempts.get(key)?.length ?? 0));
    if (worst < this.threshold) return 0;
    const exponential = this.baseDelayMs * 2 ** Math.min(worst - this.threshold, 6);
    return Math.min(exponential, this.maxDelayMs);
  }

  prune() {
    const now = Date.now();
    for (const [key, attempts] of this.#attempts) {
      const recent = attempts.filter((at) => now - at < this.windowMs);
      if (recent.length === 0) this.#attempts.delete(key);
      else this.#attempts.set(key, recent);
    }
  }
}

function assertRegistration(payload) {
  if (!payload || typeof payload !== "object") throw new TypeError("registration must be an object");
  const { householdId, verifier, salt, iterations } = payload;
  if (typeof householdId !== "string" || !HOUSEHOLD_ID_PATTERN.test(householdId)) {
    throw new TypeError("householdId must be a canonical 24-character opaque identifier");
  }
  if (typeof verifier !== "string" || !VERIFIER_PATTERN.test(verifier)) {
    throw new TypeError("verifier must be a 32-byte hex PBKDF2 output");
  }
  if (typeof salt !== "string" || !SALT_PATTERN.test(salt)) {
    throw new TypeError("salt must be a 16-byte base64 value");
  }
  if (!Number.isInteger(iterations) || iterations < ITERATION_BOUNDS.min || iterations > ITERATION_BOUNDS.max) {
    throw new TypeError(`iterations must be an integer between ${ITERATION_BOUNDS.min} and ${ITERATION_BOUNDS.max}`);
  }
  return { householdId, verifierHash: hashVerifier(verifier), salt, iterations };
}

/**
 * Household credentials and sessions.
 *
 * The store only ever holds SHA-256(verifier), the salt, and the iteration
 * count the browser chose. It cannot derive the vault key, read family state,
 * or reset a passphrase: a forgotten passphrase is unrecoverable by design.
 */
export class HouseholdAuthStore {
  #directory;
  #sessionDirectory;
  #config;
  #stateLocks = new Map();
  #unlockThrottle;
  #registerThrottle;

  constructor(config) {
    this.#config = config;
    this.#directory = join(config.dataDir, "households");
    this.#sessionDirectory = join(config.dataDir, "sessions");
    this.#unlockThrottle = new FailureThrottle(config.unlockThrottle);
    this.#registerThrottle = new FailureThrottle(config.registerThrottle);
  }

  async prepare() {
    await mkdir(this.#directory, { recursive: true });
    await mkdir(this.#sessionDirectory, { recursive: true });
  }

  #householdPath(householdId) {
    return join(this.#directory, `${householdId}.json`);
  }

  #sessionPath(sessionId) {
    return join(this.#sessionDirectory, `${sessionId}.json`);
  }

  async has(householdId) {
    if (typeof householdId !== "string" || !HOUSEHOLD_ID_PATTERN.test(householdId)) return false;
    try {
      await readFile(this.#householdPath(householdId));
      return true;
    } catch {
      return false;
    }
  }

  async register(householdId, verifier, salt, iterations) {
    const record = assertRegistration({ householdId, verifier, salt, iterations });
    if (await this.has(record.householdId)) {
      const error = new Error("this household is already registered on this server");
      error.code = "household-exists";
      throw error;
    }
    const now = new Date().toISOString();
    await writeFile(
      this.#householdPath(record.householdId),
      JSON.stringify({
        schema: HOUSEHOLD_SCHEMA,
        householdId: record.householdId,
        verifierHash: record.verifierHash,
        salt: record.salt,
        iterations: record.iterations,
        kdf: KDF_ID,
        createdAt: now,
        updatedAt: now,
        stateVersion: 0,
        currentObjectId: null
      }, null, 2),
      { encoding: "utf8", flag: "wx" }
    );
    return { householdId: record.householdId, createdAt: now };
  }

  /**
   * Exchanges a verifier for a session.
   *
   * Returns null on a wrong passphrase; the caller adds the throttle delay so a
   * guesser still pays for the failure.
   */
  async unlock(householdId, verifier) {
    if (typeof householdId !== "string" || typeof verifier !== "string") return null;
    let record;
    try {
      record = JSON.parse(await readFile(this.#householdPath(householdId), "utf8"));
    } catch {
      return null;
    }
    if (record.schema !== HOUSEHOLD_SCHEMA || !digestsMatch(record.verifierHash, hashVerifier(verifier))) return null;
    const sessionId = randomBytes(32).toString("base64url");
    const now = Date.now();
    const session = {
      schema: "org.jianyu.nas-session/v1",
      householdId,
      sessionIdHash: createHash("sha256").update(sessionId).digest("hex"),
      createdAt: new Date(now).toISOString(),
      expiresAt: new Date(now + this.#config.sessionTtlMs).toISOString()
    };
    await writeFile(this.#sessionPath(sessionId), JSON.stringify(session), "utf8");
    return { sessionId, expiresAt: session.expiresAt };
  }

  async resolveSession(sessionId) {
    if (typeof sessionId !== "string" || sessionId.length === 0 || sessionId.length > 256) return null;
    try {
      const session = JSON.parse(await readFile(this.#sessionPath(sessionId), "utf8"));
      if (session.schema !== "org.jianyu.nas-session/v1") return null;
      if (new Date(session.expiresAt).getTime() <= Date.now()) {
        await this.revokeSession(sessionId);
        return null;
      }
      return session;
    } catch {
      return null;
    }
  }

  async revokeSession(sessionId) {
    if (typeof sessionId !== "string") return;
    try {
      await rm(this.#sessionPath(sessionId), { force: true });
    } catch {
      // Revocation is best effort: a missing file already means "no session".
    }
  }

  async revokeSessionsFor(householdId) {
    const entries = await readdir(this.#sessionDirectory).catch(() => []);
    for (const entry of entries) {
      try {
        const session = JSON.parse(await readFile(join(this.#sessionDirectory, entry), "utf8"));
        if (session.householdId === householdId) await rm(join(this.#sessionDirectory, entry), { force: true });
      } catch {
        // An unreadable session file is removed on the next erase pass.
      }
    }
  }

  noteRegisterAttempt(source) {
    this.#registerThrottle.prune();
    return this.#registerThrottle.note(`register|${source}`);
  }

  registerDelayFor(source) {
    this.#registerThrottle.prune();
    return this.#registerThrottle.delayFor([`register|${source}`]);
  }

  noteUnlockFailure(keys) {
    this.#unlockThrottle.prune();
    for (const key of keys) this.#unlockThrottle.note(key);
  }

  unlockDelayFor(keys) {
    this.#unlockThrottle.prune();
    return this.#unlockThrottle.delayFor(keys);
  }

  clearUnlockFailures(keys) {
    for (const key of keys) this.#unlockThrottle.clear(key);
  }

  async erase(householdId) {
    if (typeof householdId !== "string" || !HOUSEHOLD_ID_PATTERN.test(householdId)) return;
    await rm(this.#householdPath(householdId), { force: true });
    await this.revokeSessionsFor(householdId);
  }

  #readRecord(householdId) {
    return readFile(this.#householdPath(householdId), "utf8").then((text) => JSON.parse(text));
  }

  /**
   * Serializes state commits per household so a single container cannot race
   * two read-modify-write cycles on the same version counter.
   */
  #withStateLock(householdId, operation) {
    const previous = this.#stateLocks.get(householdId) ?? Promise.resolve();
    const next = previous.then(operation, operation);
    this.#stateLocks.set(householdId, next.catch(() => {}));
    return next;
  }

  /** The household's committed state pointer, or null before the first save. */
  async readStatePointer(householdId) {
    if (!HOUSEHOLD_ID_PATTERN.test(String(householdId))) return null;
    try {
      const record = await this.#readRecord(householdId);
      if (record.currentObjectId == null) return { version: record.stateVersion ?? 0, objectId: null, updatedAt: record.updatedAt };
      return { version: record.stateVersion ?? 0, objectId: record.currentObjectId, updatedAt: record.updatedAt };
    } catch {
      return null;
    }
  }

  /**
   * Advances the household's state pointer when, and only when, the client's
   * base version still matches. A stale write is refused with the current
   * pointer so the browser can reload and merge locally instead of overwriting
   * another device's work.
   */
  async commitState(householdId, objectId, baseVersion) {
    if (!HOUSEHOLD_ID_PATTERN.test(String(householdId))) return null;
    if (!HOUSEHOLD_ID_PATTERN.test(String(objectId))) {
      const error = new TypeError("objectId must be a canonical 24-character opaque identifier");
      error.code = "invalid-object-id";
      throw error;
    }
    if (!Number.isInteger(baseVersion) || baseVersion < 0) {
      throw new TypeError("baseVersion must be a non-negative integer");
    }
    return this.#withStateLock(householdId, async () => {
      const record = await this.#readRecord(householdId);
      if ((record.stateVersion ?? 0) !== baseVersion) {
        const error = new Error("another writer advanced this household's state; reload and merge");
        error.code = "state-conflict";
        error.currentVersion = record.stateVersion ?? 0;
        error.currentObjectId = record.currentObjectId ?? null;
        throw error;
      }
      const updated = {
        ...record,
        stateVersion: (record.stateVersion ?? 0) + 1,
        currentObjectId: objectId,
        updatedAt: new Date().toISOString()
      };
      await writeFile(this.#householdPath(householdId), JSON.stringify(updated, null, 2), "utf8");
      return { version: updated.stateVersion, objectId, updatedAt: updated.updatedAt };
    });
  }
}
