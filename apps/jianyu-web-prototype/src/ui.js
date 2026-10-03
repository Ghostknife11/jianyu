import { FamilySession } from "./app-state.js";
import { BrowserFamilyVault, createInitialFamilyState } from "./browser-vault.js";
import { discoverForFamily } from "./opportunity-service.js";
import { childrenView, settingsView, shellView, timelineView, todayView, vaultEntryView } from "./view-templates.js";

const root = document.querySelector("#app");

let hasVault = false;
let session = null;
let route = "today";
let resultState = { childId: null, draftExpression: "", outcome: null, sourceEventId: null };
let renderedOptions = new Map();

function setEntryError(message) {
  const target = document.querySelector("#entry-error");
  if (target) target.textContent = message;
}

function toast(message) {
  document.querySelector(".toast")?.remove();
  const element = document.createElement("div");
  element.className = "toast";
  element.textContent = message;
  document.body.append(element);
  window.setTimeout(() => element.remove(), 2800);
}

function render() {
  if (!session) {
    root.innerHTML = vaultEntryView(hasVault);
    return;
  }

  const state = session.snapshot();
  if (!resultState.childId) resultState.childId = state.children[0]?.id ?? null;
  const views = {
    today: () => todayView(state, resultState),
    children: () => childrenView(state),
    timeline: () => timelineView(state),
    settings: () => settingsView(state)
  };
  const view = views[route] ?? views.today;
  root.innerHTML = shellView(state, route, view(), session.activeMemberId());
}

function formValues(form) {
  return Object.fromEntries(new FormData(form));
}

async function createVault(form) {
  const values = formValues(form);
  if (values.passphrase !== values.confirmation) throw new Error("两次输入的口令不一致");
  const initialState = createInitialFamilyState(values);
  const vault = await BrowserFamilyVault.create(values.passphrase, initialState);
  session = new FamilySession(vault, initialState, render);
  resultState.childId = initialState.children[0]?.id ?? null;
  hasVault = true;
  render();
  toast("家庭保险箱已建立，只保存在这台设备上");
}

async function unlockVault(form) {
  const { passphrase } = formValues(form);
  const unlocked = await BrowserFamilyVault.unlock(passphrase);
  session = new FamilySession(unlocked.vault, unlocked.state, render);
  resultState.childId = unlocked.state.children[0]?.id ?? null;
  render();
}

async function submitInterest(form) {
  const values = formValues(form);
  const state = session.snapshot();
  const child = state.children.find((item) => item.id === values.childId);
  if (!child) throw new Error("请选择一个孩子");
  const constraints = {
    timeMinutes: Number(values.timeMinutes),
    costBand: values.costBand,
    caregiverEnergy: values.caregiverEnergy,
    travelMinutesMax: values.canTravel ? 30 : 0
  };
  const goals = {
    caregiver: values.caregiverGoal?.trim() || null,
    shared: values.sharedGoal?.trim() || null
  };
  const button = form.querySelector("button[type=submit]");
  button.disabled = true;
  button.textContent = "正在寻找自然入口…";

  const interestEvent = await session.recordInterest({
    childId: child.id,
    expression: values.expression,
    evidenceKind: values.evidenceKind,
    constraints,
    schoolWindow: values.schoolWindow,
    goals
  });
  const outcome = await discoverForFamily({
    child,
    expression: values.expression,
    constraints,
    schoolWindow: values.schoolWindow,
    goals
  });
  renderedOptions = new Map([
    ...outcome.selected.map(({ candidate }) => [candidate.opportunityId, candidate]),
    [outcome.nothing.opportunityId, outcome.nothing]
  ]);
  resultState = {
    childId: child.id,
    draftExpression: values.expression,
    outcome,
    sourceEventId: interestEvent.eventId
  };
  render();
  requestAnimationFrame(() => document.querySelector("#opportunity-results")?.scrollIntoView({ behavior: "smooth", block: "start" }));
}

async function addChild(form) {
  const values = formValues(form);
  const child = await session.addChild(values);
  resultState.childId = child.id;
  route = "today";
  render();
  toast(`${child.displayName} 已加入家庭空间`);
}

async function addMember(form) {
  const member = await session.addMember(formValues(form));
  render();
  toast(`${member.displayName} 已成为独立记录者`);
}

async function chooseOpportunity(button) {
  const opportunity = renderedOptions.get(button.dataset.opportunityId);
  if (!opportunity) throw new Error("这个建议已经过期，请重新寻找");
  await session.chooseOpportunity({
    childId: resultState.childId,
    opportunity,
    sourceEventId: resultState.sourceEventId
  });
  resultState.outcome = null;
  renderedOptions.clear();
  render();
  toast(opportunity.type === "nothing" ? "这次就停在这里，不会产生任何欠账" : "已记下这个家庭选择");
}

async function vetoOpportunity(button) {
  const opportunity = renderedOptions.get(button.dataset.opportunityId);
  if (!opportunity) throw new Error("这个建议已经过期，请重新寻找");
  await session.vetoOpportunity({
    childId: resultState.childId,
    opportunity,
    sourceEventId: resultState.sourceEventId
  });
  resultState.outcome = null;
  renderedOptions.clear();
  render();
  toast("已经尊重孩子的拒绝，不会产生负面分数");
}

async function correctEvidence(button) {
  const correction = window.prompt("孩子真正的意思是什么？原记录会保留，并明确标注已被纠正。");
  if (!correction?.trim()) return;
  await session.correctEvidence({
    childId: button.dataset.childId,
    targetEventId: button.dataset.eventId,
    correction
  });
  toast("纠正已作为新记录保存，原记录没有被悄悄改写");
}

async function recordFeedback(button) {
  await session.recordFeedback({ choiceId: button.dataset.choiceId, value: button.dataset.value });
  toast("谢谢，真实反馈已经记下");
}

function exportVault() {
  const record = session.encryptedExport();
  const blob = new Blob([JSON.stringify(record, null, 2)], { type: "application/json" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `jianyu-family-vault-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
  toast("加密备份已导出，请和口令分开保管");
}

async function importBackup(input) {
  const file = input.files?.[0];
  if (!file) return;
  if (hasVault && !window.confirm("导入会替换这台设备上现有的加密保险箱。确认继续吗？")) return;
  const record = JSON.parse(await file.text());
  await BrowserFamilyVault.importEncrypted(record);
  hasVault = true;
  render();
  toast("加密备份已导入，请使用原口令解锁");
}

root.addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.target;
  try {
    if (form.id === "create-vault-form") await createVault(form);
    if (form.id === "unlock-form") await unlockVault(form);
    if (form.id === "interest-form") await submitInterest(form);
    if (form.id === "add-child-form") await addChild(form);
    if (form.id === "add-member-form") await addMember(form);
  } catch (error) {
    setEntryError(error.message);
    const childError = document.querySelector("#child-error");
    if (childError) childError.textContent = error.message;
    if (session) toast(error.message);
    const submit = form.querySelector("button[type=submit]");
    if (submit) submit.disabled = false;
  }
});

root.addEventListener("click", async (event) => {
  const routeButton = event.target.closest("[data-route]");
  if (routeButton) {
    route = routeButton.dataset.route;
    render();
    return;
  }

  const actionButton = event.target.closest("[data-action]");
  if (!actionButton) return;
  try {
    const actions = {
      lock: () => {
        session = null;
        resultState = { childId: null, draftExpression: "", outcome: null, sourceEventId: null };
        renderedOptions.clear();
        render();
      },
      choose: () => chooseOpportunity(actionButton),
      veto: () => vetoOpportunity(actionButton),
      feedback: () => recordFeedback(actionButton),
      "correct-evidence": () => correctEvidence(actionButton),
      "export-vault": exportVault,
      "erase-vault": async () => {
        session.assertAllowed("vault.erase");
        if (!window.confirm("这会永久擦除本设备上的家庭保险箱。确认继续吗？")) return;
        await BrowserFamilyVault.erase();
        session = null;
        hasVault = false;
        resultState = { childId: null, draftExpression: "", outcome: null, sourceEventId: null };
        renderedOptions.clear();
        render();
      }
    };
    await actions[actionButton.dataset.action]?.();
  } catch (error) {
    toast(error.message);
  }
});

root.addEventListener("change", async (event) => {
  try {
    if (event.target.id === "backup-import") await importBackup(event.target);
    if (event.target.id === "active-member") {
      session.setActiveMember(event.target.value);
      render();
    }
    if (event.target.name === "childId") {
      resultState.childId = event.target.value;
      resultState.outcome = null;
    }
  } catch (error) {
    setEntryError(error.message);
    toast(error.message);
  }
});

hasVault = await BrowserFamilyVault.exists();
render();

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/service-worker.js").catch(() => {
    // Offline installation is helpful but never blocks local use.
  });
}
