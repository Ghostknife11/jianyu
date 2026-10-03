import { assertOpportunity } from "../../foe-schema/src/index.js";
import { currentInterestMentionsTerm, currentInterestRefusesTerm } from "./current-interest.js";
export { currentInterestHasNonRefusalClue, currentInterestMentionsTerm, currentInterestRefusesTerm } from "./current-interest.js";

const EXECUTABLE_FIELDS = new Set(["script", "entrypoint", "executable", "nativeBinary", "postInstall"]);

function findExecutableField(value, path = "pack") {
  if (!value || typeof value !== "object") return null;
  for (const [key, child] of Object.entries(value)) {
    if (EXECUTABLE_FIELDS.has(key)) return `${path}.${key}`;
    const nested = findExecutableField(child, `${path}.${key}`);
    if (nested) return nested;
  }
  return null;
}

export function assertDeclarativePack(pack) {
  if (!pack || typeof pack !== "object" || Array.isArray(pack)) {
    throw new TypeError("pack must be an object");
  }
  if (pack.schema !== "org.foe.pack/v1") {
    throw new TypeError("unsupported pack schema");
  }
  if (typeof pack.id !== "string" || typeof pack.version !== "string") {
    throw new TypeError("pack id and version are required");
  }
  const executableField = findExecutableField(pack);
  if (executableField) {
    throw new Error(`v0.1 Pack must be declarative; found ${executableField}`);
  }
  if (!Array.isArray(pack.opportunities)) {
    throw new TypeError("pack.opportunities must be an array");
  }
  pack.opportunities.forEach(assertOpportunity);
  return pack;
}

export function opportunitiesFromPack(pack, { currentInterest = "" } = {}) {
  assertDeclarativePack(pack);
  const searchable = typeof currentInterest === "string" ? currentInterest.trim() : "";
  if (!searchable) return [];

  return structuredClone(
    pack.opportunities.filter((opportunity) =>
      Array.isArray(opportunity.triggerTerms) &&
      opportunity.triggerTerms
        .map((term) => typeof term === "string" ? term.trim() : "")
        .filter((term) => term.length >= 2)
        .every((term) => !currentInterestRefusesTerm(searchable, term)) &&
      opportunity.triggerTerms.some((term) => currentInterestMentionsTerm(searchable, term))
    )
  );
}
