import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { assertProxyTarget, forwardAiCompletion, isPrivateAddress } from "../../apps/jianyu-web-nas/server/proxy.mjs";

const PROXY_CONFIG = { aiProxyTimeoutMs: 1_000, aiProxyMaxResponseBytes: 1024 };

describe("private address detection", () => {
  it("recognises loopback, link-local, and unique-local hosts", () => {
    for (const host of [
      "localhost",
      "127.0.0.1",
      "127.8.8.8",
      "0.0.0.0",
      "10.0.0.5",
      "172.16.4.4",
      "172.31.255.255",
      "192.168.1.1",
      "169.254.169.254",
      "100.64.0.1",
      "[::1]",
      "::1",
      "[fe80::1]",
      "[fc00::1]",
      "[fd12:3456::1]",
      "nas.local",
      "router.home.arpa",
      "printer.internal"
    ]) {
      assert.equal(isPrivateAddress(host), true, host);
    }
  });

  it("recognises public hosts", () => {
    for (const host of [
      "api.openai.com",
      "api.deepseek.com",
      "8.8.8.8",
      "1.1.1.1",
      "172.15.0.1",
      "172.32.0.1",
      "192.169.0.1",
      "100.63.255.255",
      "100.128.0.1",
      "2606:4700:4700::1111",
      "example.com"
    ]) {
      assert.equal(isPrivateAddress(host), false, host);
    }
  });

  it("rejects malformed dotted quads as non-private rather than crashing", () => {
    for (const host of ["999.1.1.1", "1.2.3", "not-an-ip", ""]) {
      assert.equal(isPrivateAddress(host), false, host);
    }
  });
});

describe("proxy target validation", () => {
  it("accepts a public HTTPS target", () => {
    const url = assertProxyTarget("https://api.openai.com/v1/chat/completions", { label: "AI 服务地址" });
    assert.equal(url.hostname, "api.openai.com");
    assert.equal(url.pathname, "/v1/chat/completions");
  });

  it("refuses cleartext, credentials, fragments, and empty values", () => {
    const cases = [
      "http://api.openai.com/v1/chat/completions",
      "https://user:secret@api.openai.com/v1/chat/completions",
      "https://api.openai.com/v1/chat/completions#fragment",
      "",
      "   ",
      "not a url",
      `https://api.openai.com/${"a".repeat(3000)}`
    ];
    for (const value of cases) {
      assert.throws(() => assertProxyTarget(value, { label: "AI 服务地址" }), TypeError, value);
    }
  });

  it("refuses private hosts for the AI forward even when the feed opt-in is set", async () => {
    // The AI forward never passes the operator opt-in, so a LAN address can
    // never be reached through it, whatever the feed configuration says.
    for (const endpoint of [
      "http://127.0.0.1:8080/v1/chat/completions",
      "https://192.168.1.10/v1/chat/completions",
      "http://nas.local:8080/v1/chat/completions"
    ]) {
      await assert.rejects(
        () => forwardAiCompletion({ endpoint, apiKey: "synthetic", payload: {} }, PROXY_CONFIG),
        TypeError,
        endpoint
      );
    }
  });

  it("allows a private feed target only when the operator opted in", () => {
    assert.throws(
      () => assertProxyTarget("http://127.0.0.1:9000/feed.json", { label: "公开资讯地址" }),
      TypeError
    );
    const allowed = assertProxyTarget("http://127.0.0.1:9000/feed.json", { label: "公开资讯地址", allowPrivate: true });
    assert.equal(allowed.hostname, "127.0.0.1");
  });

  it("still requires HTTPS for a public feed target even with the opt-in", () => {
    assert.throws(
      () => assertProxyTarget("http://example.com/feed.json", { label: "公开资讯地址", allowPrivate: true }),
      TypeError
    );
  });

  it("refuses a trailing-space URL that some clients add", () => {
    assert.throws(
      () => assertProxyTarget("https://api.openai.com/v1 ", { label: "AI 服务地址" }),
      TypeError
    );
  });
});
