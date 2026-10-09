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
    for (const node of nodes) this.childNodes.push(node);
  }

  replaceChildren(...nodes) {
    this.childNodes = [...nodes];
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

function installBrowserStubs() {
  elements.set("main", new FakeNode("main"));
  elements.set("brand-status", new FakeNode("span"));
  globalThis.Node = FakeNode;
  globalThis.document = {
    createElement: (tag) => new FakeNode(tag),
    createTextNode: (text) => {
      const node = new FakeNode("#text");
      node.textContent = text;
      return node;
    },
    getElementById: (id) => elements.get(id) ?? null
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

function inputByName(name) {
  const matches = findByAttribute(main(), "name", name);
  assert.equal(matches.length, 1, `expected exactly one input named ${name}`);
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
