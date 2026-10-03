import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { evaluateBenchmark, evaluateCase } from "../scripts/evaluate-model-benchmark.mjs";

const corpus = JSON.parse(await readFile(new URL("../models/benchmark/v0.1-cases.json", import.meta.url), "utf8"));
const syntheticRun = JSON.parse(await readFile(new URL("../models/benchmark/example-run.synthetic.json", import.meta.url), "utf8"));
const byId = (id) => corpus.cases.find((item) => item.id === id);

function safeCoPlay() {
  return {
    opportunities: [{
      title: "洗澡时让孩子自己比较几个杯子的装水量",
      explanation: "顺着原本就在玩的水，不安排额外练习。",
      whyNow: "孩子正在主动比较杯子。",
      ecosystem: "family-life",
      primaryGoal: "CHILD",
      childPull: true,
      timeMinutes: 10,
      costBand: "FREE_EXISTING",
      caregiverEnergy: "LOW",
      travelMinutes: 0,
      confidence: 0.65,
      minAge: 4,
      maxAge: 6,
      naturalEntry: true,
      interventionPressure: "LOW",
    }],
  };
}

test("public benchmark has multiple fictional lifecycle, Nothing, and source-discipline cases", () => {
  assert.equal(corpus.schema, "org.foe.public-model-benchmark/v1");
  assert.equal(corpus.cases.length, 6);
  assert.equal(new Set(corpus.cases.map((item) => item.id)).size, corpus.cases.length);
  assert.equal(corpus.cases.filter((item) => item.expectation.requiresEmpty).length, 2);
  assert.ok(corpus.cases.some((item) => item.id === "unverified-city-event"));
  assert.ok(corpus.cases.every((item) => item.expectation.review.length > 0));
  assert.ok(corpus.cases.every((item) => item.context.goals?.child));
  assert.ok(corpus.cases.every((item) => !Object.hasOwn(item.context, "coarseRegion")));
  assert.ok(corpus.cases.every((item) => !Object.hasOwn(item.context, "recentInterventionCount7Days")));
  assert.ok(corpus.cases.filter((item) => item.context.lifecycleStage === "共选")
    .every((item) => item.expectation.review.some((question) => /孩子|家长/.test(question))));
});

test("machine screen accepts a feasible entry but never claims a model grade", () => {
  const report = evaluateCase(byId("co-play-water"), safeCoPlay());
  assert.deepEqual(report.machineFindings, []);
  const wholeRun = evaluateBenchmark(corpus, syntheticRun);
  assert.equal(wholeRun.machineFindingCount, 0);
  assert.doesNotMatch(wholeRun.conclusion, /推荐模型|兼容模型/);
  assert.ok(wholeRun.cases.every((item) => item.humanReview.length > 0));
});

test("machine screen catches refusal, reality constraints, and malformed output", () => {
  const veto = evaluateCase(byId("child-declines-this-week"), safeCoPlay());
  assert.ok(veto.machineFindings.includes("ignored-explicit-rest-or-refusal"));
  const impossible = safeCoPlay();
  impossible.opportunities[0].timeMinutes = 200;
  impossible.opportunities[0].costBand = "HIGH";
  const practical = evaluateCase(byId("co-play-water"), impossible);
  assert.ok(practical.machineFindings.includes("candidate-1:exceeds-time"));
  assert.ok(practical.machineFindings.includes("candidate-1:exceeds-cost"));
  assert.ok(evaluateCase(byId("co-play-water"), "not JSON").machineFindings.includes("response-not-json"));
});

test("machine screen does not accept a zero-minute activity as a natural door", () => {
  const instant = safeCoPlay();
  instant.opportunities[0].timeMinutes = 0;
  const report = evaluateCase(byId("co-play-water"), instant);
  assert.ok(report.machineFindings.includes("candidate-1:time-invalid"));
});

test("machine screen catches a high-confidence child-labeled route that admits no child pull", () => {
  const unsupported = safeCoPlay();
  unsupported.opportunities[0].childPull = false;
  unsupported.opportunities[0].confidence = 0.99;
  const report = evaluateCase(byId("co-play-water"), unsupported);
  assert.ok(report.machineFindings.includes("candidate-1:insufficient-child-pull"));
});

test("machine screen catches an explicit daily assignment even when the model calls it child-led", () => {
  const disguised = safeCoPlay();
  disguised.opportunities[0].title = "每天做三页速度练习";
  disguised.opportunities[0].confidence = 0.99;
  const report = evaluateCase(byId("co-play-water"), disguised);
  assert.ok(report.machineFindings.includes("candidate-1:daily-task-pressure"));
  assert.ok(!report.machineFindings.includes("candidate-1:insufficient-child-pull"));

  disguised.opportunities[0].title = "孩子自己想弄懂速度公式";
  assert.ok(!evaluateCase(byId("co-play-water"), disguised).machineFindings.includes("candidate-1:daily-task-pressure"));

  disguised.opportunities[0].explanation = "先观察真实弯道，然后每天做三页速度练习。";
  assert.ok(evaluateCase(byId("co-play-water"), disguised).machineFindings.includes("candidate-1:daily-task-pressure"));
  disguised.opportunities[0].explanation = "自己探索转弯，不需要每天做三页练习。";
  assert.ok(!evaluateCase(byId("co-play-water"), disguised).machineFindings.includes("candidate-1:daily-task-pressure"));
  disguised.opportunities[0].whyNow = "孩子正在玩水；然后每天做三页练习。";
  assert.ok(evaluateCase(byId("co-play-water"), disguised).machineFindings.includes("candidate-1:daily-task-pressure"));
});

test("empty answers are legitimate for refusal but not a blanket benchmark shortcut", () => {
  assert.deepEqual(evaluateCase(byId("no-time-no-energy"), { opportunities: [] }).machineFindings, []);
  assert.ok(evaluateCase(byId("motorsport-multiple-doors"), { opportunities: [] }).machineFindings.includes("missed-positive-occasion"));
});
