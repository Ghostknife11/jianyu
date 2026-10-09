// Browser bootstrap for the NAS web app. No framework and no build step: this
// module renders the screens, owns the unlocked vault, and translates the
// server's refusals into plain family-facing copy.

import { NasApi } from "./session.js";
import { NasFamilyVault, StateConflictError, VaultError, createIndexedDbStore } from "./nas-vault.js";
import { childLifecycle } from "./family-state.js";
import { digestState } from "./nas-crypto.js";
import {
  exportRecoveryBundle,
  parseRecoveryBundle,
  previewRecoveryBundle,
  RecoveryBundleError,
  serializeRecoveryBundle
} from "./recovery-bundle.js";
import {
  AI_ROUTES,
  clearAiConnection,
  createIndexedDbAiConnectionStore,
  describeAiRoute,
  readAiConnection
} from "./ai-connection.js";
import { createLlmProvider, AiProviderError } from "./ai-provider.js";
import { createWorldBriefProvider, WorldBriefError } from "./world-brief-client.js";
import {
  clearWorldConnection,
  createIndexedDbWorldConnectionStore,
  readWorldConnection
} from "./world-connection.js";
import { planDiscovery, runDiscovery, DiscoveryError } from "./discovery-service.js";
import {
  buildTodayRequest,
  laterViewLabel,
  offersLaterView,
  recordChoice,
  recordInterest,
  recordLaterView,
  recordVeto,
  TodayError
} from "./today-service.js";

const api = new NasApi({});
const store = createIndexedDbStore();
// Service configuration is browser-local and sealed with the vault key; it is
// never part of the family state.
const aiConnectionStore = createIndexedDbAiConnectionStore();
const worldConnectionStore = createIndexedDbWorldConnectionStore();

const STAGE_LABELS = {
  "co-play": "4–6 共玩",
  accompany: "7–9 陪同",
  "co-select": "10–12 一起选",
  "hand-over": "13–15 交给孩子",
  graduation: "16+ 成年交接"
};

const STAGE_AUTHORITY = {
  "caregiver": "家长主导，孩子可以否决",
  joint: "家长和孩子一起选",
  child: "孩子主导",
  self: "本人主导"
};

const COST_LABELS = {
  "free-existing": "不花钱",
  free: "免费",
  low: "花费很低",
  medium: "花费中等",
  high: "花费较高"
};

const ENERGY_LABELS = {
  none: "不费力",
  low: "省力",
  medium: "一般精力",
  high: "比较费神"
};

const VERIFICATION_LABELS = {
  verified: "发布方标注已核实",
  likely: "发布方标注较可靠",
  idea: "只是一个想法，待家庭自己判断"
};

const SOURCE_LABELS = {
  pack: "离线演示",
  llm: "AI 负责发现",
  "world-brief": "世界信息"
};

const state = {
  screen: "boot",
  route: "today",
  vault: null,
  message: null,
  conflict: false,
  pendingErase: false,
  // The recovery bundle in flight: shown once at export, previewed then
  // confirmed on import. It is deliberately not a merge.
  recovery: null
};

// The Today flow. `drafts` keeps one unsent sentence per child so switching the
// viewed person never loses what was typed, and never shows one child's words
// under another child's name.
const today = {
  childId: null,
  drafts: new Map(),
  expandedDetails: new Set(),
  pickerOpen: false,
  conditionsOpen: false,
  receiptOpen: false,
  // compose → confirm → result → acknowledged. `confirm` is the step where the
  // family sees exactly what would be sent before any provider is contacted.
  phase: "compose",
  plan: null,
  result: null,
  sourceEventId: null,
  interestText: "",
  sources: { ai: false, world: false },
  conditions: { timeMinutes: "", costBand: "", caregiverEnergy: "", travelMinutesMax: "" },
  pendingVeto: null,
  saving: false,
  viewDraft: "",
  lastChoice: null,
  // A warm caution that must be read, not auto-dismissed.
  caution: null
};


function el(tag, attributes = {}, children = []) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (name === "class") node.className = value;
    else if (name === "text") node.textContent = value;
    else if (name.startsWith("on") && typeof value === "function") {
      // Event type names are case-sensitive, so "onClick" must become "click".
      node.addEventListener(name.slice(2).toLowerCase(), value);
    }
    else if (value !== null && value !== undefined && value !== false) node.setAttribute(name, String(value));
  }
  for (const child of [children].flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function card(tone, children) {
  return el("section", { class: `card card--${tone}` }, children);
}

function field(label, control) {
  return el("label", { class: "field" }, [
    el("span", { class: "field-label", text: label }),
    control
  ]);
}

function input(attributes) {
  return el("input", attributes);
}

function button(label, onClick, tone = "filled") {
  return el("button", { class: tone === "text" ? "button--text" : "", type: "button", onClick }, label);
}

/**
 * The shared optional-details toggle. Every page uses the same `查看…` / `收起…`
 * wording and the same expanded state, so a disclosure never looks like a link
 * or a second kind of action. The caller owns the flag so the label and the
 * body can never disagree.
 */
function disclosure(summary, expanded, onToggle, body) {
  return el("div", { class: "disclosure" }, [
    button(expanded ? `收起${summary}` : `查看${summary}`, onToggle, "text"),
    expanded ? el("div", { class: "disclosure-body" }, body) : null
  ]);
}

/** A status capsule. Neutral by default; tones come from the semantic set. */
function pill(text, tone = "neutral") {
  return el("span", { class: `pill pill--${tone}`, text });
}

function requirementLine(candidate) {
  const parts = [];
  const requirements = candidate.requirements ?? {};
  if (Number.isInteger(requirements.timeMinutes)) parts.push(`${requirements.timeMinutes} 分钟`);
  if (requirements.costBand) parts.push(COST_LABELS[requirements.costBand] ?? requirements.costBand);
  if (requirements.caregiverEnergy) parts.push(ENERGY_LABELS[requirements.caregiverEnergy] ?? requirements.caregiverEnergy);
  if (Number.isInteger(requirements.travelMinutes) && requirements.travelMinutes > 0) {
    parts.push(`路上约 ${requirements.travelMinutes} 分钟`);
  }
  if (Number.isInteger(candidate.minAge) || Number.isInteger(candidate.maxAge)) {
    parts.push(`适合 ${candidate.minAge ?? "?"}–${candidate.maxAge ?? "?"} 岁`);
  }
  return parts.join(" · ");
}

/** Turns a thrown value into a sentence a family member can act on. */
function describe(error) {
  if (error instanceof RecoveryBundleError || error instanceof VaultError) return error.message;
  if (error instanceof StateConflictError) return error.message;
  return "操作没有完成，请再试一次";
}

/** A stable fingerprint of the unlocked state, used to invalidate a preview. */
function digestOf(vault) {
  return digestState(vault.state);
}

function messageCard() {
  if (!state.message) return null;
  return card("danger", [el("p", { class: "card-text", text: state.message })]);
}

function conflictCard() {
  if (!state.conflict) return null;
  return card("human-decision", [
    el("h2", { class: "card-title", text: "另一台设备已经更改过家庭记录" }),
    el("p", {
      class: "card-text",
      text: "这台 NAS 同一时间只保留一位活跃写入者。重载会把两边的记录合并到一起，不会丢掉任何一方的内容。"
    }),
    button("重载并合并", async () => {
      await reloadFromServer();
    })
  ]);
}

async function reloadFromServer() {
  state.conflict = false;
  state.message = null;
  try {
    await state.vault.reloadFromServer();
    render();
  } catch (error) {
    state.message = error instanceof Error ? error.message : "重载失败";
    render();
  }
}

/** Saves the unlocked state and turns a stale write into a reload prompt. */
async function saveState() {
  if (!state.vault) return;
  try {
    await state.vault.save();
    state.message = null;
    render();
  } catch (error) {
    if (error instanceof StateConflictError) {
      state.conflict = true;
      state.message = null;
    } else {
      state.message = error instanceof Error ? error.message : "保存失败";
    }
    render();
  }
}

function renderBoot() {
  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "正在载入家庭保险箱" }),
    card("preview", [
      el("p", { class: "card-text", text: "解密和判断都发生在你自己的浏览器里，这台 NAS 只保存密文。" })
    ])
  ]);
}

function renderCreate() {
  const familyName = input({ name: "familyName", autocomplete: "off", required: true });
  const caregiverName = input({ name: "caregiverName", autocomplete: "off", required: true });
  const childName = input({ name: "childName", autocomplete: "off" });
  const birthDate = input({ name: "birthDate", type: "date" });
  const passphrase = input({ name: "passphrase", type: "password", autocomplete: "new-password", required: true });
  const confirmation = input({ name: "confirmation", type: "password", autocomplete: "new-password", required: true });

  async function submit() {
    state.message = null;
    if (passphrase.value !== confirmation.value) {
      state.message = "两次输入的口令不一致";
      render();
      return;
    }
    try {
      state.vault = await NasFamilyVault.create({
        passphrase: passphrase.value,
        familyName: familyName.value,
        caregiverName: caregiverName.value,
        childName: childName.value,
        birthDate: birthDate.value
      }, { api, store });
      await enterApp();
    } catch (error) {
      state.message = error instanceof VaultError || error instanceof TypeError ? error.message : "建库失败，请再试一次";
      render();
    }
  }

  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "建立家庭保险箱" }),
    el("p", { class: "page-description", text: "先给这个家庭一个称呼，再设一个至少 8 个字符的口令。口令只在这台设备上使用，NAS 无法帮你重置。" }),
    messageCard(),
    card("neutral", [
      field("家庭称呼", familyName),
      field("家长称呼", caregiverName),
      field("孩子称呼（可稍后再填）", childName),
      field("孩子出生日期", birthDate),
      field("保险箱口令", passphrase),
      field("再输一次口令", confirmation),
      button("建立保险箱", submit)
    ])
  ]);
}

function renderUnlock() {
  const passphrase = input({ name: "passphrase", type: "password", autocomplete: "current-password", required: true });

  async function submit() {
    state.message = null;
    try {
      state.vault = await NasFamilyVault.unlock({ passphrase: passphrase.value }, { api, store });
      await enterApp();
    } catch (error) {
      state.message = error instanceof VaultError ? error.message : "解锁失败，请再试一次";
      render();
    }
  }

  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "解锁家庭保险箱" }),
    el("p", { class: "page-description", text: "这台浏览器里已经有一份加密记录，输入口令即可打开。" }),
    messageCard(),
    card("neutral", [field("保险箱口令", passphrase), button("解锁", submit)])
  ]);
}

/** Reads the browser-local service settings so the source list is honest. */
async function refreshSources() {
  const vault = state.vault;
  if (!vault) return;
  const [ai, world] = await Promise.all([
    readAiConnection({ vault, store: aiConnectionStore }).catch(() => null),
    readWorldConnection({ vault, store: worldConnectionStore }).catch(() => null)
  ]);
  today.sources = { ai: ai !== null, world: world !== null };
}

function currentChild() {
  const children = state.vault?.state.children ?? [];
  if (children.length === 0) return null;
  return children.find((child) => child.id === today.childId) ?? children[0];
}

function currentRecorder() {
  const members = state.vault?.state.members ?? [];
  return members.find((member) => member.id === state.vault.state.preferences.recorderId)
    ?? members.find((member) => member.role === "caregiver")
    ?? members[0]
    ?? null;
}

/** Which lifecycle band's voice the composer uses for this child. */
function childStage(child) {
  const lifecycle = childLifecycle(child);
  return {
    lifecycle,
    label: lifecycle.stage ? STAGE_LABELS[lifecycle.stage] : "未满 4 岁",
    authority: lifecycle.authority ? STAGE_AUTHORITY[lifecycle.authority.primary] : "暂不提供发现",
    selfSigned: lifecycle.stage === "hand-over" || lifecycle.stage === "graduation"
  };
}

function resetToday() {
  today.phase = "compose";
  today.plan = null;
  today.result = null;
  today.sourceEventId = null;
  today.interestText = "";
  today.pendingVeto = null;
  today.viewDraft = "";
  today.lastChoice = null;
  today.caution = null;
  today.conditionsOpen = false;
  today.receiptOpen = false;
}

/** Saves the unlocked state and turns a stale write into a reload prompt. */
async function saveTodayState() {
  today.saving = true;
  render();
  try {
    await state.vault.save();
    today.saving = false;
    return true;
  } catch (error) {
    today.saving = false;
    if (error instanceof StateConflictError) {
      state.conflict = true;
    } else {
      today.caution = error instanceof Error ? error.message : "本机保存没有完成，请再试一次";
    }
    return false;
  } finally {
    render();
  }
}

/**
 * Step one of a discovery: write the child's words and the approved scope into
 * the encrypted vault. If that write fails, no provider is contacted at all.
 */
async function submitInterest() {
  const child = currentChild();
  if (!child) {
    today.caution = "请先到「家庭」页添加一个孩子";
    render();
    return;
  }
  const text = today.interestText.trim();
  if (text === "") {
    today.caution = "请先写一句孩子当下在意的内容";
    render();
    return;
  }
  today.drafts.set(child.id, text);
  today.saving = true;
  render();
  try {
    const conditions = {};
    for (const [key, value] of Object.entries(today.conditions)) {
      if (value !== "" && value !== null && value !== undefined) conditions[key] = Number(value) || value;
    }
    const event = recordInterest(state.vault, {
      childId: child.id,
      expression: text,
      authorId: currentRecorder()?.id,
      constraints: Object.keys(conditions).length > 0 ? conditions : null,
      approvedCategories: selectedCategories(),
      sourceKind: today.sources.ai ? "byok-ai" : today.sources.world ? "world-brief" : "offline-demo"
    });
    const saved = await saveTodayState();
    if (!saved) return;
    today.sourceEventId = event.eventId;
    today.phase = "confirm";
    today.caution = null;
  } catch (error) {
    today.caution = error instanceof TodayError || error instanceof Error ? error.message : "这句话没有记下来，请再试一次";
  }
  render();
}

/** The categories this attempt would send, in the order the family approved. */
function selectedCategories() {
  if (today.sources.ai) return ["current-interest", "age-band", "practical-constraints"];
  return ["current-interest"];
}

/**
 * Step two: the family has seen what would be sent and confirmed it. The
 * provider is built here, after that confirmation, and nowhere else.
 */
async function runConfirmedDiscovery() {
  const child = currentChild();
  if (!child) return;
  today.saving = true;
  today.caution = null;
  render();
  try {
    const request = buildTodayRequest(child, {
      interest: today.interestText,
      constraints: today.conditions.timeMinutes === "" ? undefined : {
        timeMinutes: Number(today.conditions.timeMinutes) || undefined,
        travelMinutesMax: Number(today.conditions.travelMinutesMax) || undefined,
        costBand: today.conditions.costBand || undefined,
        caregiverEnergy: today.conditions.caregiverEnergy || undefined
      },
      worldQuery: { region: "" }
    });
    const sources = { offline: true };
    if (today.sources.ai) {
      const connection = await readAiConnection({ vault: state.vault, store: aiConnectionStore });
      if (!connection) throw new AiProviderError("这台浏览器里还没有保存 AI 连接");
      sources.ai = createLlmProvider({ connection, api });
    }
    if (today.sources.world) {
      const connection = await readWorldConnection({ vault: state.vault, store: worldConnectionStore });
      if (!connection) throw new WorldBriefError("这台浏览器里还没有保存世界信息 feed 地址");
      sources.world = createWorldBriefProvider({
        feedUrl: connection.feedUrl,
        region: connection.region,
        api: connection.route === "proxy" ? api : undefined
      });
    }
    const outcome = await runDiscovery({ request, sources, options: { limit: 5 } });
    today.result = outcome.result;
    today.plan = outcome.plan;
    today.phase = "result";
  } catch (error) {
    today.result = null;
    today.phase = "compose";
    today.caution = error instanceof DiscoveryError || error instanceof Error ? error.message : "这次寻找没有完成";
  }
  today.saving = false;
  render();
}

async function chooseDoor(candidate) {
  const child = currentChild();
  if (!child) return;
  try {
    const choice = recordChoice(state.vault, {
      childId: child.id,
      opportunity: candidate,
      sourceEventId: today.sourceEventId,
      authorId: currentRecorder()?.id
    });
    if (await saveTodayState()) {
      today.lastChoice = choice;
      today.phase = "acknowledged";
      today.result = null;
      today.plan = null;
      today.drafts.delete(child.id);
      today.interestText = "";
    }
  } catch (error) {
    today.caution = error instanceof TodayError || error instanceof Error ? error.message : "这个决定没有记下来";
  }
  render();
}

async function vetoDoor(candidate) {
  const child = currentChild();
  if (!child) return;
  try {
    await recordVeto(state.vault, {
      childId: child.id,
      opportunity: candidate,
      sourceEventId: today.sourceEventId,
      authorId: currentRecorder()?.id
    });
    if (await saveTodayState()) {
      today.pendingVeto = null;
      today.lastChoice = null;
      today.phase = "compose";
      today.result = null;
      today.plan = null;
      today.caution = "已经记下这份拒绝。以后 AI 可能仍会提出类似的入口，每次调用前你都可以再否决。";
      today.drafts.delete(child.id);
      today.interestText = "";
    }
  } catch (error) {
    today.caution = error instanceof TodayError || error instanceof Error ? error.message : "这份拒绝没有记下来";
  }
  render();
}

async function keepLaterView() {
  const choice = today.lastChoice;
  const child = currentChild();
  if (!choice || !child) return;
  const text = today.viewDraft.trim();
  if (text === "") {
    today.caution = "请先写一句当下的看法，也可以直接关掉";
    render();
    return;
  }
  try {
    await recordLaterView(state.vault, {
      choiceId: choice.id,
      value: text,
      authorId: currentRecorder()?.id
    });
    today.viewDraft = "";
    if (await saveTodayState()) {
      today.lastChoice = state.vault.state.choices.find((item) => item.id === choice.id) ?? choice;
      today.phase = "compose";
      resetToday();
    }
  } catch (error) {
    today.caution = error instanceof TodayError || error instanceof Error ? error.message : "这个看法没有记下来";
  }
  render();
}

/** The child switcher. Collapsed to one row plus an explicit control. */
function childSwitcher() {
  const children = state.vault.state.children;
  const child = currentChild();
  if (!child) {
    return el("p", { class: "card-text", text: "还没有添加孩子。可以到「家庭」页添加，这次也可以什么都不做。" });
  }
  const stage = childStage(child);
  const row = el("div", { class: "switcher-row" }, [
    el("div", { class: "switcher-who" }, [
      el("span", { class: "list-title", text: child.displayName }),
      el("span", { class: "list-meta", text: `${stage.lifecycle.age} 岁 · ${stage.label}` })
    ]),
    button("切换查看人", () => {
      today.pickerOpen = !today.pickerOpen;
      render();
    }, "text")
  ]);
  if (!today.pickerOpen) return row;
  return el("div", {}, [
    row,
    el("div", { class: "picker" }, children.map((item) => el("button", {
      class: item.id === child.id ? "picker-item picker-item--active" : "picker-item",
      type: "button",
      onClick: () => {
        today.childId = item.id;
        today.pickerOpen = false;
        resetToday();
        // After the reset, so the draft this child was typing survives the
        // switch and no other child's words appear under this child's name.
        today.interestText = today.drafts.get(item.id) ?? "";
        render();
      }
    }, [
      el("span", { class: "list-title", text: item.displayName }),
      el("span", { class: "list-meta", text: childStage(item).label })
    ])))
  ]);
}

/** The composer: the child's own words first, everything else optional. */
function composerCard() {
  const child = currentChild();
  const stage = child ? childStage(child) : null;
  const expression = el("textarea", {
    name: "expression",
    rows: 2,
    maxlength: 800,
    placeholder: "孩子最近主动说起的一件事、一个问题，或一个自愿的行动",
    "aria-label": "孩子当下在意的内容"
  });
  // The draft lives outside the DOM so switching the viewed person never loses
  // it and never shows one child's words under another child's name.
  expression.value = today.interestText;
  expression.addEventListener("input", () => {
    today.interestText = expression.value;
    if (child) today.drafts.set(child.id, expression.value);
  });

  const conditions = {};
  for (const [key, value] of Object.entries(today.conditions)) {
    conditions[key] = value;
  }

  const timeField = el("input", { name: "timeMinutes", type: "number", min: 5, max: 1440, step: 5, inputmode: "numeric" });
  timeField.value = conditions.timeMinutes;
  timeField.addEventListener("input", () => { today.conditions.timeMinutes = timeField.value; });
  const travelField = el("input", { name: "travelMinutesMax", type: "number", min: 0, max: 1440, step: 5, inputmode: "numeric" });
  travelField.value = conditions.travelMinutesMax;
  travelField.addEventListener("input", () => { today.conditions.travelMinutesMax = travelField.value; });
  const costField = el("select", { name: "costBand" },
    ["", "free-existing", "free", "low", "medium", "high"].map((value) =>
      el("option", { value, selected: today.conditions.costBand === value },
        value === "" ? "不限" : COST_LABELS[value])));
  costField.addEventListener("change", () => { today.conditions.costBand = costField.value; });
  const energyField = el("select", { name: "caregiverEnergy" },
    ["", "none", "low", "medium", "high"].map((value) =>
      el("option", { value, selected: today.conditions.caregiverEnergy === value },
        value === "" ? "不限" : ENERGY_LABELS[value])));
  energyField.addEventListener("change", () => { today.conditions.caregiverEnergy = energyField.value; });

  const sourceSummary = today.sources.ai
    ? "AI 负责发现 · 每次调用前单独确认"
    : today.sources.world
      ? "世界信息 · 每次调用前单独确认"
      : "离线演示 · 不发送、不保存";

  return card("discovery", [
    el("h2", { class: "card-title", text: "这次" }),
    childSwitcher(),
    field("孩子当下在意的内容", expression),
    // A count only near the limit, and it is an input boundary, never progress.
    today.interestText.length > 700
      ? el("p", { class: "card-text card-text--quiet", text: `还可以写 ${800 - today.interestText.length} 个字符` })
      : null,
    el("p", { class: "card-text card-text--quiet", text: sourceSummary }),
    disclosure("这次的条件", today.conditionsOpen, () => {
      today.conditionsOpen = !today.conditionsOpen;
      render();
    }, [
      el("p", { class: "card-text card-text--quiet", text: "不填就是不限制。条件只在本机筛选时使用。" }),
      field("可以花的时间（分钟）", timeField),
      field("路上能接受的时间（分钟）", travelField),
      field("花费", costField),
      field("需要的精力", energyField)
    ]),
    button(today.saving ? "正在保存…" : "记下这句话，看看有几扇门", submitInterest, "filled"),
    stage && !stage.lifecycle.discoveryOffered
      ? el("p", { class: "card-text", text: "未满 4 岁的孩子暂不提供发现，记录本身会一直保留。" })
      : null
  ]);
}

/**
 * The per-call confirmation. It names the roles plainly, says what would be
 * sent and where, and repeats the possible charge before anything is sent.
 */
function confirmCard() {
  const plan = today.plan;
  const usingAi = today.sources.ai;
  const usingWorld = today.sources.world;
  const lines = [
    "AI 找入口，本机筛选；你/你们来选。",
    usingAi ? "会用你保存的 AI 服务发出一次请求，服务方可能会计费。" : null,
    usingWorld ? "会向你保存的世界信息 feed 发出一次请求，请求里只有地区、时间范围、语言和类别。" : null,
    usingAi || usingWorld ? "请求前会先把这句话和这次批准的范围写进保险箱；写不进去就不会发出请求。" : null,
    "离线演示不发送任何内容，也不保存任何结果。"
  ].filter(Boolean);

  const receipt = [
    el("p", { class: "card-text card-text--quiet", text: "会发送的内容" }),
    el("ul", { class: "list" }, [
      el("li", { class: "list-row" }, [
        el("span", { class: "list-title", text: "孩子当下在意的内容" }),
        el("span", { class: "list-meta", text: plan?.currentInterest ?? "" })
      ]),
      usingAi
        ? el("li", { class: "list-row" }, [
          el("span", { class: "list-title", text: "AI 任务上下文（仅此这些）" }),
          el("span", { class: "list-meta", text: JSON.stringify(plan?.taskContext ?? {}) })
        ])
        : null,
      usingWorld
        ? el("li", { class: "list-row" }, [
          el("span", { class: "list-title", text: "世界信息查询（仅此这些）" }),
          el("span", { class: "list-meta", text: JSON.stringify(plan?.worldQuery ?? {}) })
        ])
        : null
    ].filter(Boolean)),
    el("p", {
      class: "card-text card-text--quiet",
      text: "不包含孩子和家庭的称呼、编号、出生日期，也不包含家庭历史。你自己写在句子里的名字或地址，本机检查无法识别，可能会一起发送。"
    })
  ];

  return card("human-decision", [
    el("h2", { class: "card-title", text: "确认这次寻找" }),
    el("div", {}, lines.map((line) => el("p", { class: "card-text", text: line }))),
    disclosure("会发送的内容", today.receiptOpen, () => {
      today.receiptOpen = !today.receiptOpen;
      render();
    }, receipt),
    el("div", { class: "button-row" }, [
      button(today.saving ? "正在寻找…" : "确认，开始这次寻找", runConfirmedDiscovery, "filled"),
      button("先不", () => {
        today.phase = "compose";
        render();
      }, "text")
    ])
  ]);
}

/** One door. No ordinal number, no ranking, no implied quality order. */
function doorCard(candidate) {
  const sourceLabel = SOURCE_LABELS[candidate.source?.kind] ?? "其他来源";
  const verification = VERIFICATION_LABELS[candidate.verification] ?? "来源待确认";
  const detailsOpen = today.expandedDetails.has(candidate.opportunityId);
  const provenance = el("div", { class: "inset" }, [
    el("p", { class: "card-text card-text--quiet", text: `来源：${sourceLabel}` }),
    el("p", { class: "card-text card-text--quiet", text: verification }),
    candidate.source?.publisher
      ? el("p", { class: "card-text card-text--quiet", text: `发布方：${candidate.source.publisher}` })
      : null,
    candidate.sponsorship
      ? el("p", { class: "card-text card-text--quiet", text: `赞助信息：${candidate.sponsorship}` })
      : null,
    candidate.bookingRequired === true
      ? el("p", { class: "card-text card-text--quiet", text: "可能需要提前报名，行动前请自行确认" })
      : null,
    candidate.trackingWarning === true
      ? el("p", { class: "card-text card-text--quiet", text: "发布方提示这会留下可追踪的记录" })
      : null
  ].filter(Boolean));

  return el("li", { class: "card card--neutral door" }, [
    el("h3", { class: "door-title", text: candidate.title }),
    el("p", { class: "card-text", text: candidate.explanation ?? "" }),
    requirementLine(candidate) === "" ? null : el("p", { class: "card-text card-text--quiet", text: requirementLine(candidate) }),
    provenance,
    disclosure("完整说明", detailsOpen, () => {
      if (detailsOpen) today.expandedDetails.delete(candidate.opportunityId);
      else today.expandedDetails.add(candidate.opportunityId);
      render();
    }, [
      el("p", { class: "card-text", text: candidate.entryPoint?.whyNow ?? "" })
    ]),
    el("div", { class: "button-row" }, [
      button("选这个", () => chooseDoor(candidate), "filled"),
      button("孩子不想要", () => {
        today.pendingVeto = candidate;
        render();
      }, "text")
    ])
  ].filter(Boolean));
}

/** 留白 is a full decision card with the same weight as any door. */
function nothingCard() {
  return el("li", { class: "card card--neutral door" }, [
    el("h3", { class: "door-title", text: "什么都不做" }),
    el("p", { class: "card-text", text: "选它不需要时间、花费或家长的精力。留白是一个完整的选择，不是放弃，也不会留下任何欠账。" }),
    button("这次就什么都不做", () => chooseDoor(today.result?.nothing ?? {}), "filled")
  ]);
}

function resultCard() {
  const result = today.result;
  if (!result) return null;
  const selected = result.selected ?? [];
  const failed = (result.provenance ?? []).filter((entry) => entry.reason);

  const description = selected.length === 0
    ? "这一次没有可以显示的入口。留白仍然是今天的一个完整选择。"
    : selected.length === 1
      ? "这一次有 1 个入口，加上留白这一个选择。"
      : `这一次有 ${selected.length} 个不同方向的入口，加上留白这一个选择。`;

  const overview = selected.length >= 2
    ? el("div", { class: "inset" }, [
      el("p", { class: "card-text card-text--quiet", text: "这次看见的门" }),
      el("ul", { class: "list" }, selected.map((item) => el("li", { class: "list-row" }, [
        el("span", { class: "list-title", text: item.candidate.title }),
        el("span", { class: "list-meta", text: SOURCE_LABELS[item.candidate.source?.kind] ?? "其他来源" })
      ])))
    ])
    : null;

  return el("div", {}, [
    el("p", { class: "page-description", text: description }),
    failed.length > 0
      ? card("human-decision", [
        el("p", { class: "card-text", text: "有来源没有完成，这次看到的结果不完整。可以调整后再试，也可以直接选留白。" })
      ])
      : null,
    overview,
    el("ul", { class: "door-list" }, [
      ...selected.map((item, index) => doorCard(item.candidate, index)),
      nothingCard()
    ]),
    button("换个线索", () => {
      resetToday();
      today.interestText = "";
      render();
    }, "text")
  ]);
}

/** The veto confirmation. It distinguishes the child's refusal from a skip. */
function vetoCard() {
  const candidate = today.pendingVeto;
  if (!candidate) return null;
  return card("human-decision", [
    el("h2", { class: "card-title", text: "确认记下这份拒绝？" }),
    el("p", { class: "card-text", text: `「${candidate.title}」` }),
    el("p", {
      class: "card-text",
      text: "这会作为孩子明确的拒绝保存下来，和家长只是暂时不想做是两回事。以后 AI 仍可能提出类似的入口，每次调用前你都可以再否决。"
    }),
    el("div", { class: "button-row" }, [
      button("确认记下", () => vetoDoor(candidate), "filled"),
      button("取消", () => {
        today.pendingVeto = null;
        render();
      }, "text")
    ])
  ]);
}

/** The acknowledgement after a saved decision, with the optional later view. */
function acknowledgedCard() {
  const choice = today.lastChoice;
  const child = currentChild();
  if (!choice || !child) return null;
  const nothing = choice.status === "nothing";
  const stage = childStage(child);
  const canLeaveView = offersLaterView(child);

  const laterViewField = el("textarea", { name: "laterView", rows: 2, maxlength: 500, "aria-label": "后来的看法" });
  laterViewField.value = today.viewDraft;
  laterViewField.addEventListener("input", () => {
    today.viewDraft = laterViewField.value;
  });

  return card(nothing ? "neutral" : "discovery", [
    el("h2", { class: "card-title", text: nothing ? "这次就停在这里" : "已记下这个选择" }),
    el("p", {
      class: "card-text",
      text: nothing
        ? "不需要把兴趣变成安排，也不会产生任何欠账。下一句话由孩子自己开始。"
        : `已记下「${choice.opportunity.title}」。这只是记下家庭做了这个选择，不代表活动已经发生或孩子一定喜欢。`
    }),
    canLeaveView
      ? el("div", {}, [
        el("h3", { class: "card-title", text: laterViewLabel(child) }),
        el("p", {
          class: "card-text card-text--quiet",
          text: stage.selfSigned
            ? "可以现在写，也可以试过之后再写。写不写都可以。"
            : "孩子如果说过什么，可以在这里代记一句；原话由孩子说出。"
        }),
        field("后来的看法", laterViewField),
        button(today.saving ? "正在保存看法…" : "留下这个看法", keepLaterView, "filled")
      ])
      : el("p", { class: "card-text card-text--quiet", text: "这个阶段不再追加关于童年的新看法。" }),
    button("回到这次", () => {
      resetToday();
      render();
    }, "text")
  ]);
}

function renderToday() {
  const vault = state.vault;
  if (!vault) return el("div", { class: "page" });
  if (today.childId === null) today.childId = vault.state.children[0]?.id ?? null;

  const body = [];
  if (today.caution) {
    body.push(card("human-decision", [
      el("p", { class: "card-text", text: today.caution }),
      button("知道了", () => {
        today.caution = null;
        render();
      }, "text")
    ]));
  }
  body.push(conflictCard());
  if (today.pendingVeto) body.push(vetoCard());
  else if (today.phase === "compose") body.push(composerCard());
  else if (today.phase === "confirm") body.push(confirmCard());
  else if (today.phase === "result") body.push(resultCard());
  else if (today.phase === "acknowledged") body.push(acknowledgedCard());

  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "今天" }),
    el("p", {
      class: "page-description",
      text: "从孩子当下的一句话、一个问题或一个自愿的行动开始，课程表排在后面。留白始终是一个完整的选择。"
    }),
    ...body
  ]);
}

function renderFamily() {
  const vault = state.vault;
  const members = vault.state.members;
  const recorderId = vault.state.preferences.recorderId ?? members[0]?.id ?? "";

  const childName = input({ name: "childName", autocomplete: "off" });
  const birthDate = input({ name: "birthDate", type: "date" });
  const caregiverName = input({ name: "caregiverName", autocomplete: "off" });
  const recorder = el("select", { name: "recorder" },
    members.map((member) => el("option", { value: member.id, selected: member.id === recorderId }, member.displayName))
  );

  async function addChildSubmit() {
    state.message = null;
    try {
      vault.addChild({ displayName: childName.value, birthDate: birthDate.value, authorId: recorder.value });
      childName.value = "";
      birthDate.value = "";
      await saveState();
    } catch (error) {
      state.message = error instanceof Error ? error.message : "添加孩子失败";
      render();
    }
  }

  async function addCaregiverSubmit() {
    state.message = null;
    try {
      vault.addCaregiver({ displayName: caregiverName.value, authorId: recorder.value });
      caregiverName.value = "";
      await saveState();
    } catch (error) {
      state.message = error instanceof Error ? error.message : "添加成员失败";
      render();
    }
  }

  async function changeRecorder() {
    vault.state.preferences.recorderId = recorder.value;
    await saveState();
  }

  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "家庭" }),
    el("p", { class: "page-description", text: "孩子、家长和共同目标分开记录。这里的署名只是记录者，不是身份验证。" }),
    conflictCard(),
    messageCard(),
    card("neutral", [
      el("h2", { class: "card-title", text: "家庭成员" }),
      el("ul", { class: "list" }, members.map((member) => el("li", { class: "list-row" }, [
        el("span", { class: "list-title", text: member.displayName }),
        el("span", { class: "list-meta", text: member.role === "child" ? "孩子" : member.role === "caregiver" ? "家长" : member.role })
      ]))),
      el("h2", { class: "card-title", text: "新记录由谁署名" }),
      field("记录者", recorder),
      button("保存记录者", changeRecorder, "text")
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "添加孩子" }),
      field("孩子称呼", childName),
      field("出生日期", birthDate),
      button("添加孩子", addChildSubmit)
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "添加家长" }),
      field("家长称呼", caregiverName),
      button("添加家长", addCaregiverSubmit)
    ])
  ]);
}

function renderSettings() {
  const vault = state.vault;
  const bundleFile = input({ name: "bundleFile", type: "file", accept: "application/json,.json" });
  const bundleCode = input({ name: "bundleCode", autocomplete: "off", placeholder: "例如 23456-23456-23456-23456" });
  const bundlePassphrase = input({ name: "bundlePassphrase", type: "password", autocomplete: "new-password" });

  async function lock() {
    await vault.lock();
    state.vault = null;
    state.screen = "unlock";
    render();
  }

  async function erase() {
    if (!state.pendingErase) {
      state.pendingErase = true;
      render();
      return;
    }
    await vault.eraseEverywhere();
    state.vault = null;
    state.pendingErase = false;
    state.screen = "create";
    render();
  }

  async function registerOnServer() {
    state.message = null;
    try {
      await vault.registerOnServer();
      state.message = "已经登记到这台服务器，家庭记录会以密文保存在这里。";
    } catch (error) {
      state.message = describe(error);
    }
    render();
  }

  async function exportBundle() {
    state.message = null;
    try {
      const { bundle, recoveryCode } = await exportRecoveryBundle({ vault });
      const blob = new Blob([serializeRecoveryBundle(bundle)], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = el("a", { href: url, download: "jianyu-recovery-bundle.json" });
      document.body.append(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      // Shown once, and never stored: losing it with the passphrase loses the vault.
      state.recovery = { mode: "exported", recoveryCode };
    } catch (error) {
      state.message = describe(error);
    }
    render();
  }

  async function verifyBundle() {
    state.message = null;
    const file = bundleFile.files?.[0];
    if (!file) {
      state.message = "请先选择恢复包文件";
      render();
      return;
    }
    try {
      const bundle = parseRecoveryBundle(await file.text());
      const preview = await previewRecoveryBundle(bundle, bundleCode.value);
      state.recovery = {
        mode: "preview",
        preview,
        currentName: state.vault?.state.household.name ?? null,
        // A local change after the preview must invalidate it, never merge.
        fingerprint: await state.vault ? digestOf(state.vault) : null
      };
    } catch (error) {
      state.recovery = null;
      state.message = describe(error);
    }
    render();
  }

  async function confirmBundle() {
    const preview = state.recovery?.preview;
    if (!preview) return;
    state.message = null;
    try {
      if (state.recovery.fingerprint !== null && state.vault) {
        if (await digestOf(state.vault) !== state.recovery.fingerprint) {
          state.recovery = null;
          state.message = "预览之后这台浏览器里的记录又变了，请重新验证恢复码再导入。";
          render();
          return;
        }
      }
      state.vault = await NasFamilyVault.importRecoveryBundle(
        { preview, passphrase: bundlePassphrase.value },
        { api, store }
      );
      state.recovery = null;
      await enterApp();
      // Set after entering, because entering resets the Today screen.
      today.caution = "已经导入恢复包。这台浏览器现在打开的是这个家庭的记录，它还没有登记到这台服务器上。";
    } catch (error) {
      state.message = describe(error);
    }
    render();
  }

  function cancelBundle() {
    state.recovery = null;
    state.message = null;
    render();
  }

  /**
   * The recovery bundle panel. Export shows the code once; import is a verified
   * preview followed by a separate confirmation, and never a merge.
   */
  function recoveryCard() {
    const recovery = state.recovery;
    if (recovery?.mode === "exported") {
      return card("human-decision", [
        el("h3", { class: "card-title", text: "恢复码（只显示这一次）" }),
        el("p", { class: "card-text", text: recovery.recoveryCode }),
        el("p", {
          class: "card-text",
          text: "请把它和恢复包文件一起保存在安全的地方。恢复码和口令都丢了，这份家庭记录就无法再打开。关闭或刷新这个页面后不会再显示。"
        }),
        button("知道了", () => {
          state.recovery = null;
          render();
        })
      ]);
    }

    if (recovery?.mode === "preview") {
      const preview = recovery.preview;
      return card("human-decision", [
        el("h3", { class: "card-title", text: "确认导入这个家庭？" }),
        el("p", {
          class: "card-text",
          text: `恢复包里的家庭称呼是「${preview.householdName}」，有 ${preview.childCount} 个孩子、${preview.memberCount} 位成员，状态版本 ${preview.stateVersion}。`
        }),
        recovery.currentName
          ? el("p", {
            class: "card-text",
            text: `这台浏览器现在打开的是「${recovery.currentName}」。确认后它会被整体替换，不是合并。`
          })
          : null,
        field("为这台设备设置口令", bundlePassphrase),
        button("确认替换", confirmBundle),
        button("取消", cancelBundle, "text")
      ]);
    }

    return el("div", {}, [
      field("恢复包文件", bundleFile),
      field("恢复码", bundleCode),
      button("导出恢复包", exportBundle),
      button("验证恢复包", verifyBundle, "text")
    ]);
  }

  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "设置" }),
    messageCard(),
    card("preview", [
      el("h2", { class: "card-title", text: "开发预览" }),
      el("p", {
        class: "card-text",
        text: "这个版本用 PBKDF2-SHA-256 派生密钥，不是抗内存破解的算法；也不声称生产级多设备同步或独立安全审查。请只使用虚构的家庭数据。"
      })
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "机会来源" }),
      el("ul", { class: "list" }, [
        el("li", { class: "list-row" }, [
          el("span", { class: "list-title", text: "AI 发现" }),
          el("span", { class: "list-meta", text: "未连接 · 开发预览" })
        ]),
        el("li", { class: "list-row" }, [
          el("span", { class: "list-title", text: "世界信息" }),
          el("span", { class: "list-meta", text: "未连接 · 开发预览" })
        ])
      ]),
      el("p", { class: "card-text", text: "BYOK 密钥与瞬时代理将在后续版本接入；接入后每次调用前都会单独确认。" })
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "恢复包" }),
      el("p", {
        class: "card-text",
        text: "导出一个加密恢复包和一个只显示一次的恢复码。第二台浏览器可以用它接管这份家庭记录。"
      }),
      el("p", {
        class: "card-text",
        text: "导入是整体替换，不是合并：确认后这台浏览器里原来的家庭记录会被替换掉。恢复包本身不会改动服务器上的任何内容，登记到这台 NAS 是另一步、需要你单独确认的操作。"
      }),
      recoveryCard()
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "资料与设备" }),
      el("ul", { class: "list" }, [
        el("li", { class: "list-row" }, [
          el("span", { class: "list-title", text: "服务器上的密文对象" }),
          el("span", { class: "list-meta", text: `状态版本 ${vault.stateVersion}` })
        ]),
        vault.isRegistered
          ? el("li", { class: "list-row" }, [
            el("span", { class: "list-title", text: "已登记在这台服务器" }),
            el("span", { class: "list-meta", text: "保存会自动同步到这台 NAS" })
          ])
          : el("li", { class: "list-row" }, [
            el("span", { class: "list-title", text: "尚未登记在这台服务器" }),
            el("span", { class: "list-meta", text: "只能在本机解锁，保存前需要先登记" })
          ])
      ]),
      vault.isRegistered ? null : button("登记到这台服务器", registerOnServer),
      button("锁定保险箱", lock, "text")
    ]),
    card("danger", [
      el("h2", { class: "card-title", text: "删除这台服务器上的全部记录" }),
      state.pendingErase
        ? el("p", { class: "card-text", text: "再按一次即删除。所有密文对象和家庭注册记录都会被移除，这台浏览器上的本地记录也会清空，且无法恢复。" })
        : el("p", { class: "card-text", text: "删除后无法恢复。删除前请先导出恢复包。" }),
      button(state.pendingErase ? "确认删除，无法恢复" : "删除全部记录", erase)
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "这台服务器能看到什么" }),
      el("p", {
        class: "card-text",
        text: "密文、对象大小与数量、时间，以及你的 IP 地址。它看不到明文、姓名、兴趣、口令，也看不到任何 AI 密钥——代理请求只在内存中短暂经过。"
      })
    ])
  ]);
}

function renderApp() {
  const routes = [
    ["today", "今天"],
    ["family", "家庭"],
    ["settings", "设置"]
  ];
  const nav = el("nav", { class: "nav", "aria-label": "主导航" },
    routes.map(([route, label]) => el("button", {
      class: state.route === route ? "nav-item nav-item--active" : "nav-item",
      type: "button",
      "aria-current": state.route === route ? "page" : null,
      onClick: () => {
        state.route = route;
        state.message = null;
        state.pendingErase = false;
        render();
      }
    }, label))
  );
  const screen = state.route === "today" ? renderToday() : state.route === "family" ? renderFamily() : renderSettings();
  return [nav, screen];
}

/**
 * Enters the app with a freshly unlocked vault. The service settings are read
 * once here rather than on every render, so the source list on the composer is
 * the truth about this browser rather than a guess.
 */
async function enterApp() {
  state.screen = "app";
  state.route = "today";
  resetToday();
  render();
  await refreshSources();
  render();
}

function render() {
  const root = document.getElementById("main");
  root.replaceChildren();
  if (state.screen === "app" && state.vault) {
    root.append(...renderApp());
    return;
  }
  if (state.screen === "unlock") root.append(renderUnlock());
  else if (state.screen === "create") root.append(renderCreate());
  else root.append(renderBoot());
}

async function boot() {
  const root = document.getElementById("main");
  root.replaceChildren(renderBoot());
  let capabilities = null;
  try {
    capabilities = await api.capabilities();
  } catch {
    capabilities = null;
  }
  const status = document.getElementById("brand-status");
  if (capabilities && !capabilities.secureCookies) {
    status.textContent = "未启用安全 Cookie，仅限本机测试";
    status.className = "brand-status brand-status--warning";
  } else {
    status.textContent = "加密保存在你的 NAS";
  }
  const record = await store.read();
  state.screen = record ? "unlock" : "create";
  root.replaceChildren(record ? renderUnlock() : renderCreate());
}

boot();
