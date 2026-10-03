import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";

const appUrl = process.env.JIANYU_APP_URL ?? "http://127.0.0.1:4173";
const debugPort = Number(process.env.JIANYU_CDP_PORT ?? 9333);
const chromeCandidates = process.platform === "win32"
  ? [
      process.env.CHROME_PATH,
      "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
      "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
    ]
  : [process.env.CHROME_PATH, "google-chrome", "chromium", "chromium-browser"];

async function locateChrome() {
  for (const candidate of chromeCandidates.filter(Boolean)) {
    if (candidate.includes("\\") || candidate.includes("/")) {
      const { access } = await import("node:fs/promises");
      try { await access(candidate); return candidate; } catch { continue; }
    }
    return candidate;
  }
  throw new Error("Chrome was not found. Set CHROME_PATH to run the browser smoke test.");
}

async function waitForJson(url, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return response.json();
    } catch {}
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
  }
  throw new Error(`Timed out waiting for ${url}`);
}

function createCdpClient(webSocketUrl) {
  const socket = new WebSocket(webSocketUrl);
  const pending = new Map();
  let sequence = 0;
  const opened = new Promise((resolveOpen, rejectOpen) => {
    socket.addEventListener("open", resolveOpen, { once: true });
    socket.addEventListener("error", rejectOpen, { once: true });
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (!message.id || !pending.has(message.id)) return;
    const { resolveRequest, rejectRequest } = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) rejectRequest(new Error(message.error.message));
    else resolveRequest(message.result);
  });
  return {
    async send(method, params = {}) {
      await opened;
      const id = ++sequence;
      const response = new Promise((resolveRequest, rejectRequest) => pending.set(id, { resolveRequest, rejectRequest }));
      socket.send(JSON.stringify({ id, method, params }));
      return response;
    },
    close() { socket.close(); }
  };
}

async function evaluate(client, expression) {
  const result = await client.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? "Browser evaluation failed");
  return result.result.value;
}

async function waitFor(client, expression, label, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await evaluate(client, expression)) return;
    } catch (error) {
      if (!/context|Execution context/i.test(error.message)) throw error;
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 100));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

const temporaryRoot = resolve(tmpdir());
const profileDirectory = await mkdtemp(join(temporaryRoot, "jianyu-smoke-"));
const relativeProfile = relative(temporaryRoot, resolve(profileDirectory));
if (!relativeProfile || relativeProfile.startsWith("..")) throw new Error("Unsafe temporary profile path");

const chrome = spawn(await locateChrome(), [
  "--headless=new",
  "--window-size=1440,1000",
  `--remote-debugging-port=${debugPort}`,
  `--user-data-dir=${profileDirectory}`,
  "--no-first-run",
  "--disable-default-apps",
  "--disable-background-networking",
  appUrl
], { stdio: "ignore" });

let client;
try {
  const targets = await waitForJson(`http://127.0.0.1:${debugPort}/json/list`);
  const page = targets.find((target) => target.type === "page" && target.url.startsWith(appUrl));
  if (!page) throw new Error("Jianyu page did not open in headless Chrome");
  client = createCdpClient(page.webSocketDebuggerUrl);
  await client.send("Runtime.enable");
  await client.send("Page.enable");
  await waitFor(client, "Boolean(document.querySelector('#create-vault-form'))", "vault creation screen");

  await evaluate(client, `(() => {
    const form = document.querySelector('#create-vault-form');
    form.elements.familyName.value = '合成测试家庭';
    form.elements.caregiverName.value = '测试家长';
    form.elements.childName.value = '小见';
    form.elements.birthYear.value = String(new Date().getFullYear() - 10);
    form.elements.passphrase.value = 'synthetic-passphrase-2026';
    form.elements.confirmation.value = 'synthetic-passphrase-2026';
    form.requestSubmit();
    return true;
  })()`);
  await waitFor(client, "Boolean(document.querySelector('.shell'))", "unlocked App shell");

  const encryptedRecord = await evaluate(client, `(async () => {
    const database = await new Promise((resolve, reject) => {
      const request = indexedDB.open('jianyu-family-vault', 1);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    const record = await new Promise((resolve, reject) => {
      const request = database.transaction('encrypted-vaults', 'readonly').objectStore('encrypted-vaults').get('primary');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
    database.close();
    return JSON.stringify(record);
  })()`);
  if (encryptedRecord.includes("合成测试家庭") || encryptedRecord.includes("小见") || !encryptedRecord.includes("AES-GCM-256")) {
    throw new Error("Encrypted browser record exposed family plaintext or lacked cipher metadata");
  }
  await evaluate(client, `(() => {
    const selector = document.querySelector('#active-member');
    const childOption = [...selector.options].find(option => option.textContent.includes('本人'));
    selector.value = childOption.value;
    selector.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`);
  await waitFor(client, "document.querySelector('#active-member')?.selectedOptions[0]?.textContent.includes('本人')", "child author selection");

  await evaluate(client, `(() => {
    const form = document.querySelector('#interest-form');
    form.elements.expression.value = '最近一直研究赛车为什么过弯更快';
    form.elements.schoolWindow.value = '速度、时间和比例';
    form.requestSubmit();
    return true;
  })()`);
  await waitFor(client, "Boolean(document.querySelector('#opportunity-results'))", "opportunity results");

  const opportunityAudit = await evaluate(client, `(() => ({
    count: document.querySelectorAll('.option-card').length,
    ecosystems: [...document.querySelectorAll('.option-meta span:first-child')].map(node => node.textContent),
    hasNothing: [...document.querySelectorAll('.option-card h3')].some(node => node.textContent.includes('什么都不做')),
    gateVisible: Boolean(document.querySelector('.gate-details'))
  }))()`);
  if (opportunityAudit.count < 3 || !opportunityAudit.hasNothing || !opportunityAudit.gateVisible) {
    throw new Error(`Opportunity screen failed audit: ${JSON.stringify(opportunityAudit)}`);
  }
  const accessibilityAudit = await evaluate(client, `(() => {
    const ids = [...document.querySelectorAll('[id]')].map(node => node.id);
    return {
      duplicateIds: ids.filter((id, index) => ids.indexOf(id) !== index),
      unnamedButtons: [...document.querySelectorAll('button')].filter(button => !button.textContent.trim() && !button.getAttribute('aria-label')).length,
      unlabeledFields: [...document.querySelectorAll('input, select, textarea')].filter(field => !field.closest('label') && !field.getAttribute('aria-label') && !document.querySelector('label[for="' + field.id + '"]')).length
    };
  })()`);
  if (accessibilityAudit.duplicateIds.length || accessibilityAudit.unnamedButtons || accessibilityAudit.unlabeledFields) {
    throw new Error(`Basic accessibility audit failed: ${JSON.stringify(accessibilityAudit)}`);
  }
  if (process.env.JIANYU_SMOKE_SCREENSHOT) {
    const capture = await client.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
    await writeFile(process.env.JIANYU_SMOKE_SCREENSHOT, Buffer.from(capture.data, "base64"));
  }

  await evaluate(client, "document.querySelector('.option-card:not(.nothing) .choice-button').click(); true");
  await waitFor(client, "Boolean(document.querySelector('.choice-row'))", "chosen opportunity");
  await evaluate(client, "document.querySelector('.feedback-button[data-value=liked]').click(); true");
  await waitFor(client, "Boolean(document.querySelector('.feedback-done'))", "optional feedback");
  await evaluate(client, "document.querySelector('[data-route=timeline]').click(); true");
  await waitFor(client, "document.querySelectorAll('.timeline-item').length >= 3", "timeline events");
  const beforeLockEvents = await evaluate(client, "document.querySelectorAll('.timeline-item').length");
  const timelineShowsChildAuthor = await evaluate(client, "document.querySelector('.timeline')?.textContent.includes('小见记录')");
  if (!timelineShowsChildAuthor) throw new Error("Timeline did not preserve the selected child author");

  await evaluate(client, "document.querySelector('[data-action=lock]').click(); true");
  await waitFor(client, "Boolean(document.querySelector('#unlock-form'))", "locked vault");
  await evaluate(client, `(() => {
    const form = document.querySelector('#unlock-form');
    form.elements.passphrase.value = 'synthetic-passphrase-2026';
    form.requestSubmit();
    return true;
  })()`);
  await waitFor(client, "Boolean(document.querySelector('.shell'))", "vault re-unlock");
  await evaluate(client, "document.querySelector('[data-route=timeline]').click(); true");
  await waitFor(client, `document.querySelectorAll('.timeline-item').length === ${beforeLockEvents}`, "persisted timeline");

  await evaluate(client, "navigator.serviceWorker.ready.then(() => true)");
  await client.send("Network.enable");
  await client.send("Network.emulateNetworkConditions", {
    offline: true,
    latency: 0,
    downloadThroughput: 0,
    uploadThroughput: 0,
    connectionType: "none"
  });
  await client.send("Page.reload", { ignoreCache: true });
  await waitFor(client, "Boolean(document.querySelector('#unlock-form'))", "offline App reload");
  await evaluate(client, `(() => {
    const form = document.querySelector('#unlock-form');
    form.elements.passphrase.value = 'synthetic-passphrase-2026';
    form.requestSubmit();
    return true;
  })()`);
  await waitFor(client, "Boolean(document.querySelector('.shell'))", "offline vault unlock");
  await client.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 0,
    downloadThroughput: -1,
    uploadThroughput: -1,
    connectionType: "wifi"
  });
  await evaluate(client, "document.querySelector('[data-action=lock]').click(); true");
  await waitFor(client, "Boolean(document.querySelector('#unlock-form'))", "final lock before bundle round-trip");
  const bundleRoundTrip = await evaluate(client, `(async () => {
    const { BrowserFamilyVault } = await import('/apps/jianyu-web-prototype/src/browser-vault.js');
    await BrowserFamilyVault.erase();
    const erased = !(await BrowserFamilyVault.exists());
    await BrowserFamilyVault.importEncrypted(JSON.parse(${JSON.stringify(encryptedRecord)}));
    const restored = await BrowserFamilyVault.exists();
    return { erased, restored };
  })()`);
  if (!bundleRoundTrip.erased || !bundleRoundTrip.restored) throw new Error("Encrypted bundle erase/import round-trip failed");
  await evaluate(client, `(() => {
    const form = document.querySelector('#unlock-form');
    form.elements.passphrase.value = 'synthetic-passphrase-2026';
    form.requestSubmit();
    return true;
  })()`);
  await waitFor(client, "Boolean(document.querySelector('.shell'))", "restored bundle unlock");
  await client.send("Emulation.setDeviceMetricsOverride", {
    width: 390,
    height: 844,
    deviceScaleFactor: 1,
    mobile: true
  });
  await client.send("Page.reload");
  await waitFor(client, "Boolean(document.querySelector('#unlock-form'))", "mobile locked screen");
  await evaluate(client, `(() => {
    const form = document.querySelector('#unlock-form');
    form.elements.passphrase.value = 'synthetic-passphrase-2026';
    form.requestSubmit();
    return true;
  })()`);
  await waitFor(client, "Boolean(document.querySelector('.shell'))", "mobile App shell");
  const mobileAudit = await evaluate(client, `(() => ({
    viewportWidth: innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    bottomNavigationItems: document.querySelectorAll('.sidebar .nav-item').length
  }))()`);
  if (mobileAudit.documentWidth > mobileAudit.viewportWidth + 1 || mobileAudit.bottomNavigationItems !== 4) {
    throw new Error(`Mobile layout audit failed: ${JSON.stringify(mobileAudit)}`);
  }

  console.log(JSON.stringify({
    status: "passed",
    opportunityCards: opportunityAudit.count,
    ecosystems: opportunityAudit.ecosystems,
    nothingPresent: opportunityAudit.hasNothing,
    gateExplainsRejections: opportunityAudit.gateVisible,
    basicAccessibilityAudit: true,
    encryptedRecordContainsNoFamilyPlaintext: true,
    persistedEventsAfterRelock: beforeLockEvents,
    optionalFeedbackRecorded: true,
    distinctChildAuthorVisible: true,
    offlineReloadAndUnlock: true,
    encryptedBundleEraseImportRoundTrip: true,
    mobileLayoutWithoutHorizontalOverflow: true
  }, null, 2));
} finally {
  client?.close();
  if (chrome.exitCode === null) {
    const exited = new Promise((resolveExit) => chrome.once("exit", resolveExit));
    chrome.kill();
    await exited;
  }
  const cleanupTarget = resolve(profileDirectory);
  const cleanupRelative = relative(temporaryRoot, cleanupTarget);
  if (cleanupRelative && !cleanupRelative.startsWith("..")) {
    await rm(cleanupTarget, { recursive: true, force: true });
  }
}
