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

const api = new NasApi({});
const store = createIndexedDbStore();

const STAGE_LABELS = {
  "co-play": "4–6 共玩",
  accompany: "7–9 陪同",
  "co-select": "10–12 一起选",
  "hand-over": "13–15 交给孩子",
  graduation: "16+ 成年交接"
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
      state.screen = "app";
      render();
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
      state.screen = "app";
      render();
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

function renderToday() {
  const vault = state.vault;
  const children = vault.state.children;
  const rows = children.length === 0
    ? [el("p", { class: "card-text", text: "还没有添加孩子。可以到「家庭」页添加，也可以先什么都不做。" })]
    : children.map((child) => {
      const lifecycle = childLifecycle(child);
      const stage = lifecycle.stage ? STAGE_LABELS[lifecycle.stage] : "未满 4 岁";
      const authority = lifecycle.authority
        ? { "caregiver": "家长主导，孩子可以否决", joint: "家长和孩子一起选", child: "孩子主导", self: "本人主导" }[lifecycle.authority.primary]
        : "暂不提供发现";
      return el("li", { class: "list-row" }, [
        el("span", { class: "list-title", text: child.displayName }),
        el("span", { class: "list-meta", text: `${lifecycle.age} 岁 · ${stage}` }),
        el("span", { class: "list-meta", text: authority }),
        lifecycle.approximate ? el("span", { class: "list-meta", text: "只记录了出生年份，月份和日期是估算的" }) : null
      ]);
    });

  return el("div", { class: "page" }, [
    el("h1", { class: "page-title", text: "今天" }),
    el("p", { class: "page-description", text: "从孩子当下的一句话、一个问题或一个自愿的行动开始，课程表排在后面。" }),
    conflictCard(),
    card("discovery", [
      el("h2", { class: "card-title", text: "这次" }),
      el("p", { class: "card-text", text: "发现流程（几扇门与留白）还没有接入这个版本，因此这里不会给出任何推荐。" }),
      el("p", { class: "card-text", text: "在接入之前，什么都不做始终是一个完整的选择。" })
    ]),
    card("neutral", [
      el("h2", { class: "card-title", text: "家里的孩子" }),
      el("ul", { class: "list" }, rows)
    ])
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
      state.screen = "app";
      state.route = "today";
      state.message = "已经导入恢复包。这台浏览器现在打开的是这个家庭的记录，它还没有登记到这台服务器上。";
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
