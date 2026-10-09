// The family's own AI provider (BYOK), as an FOE `llm` provider.
//
// The browser builds the minimized task context first and hands over exactly
// that: no child name, no household, no history. The provider never sees the
// family state. Its answer is candidate material only — it is never a fact, a
// diagnosis, a score, a purchase, an enrollment, or an instruction, and the UI
// must say so wherever these options appear.

import { assertOpportunity } from "../../../../packages/foe-schema/src/index.js";
import { describeProvider } from "../../../../packages/provider-sdk/src/index.js";
import { AI_ROUTES } from "./ai-connection.js";

export const LLM_PROVIDER_ID = "org.jianyu.web.byok-llm";
const MAX_CANDIDATES = 5;
const MAX_OPPORTUNITIES = 8;

const BOUNDS = {
  title: 120,
  ecosystem: 60,
  whyNow: 240,
  explanation: 400,
  motivation: 60,
  topic: 80,
  sponsorship: 120
};

const COST_BANDS = ["free-existing", "free", "low", "medium", "high"];
const ENERGY_BANDS = ["none", "low", "medium", "high"];
const VERIFICATIONS = ["verified", "likely", "idea"];
const GOAL_OWNERS = ["child", "caregiver", "shared"];

const INSTRUCTIONS = [
  "你是见隅（Jianyu）家庭机会引擎的一个候选来源。",
  "你会收到一个已经最小化的任务上下文，其中只有家庭批准过的内容。",
  "请只依据这个上下文给出最多 5 个家庭可以一起尝试的入口。",
  "规则：",
  "1. 每个选项必须从孩子当下的兴趣出发，而不是从课程表出发。",
  "2. 不要编造具体的时间、地点、名额、价格、联系方式或链接；无法核实的标为 idea。",
  "3. 不要给出评分、诊断、购买建议、报名建议或指令，也不要把家务和作业包装成机会。",
  "4. 全部使用简体中文。",
  "5. 只输出 JSON，不要输出解释文字，也不要用 Markdown 代码块包住它。",
  '输出格式：{"opportunities":[{"title":"","ecosystem":"","entryPoint":{"motivation":"","whyNow":"","startupCost":"low"},"goalAlignment":{"primary":"child"},"childPull":true,"requirements":{"timeMinutes":30,"costBand":"free-existing","caregiverEnergy":"low","travelMinutes":0},"verification":"idea","explanation":"","topics":[""],"risks":[]}]}',
  "ecosystem 用简短英文短语，例如 digital-game、making、live-sport、books、nature、family-life、nearby-world。",
  "topics 是 2 到 6 个用于匹配孩子原话的中文或英文词。"
].join("\n");

export class AiProviderError extends Error {
  constructor(message) {
    super(message);
    this.name = "AiProviderError";
  }
}

function boundedText(value, maximum) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (trimmed === "" || trimmed.length > maximum) return null;
  return trimmed;
}

function boundedInteger(value, { minimum, maximum, fallback }) {
  if (value === undefined || value === null) return fallback;
  if (!Number.isInteger(value) || value < minimum || value > maximum) return fallback;
  return value;
}

function oneOf(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback;
}

function textList(value, { maximum, itemMaximum }) {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => boundedText(item, itemMaximum))
    .filter((item) => item !== null)
    .slice(0, maximum);
}

/**
 * Removes a Markdown code fence, which some models add even when told not to.
 * Built from a repeated character so the source holds no shell-looking literal.
 */
function stripCodeFence(body) {
  const fence = "`".repeat(3);
  const lines = body.split("\n");
  if (lines.length < 2) return body;
  const first = lines[0].trim();
  const last = lines[lines.length - 1].trim();
  if (first.startsWith(fence) && last === fence) return lines.slice(1, -1).join("\n");
  return body;
}

function parseCompletion(text) {
  const body = String(text ?? "").trim();
  if (body === "") throw new AiProviderError("AI 服务没有返回内容");
  let parsed;
  try {
    parsed = JSON.parse(stripCodeFence(body));
  } catch {
    throw new AiProviderError("AI 服务返回的内容不是有效的 JSON");
  }
  if (Array.isArray(parsed)) return parsed;
  for (const key of ["opportunities", "candidates", "options"]) {
    if (Array.isArray(parsed?.[key])) return parsed[key];
  }
  throw new AiProviderError("AI 服务返回的内容里没有选项列表");
}

/**
 * Maps one raw entry onto the public opportunity schema. An entry that cannot
 * be mapped is dropped and counted, never guessed at: a half-mapped option
 * would put words in the family's mouth.
 */
function mapCandidate(entry, { index, retrievedAt, model }) {
  const title = boundedText(entry?.title, BOUNDS.title);
  const ecosystem = boundedText(entry?.ecosystem, BOUNDS.ecosystem);
  const whyNow = boundedText(entry?.entryPoint?.whyNow, BOUNDS.whyNow);
  const explanation = boundedText(entry?.explanation, BOUNDS.explanation);
  if (!title || !ecosystem || !whyNow || !explanation) return null;
  const startupCost = entry.entryPoint?.startupCost;
  const candidate = {
    schema: "org.foe.opportunity/v1",
    opportunityId: `llm-${index}-${retrievedAt}`,
    title,
    ecosystem,
    entryPoint: {
      motivation: boundedText(entry.entryPoint?.motivation, BOUNDS.motivation) ?? "child-current-pull",
      whyNow,
      startupCost: startupCost === "high" || startupCost === "medium" ? startupCost : "low"
    },
    goalAlignment: { primary: oneOf(entry.goalAlignment?.primary, GOAL_OWNERS, "child") },
    childPull: entry.childPull === true,
    requirements: {
      timeMinutes: boundedInteger(entry.requirements?.timeMinutes, { minimum: 5, maximum: 1440, fallback: 30 }),
      costBand: oneOf(entry.requirements?.costBand, COST_BANDS, "low"),
      caregiverEnergy: oneOf(entry.requirements?.caregiverEnergy, ENERGY_BANDS, "low"),
      travelMinutes: boundedInteger(entry.requirements?.travelMinutes, { minimum: 0, maximum: 1440, fallback: 0 })
    },
    source: {
      kind: "llm",
      publisher: LLM_PROVIDER_ID,
      retrievedAt,
      model
    },
    verification: oneOf(entry.verification, VERIFICATIONS, "idea"),
    risks: [],
    score: null,
    explanation,
    topics: textList(entry.topics, { maximum: 6, itemMaximum: BOUNDS.topic }),
    sponsorship: null
  };
  const freshUntil = boundedText(entry.freshUntil, 40);
  if (freshUntil && !Number.isNaN(Date.parse(freshUntil))) candidate.freshUntil = freshUntil;
  const sponsorship = boundedText(entry.sponsorship, BOUNDS.sponsorship);
  if (sponsorship) candidate.sponsorship = sponsorship;
  try {
    assertOpportunity(candidate);
  } catch {
    return null;
  }
  return candidate;
}

/**
 * Sends one completion request. The proxy route keeps the key in the NAS for
 * the length of this request only; the direct route skips the NAS entirely and
 * is the only way to reach a model server on the family's own network.
 */
async function requestCompletion({ connection, taskContext, api, fetchImpl }) {
  const payload = {
    model: connection.model,
    temperature: 0.4,
    max_tokens: 1200,
    messages: [
      { role: "system", content: INSTRUCTIONS },
      { role: "user", content: JSON.stringify(taskContext) }
    ]
  };
  if (connection.route === AI_ROUTES.direct) {
    const impl = fetchImpl ?? globalThis.fetch.bind(globalThis);
    let response;
    try {
      response = await impl(connection.endpoint, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${connection.apiKey}` },
        body: JSON.stringify(payload),
        redirect: "error"
      });
    } catch {
      throw new AiProviderError("无法连接 AI 服务，请检查地址与网络");
    }
    const text = await response.text();
    if (!response.ok) throw new AiProviderError(`AI 服务返回 ${response.status}`);
    return text;
  }
  if (!api) throw new AiProviderError("这台 NAS 没有可用的 AI 代理");
  let result;
  try {
    result = await api.proxyAiCompletion({ endpoint: connection.endpoint, apiKey: connection.apiKey, payload });
  } catch (error) {
    throw new AiProviderError(error instanceof Error ? error.message : "AI 服务没有响应");
  }
  if (!result?.ok) throw new AiProviderError(`AI 服务返回 ${result?.status ?? "未知状态"}`);
  return result.body;
}

function readCompletionText(body) {
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new AiProviderError("AI 服务返回的内容不是有效的 JSON");
  }
  const content = parsed?.choices?.[0]?.message?.content;
  if (typeof content !== "string") throw new AiProviderError("AI 服务返回的内容里没有消息");
  return content;
}

/**
 * Builds the FOE `llm` provider. `generate` reports what it dropped so the UI
 * can be honest about a partial answer; `generateCandidates` is the shape
 * `runOpportunityFlow` calls.
 */
export function createLlmProvider({ connection, api, fetchImpl, now = () => new Date().toISOString() }) {
  if (!connection) throw new AiProviderError("还没有设置 AI 连接");
  async function generate(taskContext) {
    const retrievedAt = now();
    const body = await requestCompletion({ connection, taskContext, api, fetchImpl });
    const entries = parseCompletion(readCompletionText(body)).slice(0, MAX_OPPORTUNITIES);
    const candidates = [];
    let dropped = 0;
    for (const [index, entry] of entries.entries()) {
      const candidate = mapCandidate(entry, { index, retrievedAt, model: connection.model });
      if (candidate) candidates.push(candidate);
      else dropped += 1;
      if (candidates.length >= MAX_CANDIDATES) break;
    }
    if (candidates.length === 0) {
      throw new AiProviderError(dropped > 0 ? "AI 服务返回的选项无法使用" : "AI 服务没有给出任何选项");
    }
    return Object.freeze({ candidates: Object.freeze(candidates), dropped, retrievedAt });
  }

  return Object.freeze({
    kind: "llm",
    describe() {
      return describeProvider({
        id: LLM_PROVIDER_ID,
        kind: "llm",
        version: "0.1.0",
        networkDestinations: [connection.endpoint],
        dataCategories: ["minimized-task-context"]
      });
    },
    generate,
    async generateCandidates(taskContext) {
      return (await generate(taskContext)).candidates;
    }
  });
}
