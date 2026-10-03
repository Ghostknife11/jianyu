import test from "node:test";
import assert from "node:assert/strict";
import { assertEvent, assertOpportunity, makeNothingOption } from "../packages/foe-schema/src/index.js";

test("versioned event preserves author and visibility", () => {
  const event = {
    schema: "org.foe.event/v1",
    eventId: "evt_demo",
    eventType: "evidence.observed",
    eventVersion: 1,
    householdId: "household_demo",
    subjectId: "child_demo",
    authorId: "caregiver_demo",
    actorRole: "caregiver",
    deviceId: "device_demo",
    occurredAt: "2026-09-12T10:00:00+08:00",
    recordedAt: "2026-09-12T10:01:00+08:00",
    visibility: "family",
    payload: { kind: "direct-observation" }
  };
  assert.equal(assertEvent(event), event);
});

test("opportunity requires goal provenance and verification", () => {
  assert.throws(() => assertOpportunity({}), /schema/);
  assert.equal(makeNothingOption().type, "nothing");
});
