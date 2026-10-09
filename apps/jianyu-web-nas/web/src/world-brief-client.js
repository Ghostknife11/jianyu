// The World Brief client: an FOE `world-brief` provider over one HTTPS feed.
//
// The request carries no family data at all. The configured feed URL is
// fetched as-is — the region, time window, language, and categories the family
// approved are used to filter locally, never appended to a request — and
// `runOpportunityFlow` then drops any brief whose public terms do not match the
// child's own words. A brief is public information about the world; it is never
// treated as a fact about this child, and the UI must show its source,
// verification state, and any sponsorship.

import { assertWorldBriefFeed, describeProvider } from "../../../../packages/provider-sdk/src/index.js";

export const WORLD_BRIEF_PROVIDER_ID = "org.jianyu.web.world-brief-feed";
const MAX_BRIEFS = 100;

const COST_BANDS = { FREE_EXISTING: "free-existing", FREE: "free", LOW: "low", MEDIUM: "medium", HIGH: "high" };
const ENERGY_BANDS = { NONE: "none", LOW: "low", MEDIUM: "medium", HIGH: "high" };
const VERIFICATIONS = { VERIFIED: "verified", LIKELY: "likely", IDEA: "idea" };

export class WorldBriefError extends Error {
  constructor(message) {
    super(message);
    this.name = "WorldBriefError";
  }
}

function boundedText(value, maximum) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (trimmed === "" || trimmed.length > maximum) return null;
  return trimmed;
}

function band(value, table, fallback) {
  return table[String(value ?? "").toUpperCase()] ?? fallback;
}

function integer(value, { minimum, maximum, fallback }) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) return fallback;
  return value;
}

function isPast(timestamp, now) {
  const parsed = Date.parse(timestamp);
  return Number.isNaN(parsed) ? false : parsed < now.getTime();
}

/** Maps one feed brief onto the public opportunity schema. */
function briefToCandidate(brief, { now }) {
  const title = boundedText(brief.title, 200);
  const whyNow = boundedText(brief.summary, 240);
  if (!title || !whyNow) return null;
  const candidate = {
    schema: "org.foe.opportunity/v1",
    opportunityId: boundedText(brief.id, 120) ?? `world-brief-${title}`,
    title,
    entryPoint: { motivation: "public-world-event", whyNow, startupCost: "low" },
    ecosystem: boundedText(brief.ecosystem, 60) ?? "public-world-event",
    goalAlignment: { primary: "shared", secondary: ["child"] },
    childPull: true,
    requirements: {
      timeMinutes: integer(brief.timeMinutes, { minimum: 5, maximum: 1440, fallback: 60 }),
      costBand: band(brief.costBand, COST_BANDS, "low"),
      caregiverEnergy: band(brief.caregiverEnergy, ENERGY_BANDS, "low"),
      travelMinutes: integer(brief.travelMinutes, { minimum: 0, maximum: 1440, fallback: 0 })
    },
    source: {
      kind: "world-brief",
      publisher: boundedText(brief.sourceTitle, 120) ?? WORLD_BRIEF_PROVIDER_ID,
      retrievedAt: boundedText(brief.retrievedAt, 40) ?? now.toISOString()
    },
    verification: band(brief.verification, VERIFICATIONS, "idea"),
    risks: [],
    score: null,
    explanation: whyNow,
    topics: Array.isArray(brief.topics) ? brief.topics.slice(0, 20) : [],
    sponsorship: boundedText(brief.sponsorship, 120) ?? null
  };
  if (typeof brief.sourceUrl === "string" && brief.sourceUrl.trim() !== "") candidate.source.url = brief.sourceUrl.trim();
  if (typeof brief.expiresAt === "string") candidate.freshUntil = brief.expiresAt;
  if (Number.isInteger(brief.minAge)) candidate.minAge = brief.minAge;
  if (Number.isInteger(brief.maxAge)) candidate.maxAge = brief.maxAge;
  if (typeof brief.bookingRequired === "boolean") candidate.bookingRequired = brief.bookingRequired;
  if (typeof brief.trackingWarning === "boolean") candidate.trackingWarning = brief.trackingWarning;
  return candidate;
}

/**
 * Builds the provider. The feed is fetched once per call; a family that points
 * this at a service on their own network needs the operator to allow private
 * feed addresses on the NAS (`JIANYU_ALLOW_PRIVATE_FEED=1`).
 */
export function createWorldBriefProvider({
  feedUrl,
  api,
  fetchImpl,
  region = "",
  now = () => new Date()
}) {
  const url = boundedText(feedUrl, 2048);
  if (!url) throw new WorldBriefError("请先填写世界信息 feed 地址");

  async function readFeed() {
    if (api) {
      let result;
      try {
        result = await api.proxyFeed({ url });
      } catch (error) {
        throw new WorldBriefError(error instanceof Error ? error.message : "无法获取公开资讯");
      }
      if (!result?.ok) throw new WorldBriefError(`公开资讯服务返回 ${result?.status ?? "未知状态"}`);
      return result.body;
    }
    const impl = fetchImpl ?? globalThis.fetch.bind(globalThis);
    let response;
    try {
      response = await impl(url, { method: "GET", redirect: "error" });
    } catch {
      throw new WorldBriefError("无法获取公开资讯，请检查地址与网络");
    }
    if (!response.ok) throw new WorldBriefError(`公开资讯服务返回 ${response.status}`);
    return response.text();
  }

  return Object.freeze({
    kind: "world-brief",
    describe() {
      return describeProvider({
        id: WORLD_BRIEF_PROVIDER_ID,
        kind: "world-brief",
        version: "0.1.0",
        networkDestinations: [url],
        dataCategories: ["public-region", "public-time-window", "public-language", "public-category"]
      });
    },
    async getBrief(query) {
      const text = await readFeed();
      let feed;
      try {
        feed = assertWorldBriefFeed(JSON.parse(text));
      } catch (error) {
        throw new WorldBriefError(error instanceof Error ? error.message : "公开资讯格式不受支持");
      }
      const reference = now();
      const wanted = boundedText(query?.region, 120) ?? boundedText(region, 120) ?? "";
      const candidates = [];
      for (const brief of feed.briefs.slice(0, MAX_BRIEFS)) {
        const briefRegion = boundedText(brief.region, 120) ?? "";
        if (wanted !== "" && briefRegion !== wanted) continue;
        if (typeof brief.expiresAt === "string" && isPast(brief.expiresAt, reference)) continue;
        const candidate = briefToCandidate(brief, { now: reference });
        if (candidate) candidates.push(candidate);
      }
      return Object.freeze(candidates);
    }
  });
}
