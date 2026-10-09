// A tiny DOM stand-in so the real bootstrap module can be driven in Node. The
// point of these tests is the wiring between the rendered controls and the
// vault, not the browser: a button that renders but never fires its handler is
// exactly the class of bug a pure unit test of the vault cannot see.

import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";

class FakeNode {
  constructor(tag) {
    this.tagName = String(tag).toUpperCase();
    this.childNodes = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.textContent = "";
    this.className = "";
    this.value = "";
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  append(...nodes) {
    for (const node of nodes) {
      node.parentNode = this;
      this.childNodes.push(node);
    }
  }

  replaceChildren(...nodes) {
    for (const node of this.childNodes) node.parentNode = null;
    for (const node of nodes) node.parentNode = this;
    this.childNodes = [...nodes];
  }

  // The download link is clicked and then detached, so the stub has to behave
  // like an element rather than throw on either call.
  click() {
    return this.dispatch("click");
  }

  remove() {
    if (!this.parentNode) return;
    const siblings = this.parentNode.childNodes;
    const index = siblings.indexOf(this);
    if (index >= 0) siblings.splice(index, 1);
    this.parentNode = null;
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(handler);
  }

  dispatch(type) {
    for (const handler of [...(this.listeners.get(type) ?? [])]) handler({ type, target: this });
    return true;
  }

  descendants() {
    const found = [];
    const walk = (node) => {
      for (const child of node.childNodes) {
        found.push(child);
        walk(child);
      }
    };
    walk(this);
    return found;
  }

  text() {
    return this.descendants().map((node) => node.textContent).join("\n");
  }
}

function requestOutcome(request) {
  return new Promise((resolve, reject) => {
    request.addEventListener("success", () => resolve(request.result), { once: true });
    request.addEventListener("error", () => reject(request.error), { once: true });
  });
}

function createIndexedDbStub() {
  const databases = new Map();

  function open(name) {
    const request = {
      result: null,
      error: null,
      listeners: new Map(),
      addEventListener(type, handler) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(handler);
      },
      fire(type) {
        for (const handler of [...(this.listeners.get(type) ?? [])]) handler({ type });
      }
    };
    queueMicrotask(() => {
      let database = databases.get(name);
      if (!database) {
        const stores = new Map();
        database = {
          objectStoreNames: { contains: (storeName) => stores.has(storeName) },
          createObjectStore(storeName) {
            stores.set(storeName, new Map());
            return {};
          },
          transaction(storeName) {
            const store = stores.get(storeName);
            const transaction = {
              listeners: new Map(),
              addEventListener(type, handler) {
                if (!this.listeners.has(type)) this.listeners.set(type, []);
                this.listeners.get(type).push(handler);
              },
              objectStore: () => ({
                get: (key) => makeRequest(() => store.get(key)),
                put: (value) => makeRequest(() => {
                  store.set(value.id, structuredClone(value));
                  return value.id;
                }),
                delete: (key) => makeRequest(() => store.delete(key))
              })
            };
            // The vault awaits its store operation before listening for
            // "complete", so the event has to land on a later macrotask.
            setTimeout(() => {
              for (const handler of [...(transaction.listeners.get("complete") ?? [])]) handler({ type: "complete" });
            }, 0);
            return transaction;
          },
          close() {}
        };
        databases.set(name, database);
      }
      request.result = database;
      request.fire("upgradeneeded");
      request.fire("success");
    });
    return request;
  }

  function makeRequest(operation) {
    const request = {
      result: undefined,
      error: null,
      listeners: new Map(),
      addEventListener(type, handler) {
        if (!this.listeners.has(type)) this.listeners.set(type, []);
        this.listeners.get(type).push(handler);
      },
      fire(type) {
        for (const handler of [...(this.listeners.get(type) ?? [])]) handler({ type });
      }
    };
    queueMicrotask(() => {
      try {
        request.result = operation();
        request.fire("success");
      } catch (error) {
        request.error = error;
        request.fire("error");
      }
    });
    return request;
  }

  return { open };
}

function jsonResponse(status, payload) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { getSetCookie: () => [] },
    json: async () => payload,
    arrayBuffer: async () => new ArrayBuffer(0)
  };
}

const CAPABILITIES = {
  aiProxy: true,
  feedProxy: true,
  privateFeedAllowed: false,
  secureCookies: true,
  maxObjectBytes: 25_165_824,
  objectPageMax: 256,
  sessionTtlDays: 30
};

const elements = new Map();
const requests = [];
const downloads = [];

function installBrowserStubs() {
  elements.set("main", new FakeNode("main"));
  elements.set("brand-status", new FakeNode("span"));
  const body = new FakeNode("body");
  elements.set("body", body);
  globalThis.Node = FakeNode;
  globalThis.document = {
    createElement: (tag) => new FakeNode(tag),
    createTextNode: (text) => {
      const node = new FakeNode("#text");
      node.textContent = text;
      return node;
    },
    getElementById: (id) => elements.get(id) ?? null,
    body
  };
  globalThis.Blob = class {
    constructor(parts) {
      this.parts = parts;
      this.text = async () => parts.join("");
    }
  };
  globalThis.URL = {
    createObjectURL: (blob) => {
      downloads.push(blob);
      return "blob:jianyu-recovery-bundle";
    },
    revokeObjectURL: () => {}
  };
  globalThis.indexedDB = createIndexedDbStub();
  globalThis.fetch = async (url, options = {}) => {
    const path = String(url);
    const body = options.body === undefined ? null : options.body;
    requests.push({
      method: options.method ?? "GET",
      path,
      contentType: options.headers?.["content-type"] ?? null,
      body
    });
    if (path === "/api/capabilities") return jsonResponse(200, CAPABILITIES);
    if (path === "/api/households" && options.method === "POST") return jsonResponse(201, { created: true });
    if (path === "/api/sessions" && options.method === "POST") return jsonResponse(200, { unlocked: true });
    if (path === "/api/state" && options.method === "PUT") return jsonResponse(200, { version: 1, objectId: "state-object" });
    if (path.startsWith("/api/objects/") && options.method === "PUT") return jsonResponse(201, { created: true });
    if (path === "/api/state" && options.method === "GET") return jsonResponse(200, { version: 1, objectId: "state-object" });
    if (path === "/api/households/me" && options.method === "DELETE") return jsonResponse(200, { erased: true });
    return jsonResponse(404, { error: { code: "not-found", message: "未找到" } });
  };
}

function main() {
  return elements.get("main");
}

function findByAttribute(root, attribute, value) {
  return main().descendants().filter((node) => node.getAttribute(attribute) === value);
}

function buttonByLabel(label) {
  // A button's label is appended as a text child, not set as its own text.
  const matches = main().descendants().filter((node) => node.tagName === "BUTTON" && node.text() === label);
  assert.equal(matches.length, 1, `expected exactly one button labelled ${label}`);
  return matches[0];
}

/** Every door carries its own refusal, so address the first one directly. */
function firstRefusalButton() {
  const matches = main().descendants().filter((node) => node.tagName === "BUTTON" && node.text() === "孩子不想要");
  assert.ok(matches.length >= 1, "each door must offer the child's own refusal");
  return matches[0];
}

function inputByName(name) {
  const matches = findByAttribute(main(), "name", name);
  assert.equal(matches.length, 1, `expected exactly one input named ${name}`);
  return matches[0];
}

/** The switcher's rows carry the name and the stage, so match on the name span. */
function pickerItem(name) {
  const matches = main().descendants().filter((node) =>
    /(^|\s)picker-item(\s|$)/.test(node.className) &&
    node.descendants().some((child) => child.className === "list-title" && child.textContent === name));
  assert.equal(matches.length, 1, `expected exactly one child named ${name} in the switcher`);
  return matches[0];
}

/** One record on the footprint, addressed by text only it contains. */
function recordCard(text) {
  const matches = main().descendants().filter((node) =>
    /(^|\s)card(\s|$)/.test(node.className) && node.text().includes(text));
  assert.equal(matches.length, 1, `expected exactly one card containing ${text}`);
  return matches[0];
}

/** A control inside one record, so a repeated label elsewhere is not ambiguous. */
function controlIn(scope, label) {
  const matches = scope.descendants().filter((node) => node.tagName === "BUTTON" && node.text() === label);
  assert.equal(matches.length, 1, `expected exactly one button labelled ${label} inside the record`);
  return matches[0];
}

/** Waits until the main region stops changing, so async handlers can settle. */
async function settle(expectedSubstring) {
  const deadline = Date.now() + 20_000;
  let previous = "";
  while (Date.now() < deadline) {
    const text = main().text();
    if (text === previous && (!expectedSubstring || text.includes(expectedSubstring))) return text;
    previous = text;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return main().text();
}

const PASSPHRASE = "synthetic-test-passphrase";
const FAMILY_NAME = "隅之家";
const CAREGIVER_NAME = "林晓";
const CHILD_NAME = "小隅";

describe("NAS web bootstrap", () => {
  before(async () => {
    installBrowserStubs();
    await import("../../apps/jianyu-web-nas/web/src/main.js");
    await settle("建立家庭保险箱");
  });

  after(() => {
    delete globalThis.Node;
    delete globalThis.document;
    delete globalThis.indexedDB;
    delete globalThis.fetch;
    delete globalThis.Blob;
    delete globalThis.URL;
  });

  it("renders the create screen for a browser with no local record", () => {
    assert.match(main().text(), /建立家庭保险箱/);
    for (const name of ["familyName", "caregiverName", "childName", "birthDate", "passphrase", "confirmation"]) {
      assert.equal(inputByName(name).getAttribute("name"), name);
    }
    assert.equal(elements.get("brand-status").textContent, "加密保存在你的 NAS");
  });

  it("wires rendered buttons to the lowercase click event", () => {
    const submit = buttonByLabel("建立保险箱");
    const listeners = submit.listeners.get("click") ?? [];
    assert.equal(listeners.length, 1, "the submit button must listen for \"click\", not a differently-cased name");
  });

  it("creates the vault and reaches the app screen", async () => {
    inputByName("familyName").value = FAMILY_NAME;
    inputByName("caregiverName").value = CAREGIVER_NAME;
    inputByName("childName").value = CHILD_NAME;
    inputByName("birthDate").value = "2013-09-15";
    inputByName("passphrase").value = PASSPHRASE;
    inputByName("confirmation").value = PASSPHRASE;
    buttonByLabel("建立保险箱").dispatch("click");

    const text = await settle("小隅");
    assert.match(text, /小隅/, "the app screen should list the child");

    const registered = requests.find((request) => request.path === "/api/households");
    assert.ok(registered, "creating a vault must register the household");
    const sent = typeof registered.body === "string" ? registered.body : "";
    assert.match(sent, /"householdId"/);
    for (const secret of [PASSPHRASE, FAMILY_NAME, CAREGIVER_NAME, CHILD_NAME, "nas-vault-v1"]) {
      assert.ok(!sent.includes(secret), `the registration body must not contain ${secret}`);
    }
    assert.ok(requests.some((request) => request.path === "/api/state" && request.method === "PUT"));
  });

  it("exports a recovery bundle whose file and code are never sent as plaintext", async () => {
    buttonByLabel("设置").dispatch("click");
    await settle("恢复包");
    buttonByLabel("导出恢复包").dispatch("click");
    await settle("恢复码（只显示这一次）");

    assert.equal(downloads.length, 1, "exporting must offer exactly one file");
    const text = await downloads[0].text();
    for (const secret of [PASSPHRASE, FAMILY_NAME, CAREGIVER_NAME, CHILD_NAME]) {
      assert.ok(!text.includes(secret), `the bundle must not contain ${secret}`);
    }
    assert.match(text, /org\.jianyu\.web-recovery-bundle\/v1/);

    // The code is shown once and is not persisted anywhere in the DOM.
    const shown = main().text().match(/[2-9A-HJ-NP-Z]{5}(?:-[2-9A-HJ-NP-Z]{5}){3}/u);
    assert.ok(shown, "the recovery code must be shown once at export");
    buttonByLabel("知道了").dispatch("click");
    await settle("恢复包文件");
    assert.equal(main().text().includes(shown[0]), false, "the code must not linger on screen");
  });

  it("imports a bundle as a replacement, and a local change invalidates the preview", async () => {
    buttonByLabel("设置").dispatch("click");
    await settle("恢复包");
    buttonByLabel("导出恢复包").dispatch("click");
    await settle("恢复码（只显示这一次）");
    const code = main().text().match(/[2-9A-HJ-NP-Z]{5}(?:-[2-9A-HJ-NP-Z]{5}){3}/u)[0];
    // The newest download, not the first one this suite ever produced.
    const bundleText = await downloads.at(-1).text();
    buttonByLabel("知道了").dispatch("click");
    await settle("恢复包文件");

    // Feed the exported file back through the import path.
    inputByName("bundleFile").files = [{ text: async () => bundleText }];
    inputByName("bundleCode").value = code;
    buttonByLabel("验证恢复包").dispatch("click");
    await settle("确认导入这个家庭？");
    assert.match(main().text(), /隅之家/);

    // A local change after the preview must refuse the import, not merge.
    buttonByLabel("取消").dispatch("click");
    await settle("恢复包文件");
    buttonByLabel("家庭").dispatch("click");
    await settle("添加孩子");
    inputByName("childName").value = "三宝";
    inputByName("birthDate").value = "2019-01-31";
    buttonByLabel("添加孩子").dispatch("click");
    await settle("三宝");
    buttonByLabel("设置").dispatch("click");
    await settle("恢复包");

    inputByName("bundleFile").files = [{ text: async () => bundleText }];
    inputByName("bundleCode").value = code;
    buttonByLabel("验证恢复包").dispatch("click");
    await settle("确认导入这个家庭？");
    inputByName("bundlePassphrase").value = "synthetic-second-device";
    buttonByLabel("确认替换").dispatch("click");
    await settle("预览之后这台浏览器里的记录又变了");
    assert.match(main().text(), /预览之后这台浏览器里的记录又变了/);
  });

  // The Today flow, driven through the same bootstrap. What matters here is the
  // order of operations a family depends on: the words are written down before
  // any provider could be contacted, the confirmation is a separate step, and one
  // search yields at most one durable decision.
  it("opens on the child's own words and names the offline boundary", async () => {
    buttonByLabel("今天").dispatch("click");
    const text = await settle("记下这句话");
    assert.match(text, /这次/, "the current occasion is labelled 这次");
    assert.match(text, /离线演示 · 不发送、不保存/u, "the source state is stated before anything happens");
    assert.match(text, /孩子当下在意的内容/u);
    // No ranking, no count of saved observations, no progress meter.
    assert.equal(text.includes("0/800"), false);
    assert.equal(text.includes("推荐"), false);
  });

  it("writes the child's words before any provider could be contacted", async () => {
    const stateWrites = () => requests.filter((request) => request.path === "/api/state" && request.method === "PUT").length;
    const proxyCalls = () => requests.filter((request) => request.path.startsWith("/api/proxy/")).length;
    const writesBefore = stateWrites();
    const proxyBefore = proxyCalls();

    const expression = inputByName("expression");
    expression.value = "孩子最近主动说自己很喜欢赛车，想弄明白轮胎为什么能抓地";
    expression.dispatch("input");
    buttonByLabel("记下这句话，看看有几扇门").dispatch("click");
    const text = await settle("确认这次寻找");

    assert.match(text, /确认这次寻找/u);
    assert.equal(proxyCalls(), proxyBefore, "no provider is contacted at the composer step");
    assert.equal(stateWrites(), writesBefore + 1, "the sentence and its scope are sealed into the vault first");
    // The confirmation is a separate step, and it names the three roles.
    assert.match(text, /AI 找入口，本机筛选；你\/你们来选/u);
    assert.match(text, /离线演示不发送任何内容/u);
  });

  it("shows exactly what would be sent behind one disclosure", async () => {
    buttonByLabel("查看会发送的内容").dispatch("click");
    const text = await settle("不包含孩子和家庭的称呼");
    assert.match(text, /孩子当下在意的内容/u);
    assert.match(text, /不包含孩子和家庭的称呼/u);
  });

  it("offers doors plus 留白 and records the family's choice", async () => {
    buttonByLabel("确认，开始这次寻找").dispatch("click");
    const result = await settle("选这个");

    // Several doors and 留白, with no ordinal numbering or quality claim.
    const doors = main().descendants().filter((node) => node.className === "door-title");
    assert.ok(doors.length >= 3, "the offline demonstration offers more than one door");
    for (const door of doors) {
      assert.equal(/^\d/.test(door.textContent), false, "a door carries no ordinal number");
    }
    assert.match(result, /什么都不做/u, "留白 is offered alongside the doors");
    assert.match(result, /选它不需要时间、花费或家长的精力/u);
    assert.match(result, /留白是一个完整的选择，不是放弃/u);
    assert.match(result, /这次就什么都不做/u);

    buttonByLabel("这次就什么都不做").dispatch("click");
    const acknowledged = await settle("这次就停在这里");
    assert.match(acknowledged, /这次就停在这里/u);
    assert.match(acknowledged, /也不会产生任何欠账/u);
    // The first child is 13, so the view is signed by the child themselves.
    assert.match(acknowledged, /我想留个看法/u, "a 13–15 year old signs their own view");
    assert.match(acknowledged, /写不写都可以/u);
  });

  it("leaves an optional later view and then returns to a fresh occasion", async () => {
    const view = inputByName("laterView");
    view.value = "孩子后来说想再看看轮胎花纹";
    view.dispatch("input");
    buttonByLabel("留下这个看法").dispatch("click");
    await settle("记下这句话");

    // Back at the composer with the draft cleared, ready for the next occasion.
    assert.match(main().text(), /记下这句话，看看有几扇门/u);
    assert.equal(inputByName("expression").value, "", "a saved choice clears that occasion's draft");
  });

  it("requires a separate confirmation before writing a refusal", async () => {
    const expression = inputByName("expression");
    expression.value = "孩子主动说想去看看赛车场";
    expression.dispatch("input");
    buttonByLabel("记下这句话，看看有几扇门").dispatch("click");
    await settle("确认这次寻找");
    buttonByLabel("确认，开始这次寻找").dispatch("click");
    await settle("选这个");

    firstRefusalButton().dispatch("click");
    const veto = await settle("确认记下这份拒绝？");
    assert.match(veto, /和家长只是暂时不想做是两回事/u);
    assert.match(veto, /每次调用前你都可以再否决/u);

    buttonByLabel("确认记下").dispatch("click");
    const after = await settle("已经记下这份拒绝");
    assert.match(after, /已经记下这份拒绝/u);
    assert.equal(
      main().descendants().filter((node) => node.className === "door-title").length,
      0,
      "a refusal is a durable decision, so the doors are gone"
    );
  });

  it("keeps one unsent sentence per child when the viewed person changes", async () => {
    buttonByLabel("家庭").dispatch("click");
    await settle("添加孩子");
    inputByName("childName").value = "二宝";
    inputByName("birthDate").value = "2016-05-20";
    buttonByLabel("添加孩子").dispatch("click");
    await settle("二宝");

    buttonByLabel("今天").dispatch("click");
    await settle("记下这句话");
    const first = inputByName("expression");
    first.value = "小隅说想研究赛车";
    first.dispatch("input");

    buttonByLabel("切换查看人").dispatch("click");
    await settle("二宝");
    pickerItem("二宝").dispatch("click");
    await settle("记下这句话");
    assert.equal(inputByName("expression").value, "", "another child's draft is never shown here");

    buttonByLabel("切换查看人").dispatch("click");
    await settle("小隅");
    pickerItem("小隅").dispatch("click");
    await settle("记下这句话");
    assert.equal(inputByName("expression").value, "小隅说想研究赛车", "the draft comes back with its own child");
  });

  // The shared footprint. What matters here is that a record says who wrote it
  // and when, that a correction is added rather than rewriting the original, and
  // that deleting a choice is a separate, explicit step that also removes what
  // is linked to it.
  it("shows the family's own records with their author and day", async () => {
    buttonByLabel("回望").dispatch("click");
    const text = await settle("家庭足迹");

    assert.match(text, /家庭足迹按时间记下谁在什么时候说了什么/u);
    assert.match(text, /孩子当下在意的内容/u, "the recorded sentence is on the footprint");
    assert.match(text, /林晓记录/u, "each record names who wrote it");
    assert.match(text, /\d{4} 年 \d{1,2} 月 \d{1,2} 日/u, "each record says which day");
    assert.match(text, /孩子能自己决定什么/u);
    assert.match(text, /随着年龄变化，选择权慢慢交到孩子手上/u);
    assert.match(text, /13–15 交给孩子/u, "the whole arc is visible, not just this child's band");
    // Nothing in this family's records is restricted, so the screen says nothing
    // about hidden records rather than implying there are some.
    assert.equal(text.includes("有些记录只对部分人可见"), false);
  });

  it("adds a correction without rewriting the original", async () => {
    const card = recordCard("孩子最近主动说自己很喜欢赛车，想弄明白轮胎为什么能抓地");
    controlIn(card, "孩子后来改过说法").dispatch("click");
    await settle("原记录会保留");

    const field = inputByName("correction");
    field.value = "孩子后来说的是想弄明白轮胎花纹，不是想买赛车";
    field.dispatch("input");
    buttonByLabel("保存这条纠正").dispatch("click");
    const text = await settle("后来的纠正");

    assert.match(text, /后来的纠正/u);
    assert.match(text, /孩子后来说的是想弄明白轮胎花纹，不是想买赛车/u);
    // The original is still there, unchanged, next to the correction.
    assert.match(text, /孩子最近主动说自己很喜欢赛车，想弄明白轮胎为什么能抓地/u);
    assert.match(text, /纠正的是另一条记录，原记录没有改写/u);
  });

  it("deletes a saved choice only after the young person confirms", async () => {
    // The first child is 13, so the deletion is theirs to confirm.
    // Titles are set as textContent rather than child nodes, so compare that.
    const titles = () => main().descendants()
      .filter((node) => node.className === "door-title" && node.textContent === "什么都不做").length;

    const card = recordCard("什么都不做");
    controlIn(card, "删除这个选择").dispatch("click");
    const confirming = await settle("这次删除需要由孩子本人确认");
    assert.match(confirming, /这次删除需要由孩子本人确认/u);
    assert.match(confirming, /按一下只是记下这次确认是孩子做出的，并不验证身份/u);
    assert.equal(titles(), 1, "nothing is removed before the confirmation");

    buttonByLabel("由小隅确认删除").dispatch("click");
    const after = await settle("已经删除这次选择");
    assert.match(after, /已经删除这次选择，和它连在一起的记录也一并删除了/u);
    assert.equal(titles(), 0, "the choice and its title are gone from the footprint");
  });

  it("lists each child with the band their birth date puts them in", async () => {
    buttonByLabel("家庭").dispatch("click");
    const text = await settle("阶段按出生日期计算");

    assert.match(text, /小隅 · 13 岁/u);
    assert.match(text, /13–15 交给孩子 · 孩子主导/u);
    assert.match(text, /二宝 · 10 岁/u);
    assert.match(text, /10–12 一起选 · 家长和孩子一起选/u);
    assert.match(text, /三宝 · 7 岁/u);
    assert.match(text, /7–9 陪同/u);
    // The stage is a date calculation, not a score, and the signature is not a login.
    assert.match(text, /阶段按出生日期计算，生日当天自动切换/u);
    assert.match(text, /不作为能力评分/u);
    assert.match(text, /按一下不代表验证了身份/u);
  });

  it("navigates to settings and erases only after a second confirmation", async () => {
    buttonByLabel("设置").dispatch("click");
    await settle("删除这台服务器上的全部记录");

    buttonByLabel("删除全部记录").dispatch("click");
    const confirming = await settle("再按一次即删除");
    assert.match(confirming, /再按一次即删除/);
    assert.ok(
      !requests.some((request) => request.path === "/api/households/me" && request.method === "DELETE"),
      "the first press must only arm the confirmation"
    );

    buttonByLabel("确认删除，无法恢复").dispatch("click");
    await settle("建立家庭保险箱");
    assert.ok(
      requests.some((request) => request.path === "/api/households/me" && request.method === "DELETE"),
      "the second press must erase the household"
    );
    assert.match(main().text(), /建立家庭保险箱/, "erasing returns to the create screen");
  });
});
