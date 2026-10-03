import test from "node:test";
import assert from "node:assert/strict";
import { lifecycleAuthority, resolveLifecycleStage } from "../packages/policy-sdk/src/index.js";

test("lifecycle maps 4–15 and Graduation boundaries", () => {
  assert.equal(resolveLifecycleStage(4), "co-play");
  assert.equal(resolveLifecycleStage(7), "accompany");
  assert.equal(resolveLifecycleStage(10), "co-select");
  assert.equal(resolveLifecycleStage(13), "hand-over");
  assert.equal(resolveLifecycleStage(16), "graduation");
});

test("authority transfers to child and caregiver modeling stops at Graduation", () => {
  assert.equal(lifecycleAuthority("hand-over").primary, "child");
  assert.equal(lifecycleAuthority("graduation").stopCaregiverModeling, true);
});
