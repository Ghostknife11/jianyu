const FORBIDDEN_WORLD_QUERY_KEYS = new Set([
  "childId",
  "subjectId",
  "householdId",
  "familyVaultId",
  "interests",
  "assessments",
  "observations",
  "exactAddress",
  "currentInterest",
  "childGoal",
  "caregiverGoal",
  "sharedGoal",
  "recentEvidence",
  "schoolWindow",
  "lifeContext"
]);
const PUBLIC_WORLD_QUERY_KEYS = new Set(["region", "timeWindow", "language", "categories", "cursor"]);
const issuedCapabilities = new WeakSet();
const OPAQUE_SYNC_OBJECT_ID = /^[A-Za-z0-9_-]{24}$/;
const MAX_SYNC_OBJECT_BYTES = 24 * 1024 * 1024;

export function assertProvider(provider, kind) {
  if (!provider || typeof provider !== "object") {
    throw new TypeError(`${kind} provider must be an object`);
  }
  if (provider.kind !== kind) {
    throw new TypeError(`expected ${kind} provider`);
  }
  if (typeof provider.describe !== "function") {
    throw new TypeError(`${kind} provider must implement describe()`);
  }
  return provider;
}

export function createOpaqueSyncObjectId() {
  const bytes = crypto.getRandomValues(new Uint8Array(18));
  const binary = String.fromCharCode(...bytes);
  bytes.fill(0);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

export function assertOpaqueSyncObjectId(objectId) {
  if (typeof objectId !== "string" || !OPAQUE_SYNC_OBJECT_ID.test(objectId)) {
    throw new TypeError("sync object ID must be canonical 24-character opaque Base64URL");
  }
  return objectId;
}

export function assertEncryptedSyncObject(bytes) {
  if (!(bytes instanceof Uint8Array) || bytes.byteLength < 1 || bytes.byteLength > MAX_SYNC_OBJECT_BYTES) {
    throw new TypeError("encrypted sync object must be a bounded Uint8Array");
  }
  return bytes;
}

export function assertSyncProvider(provider) {
  assertProvider(provider, "sync");
  for (const method of ["listOpaqueObjects", "putEncryptedObject", "getEncryptedObject", "deleteEncryptedObject"]) {
    if (typeof provider[method] !== "function") {
      throw new TypeError(`sync provider must implement ${method}()`);
    }
  }
  return provider;
}

export function assertPublicWorldQuery(query) {
  if (!query || typeof query !== "object" || Array.isArray(query)) {
    throw new TypeError("world query must be an object");
  }
  const inspect = (value, path = "query") => {
    if (!value || typeof value !== "object") return;
    for (const [key, child] of Object.entries(value)) {
      if (FORBIDDEN_WORLD_QUERY_KEYS.has(key)) {
        throw new Error(`WorldBriefProvider query cannot contain private field: ${path}.${key}`);
      }
      inspect(child, `${path}.${key}`);
    }
  };
  inspect(query);
  const publicQuery = {};
  for (const [key, value] of Object.entries(query)) {
    if (!PUBLIC_WORLD_QUERY_KEYS.has(key)) throw new Error(`WorldBriefProvider query has unsupported field: query.${key}`);
    if (key === "categories") {
      if (!Array.isArray(value) || value.length > 20 || value.some((item) =>
        typeof item !== "string" || item.trim() !== item || item.length < 1 || item.length > 80
      )) throw new TypeError("query.categories must be a bounded public string list");
    } else if (value !== null && (typeof value !== "string" || value.trim() !== value || value.length >
        ({ region: 120, timeWindow: 80, language: 35, cursor: 512 })[key])) {
      throw new TypeError(`query.${key} must be a bounded public string`);
    }
  }
  for (const key of PUBLIC_WORLD_QUERY_KEYS) {
    if (Object.hasOwn(query, key)) publicQuery[key] = key === "categories" ? Object.freeze([...query[key]]) : query[key];
  }
  return Object.freeze(publicQuery);
}

export function assertWorldBriefFeed(feed) {
  if (!feed || typeof feed !== "object" || Array.isArray(feed)) {
    throw new TypeError("World Brief feed must be an object");
  }
  if (feed.schema !== "org.foe.world-brief-feed/v1") {
    throw new Error("unsupported World Brief feed schema");
  }
  if (!Array.isArray(feed.briefs) || feed.briefs.length > 100) {
    throw new Error("World Brief feed requires at most 100 briefs");
  }
    for (const [index, brief] of feed.briefs.entries()) {
    const path = `briefs[${index}]`;
    for (const field of ["id", "title", "summary", "region", "sourceTitle", "retrievedAt", "verification"]) {
      if (typeof brief?.[field] !== "string" || brief[field].trim() === "") {
        throw new Error(`${path}.${field} is required`);
      }
    }
    if (!Array.isArray(brief.topics) || brief.topics.length === 0 || brief.topics.length > 20 ||
        brief.topics.some((topic) => typeof topic !== "string" || topic.trim().length < 2 || topic.trim().length > 80)) {
      throw new Error(`${path}.topics must contain public matching terms`);
    }
    if (brief.matchTerms !== undefined && (!Array.isArray(brief.matchTerms) || brief.matchTerms.length > 20 ||
        brief.matchTerms.some((term) => typeof term !== "string" || term.trim().length < 2 || term.trim().length > 80))) {
      throw new Error(`${path}.matchTerms must contain at most 20 bounded public matching terms`);
    }
    if (!["VERIFIED", "LIKELY", "IDEA"].includes(brief.verification)) {
      throw new Error(`${path}.verification is invalid`);
    }
    for (const field of ["retrievedAt", "startsAt", "expiresAt"]) {
      if (brief[field] !== undefined && Number.isNaN(Date.parse(brief[field]))) {
        throw new Error(`${path}.${field} must be an ISO timestamp`);
      }
    }
    if (brief.sourceUrl !== undefined) {
      let url;
      try {
        url = new URL(brief.sourceUrl);
      } catch {
        throw new Error(`${path}.sourceUrl must be a valid HTTPS URL`);
      }
      const authority = typeof brief.sourceUrl === "string" ? brief.sourceUrl.split("//", 2)[1]?.split(/[/?#]/, 1)[0] ?? "" : "";
      if (typeof brief.sourceUrl !== "string" || brief.sourceUrl !== brief.sourceUrl.trim() ||
          !/^https:\/\//i.test(brief.sourceUrl) || brief.sourceUrl.includes("\\") ||
          url.protocol !== "https:" || !url.hostname || !authority || authority.includes("@") ||
          url.username || url.password || brief.sourceUrl.includes("#")) {
        throw new Error(`${path}.sourceUrl must be a valid HTTPS URL without credentials or fragment`);
      }
    }
    for (const [field, maximum] of [["timeMinutes", 1440], ["travelMinutes", 1440], ["minAge", 18], ["maxAge", 25]]) {
      if (brief[field] !== undefined && (!Number.isInteger(brief[field]) || brief[field] < 0 || brief[field] > maximum)) {
        throw new Error(`${path}.${field} is outside the supported range`);
      }
    }
    if (brief.minAge !== undefined && brief.maxAge !== undefined && brief.minAge > brief.maxAge) {
      throw new Error(`${path}.minAge cannot exceed maxAge`);
    }
    if (brief.costBand !== undefined && !["FREE_EXISTING", "FREE", "LOW", "MEDIUM", "HIGH"].includes(brief.costBand)) {
      throw new Error(`${path}.costBand is invalid`);
    }
    if (brief.caregiverEnergy !== undefined && !["NONE", "LOW", "MEDIUM", "HIGH"].includes(brief.caregiverEnergy)) {
      throw new Error(`${path}.caregiverEnergy is invalid`);
    }
    for (const field of ["bookingRequired", "trackingWarning"]) {
      if (brief[field] !== undefined && typeof brief[field] !== "boolean") {
        throw new Error(`${path}.${field} must be boolean`);
      }
    }
    if (brief.verificationNotes !== undefined && (!Array.isArray(brief.verificationNotes) || brief.verificationNotes.length > 8 || brief.verificationNotes.some((note) => typeof note !== "string" || note.trim() === ""))) {
      throw new Error(`${path}.verificationNotes must contain at most 8 non-empty strings`);
    }
    if (brief.sponsorship !== undefined && (typeof brief.sponsorship !== "string" || brief.sponsorship.trim() === "")) {
      throw new Error(`${path}.sponsorship must be a non-empty disclosure`);
    }
  }
  return feed;
}

export function describeProvider({ id, kind, version = "0.1.0", networkDestinations = [], dataCategories = [] }) {
  return Object.freeze({ id, kind, version, networkDestinations, dataCategories });
}

export function issueProviderCapability({ kind, purpose, dataCategories, ttlMilliseconds = 60_000 }) {
  if (typeof kind !== "string" || typeof purpose !== "string") {
    throw new TypeError("provider capability requires kind and purpose");
  }
  if (!Array.isArray(dataCategories) || dataCategories.length === 0) {
    throw new TypeError("provider capability requires explicit data categories");
  }
  if (!Number.isFinite(ttlMilliseconds) || ttlMilliseconds <= 0 || ttlMilliseconds > 300_000) {
    throw new RangeError("provider capability lifetime must be between 1 ms and 5 minutes");
  }
  const capability = Object.freeze({
    id: crypto.randomUUID(),
    kind,
    purpose,
    dataCategories: Object.freeze([...new Set(dataCategories)]),
    expiresAt: new Date(Date.now() + ttlMilliseconds).toISOString()
  });
  issuedCapabilities.add(capability);
  return capability;
}

export function assertProviderCapability(capability, { kind, purpose, requiredCategories = [], now = new Date() }) {
  if (!capability || !issuedCapabilities.has(capability)) throw new Error("provider call has no issued capability");
  if (capability.kind !== kind) throw new Error("provider capability kind mismatch");
  if (capability.purpose !== purpose) throw new Error("provider capability purpose mismatch");
  if (new Date(capability.expiresAt) <= now) throw new Error("provider capability expired");
  for (const category of requiredCategories) {
    if (!capability.dataCategories.includes(category)) throw new Error(`provider capability lacks data category: ${category}`);
  }
  return capability;
}
