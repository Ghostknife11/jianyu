import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { isObviousDailyTask } from "../packages/foe-opportunity/src/reference-patterns.js";

const ECOSYSTEMS = new Set([
  "existing-interest", "digital-game", "media", "reading", "sport", "making",
  "family-life", "nature", "travel", "place", "people", "real-world",
  "world-event", "digital-making", "real-project", "other",
]);
const GOALS = new Set(["CHILD", "CAREGIVER", "SHARED"]);
const COSTS = ["FREE_EXISTING", "FREE", "LOW", "MEDIUM", "HIGH"];
const ENERGIES = ["NONE", "LOW", "MEDIUM", "HIGH"];
const PRESSURES = new Set(["LOW", "MEDIUM", "HIGH", "UNKNOWN"]);

function nonBlank(value, maxLength) {
  return typeof value === "string" && value.trim().length > 0 && value.length <= maxLength;
}

function integerIn(value, minimum, maximum) {
  return Number.isInteger(value) && value >= minimum && value <= maximum;
}

function parseResponse(response) {
  if (typeof response !== "string") return response;
  const text = response.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  return JSON.parse(text);
}

/** Public synthetic screening only. This is not the Android Gate or a model grade. */
export function evaluateCase(benchmarkCase, rawResponse) {
  const findings = [];
  let response;
  try {
    response = parseResponse(rawResponse);
  } catch {
    findings.push("response-not-json");
  }
  const items = response?.opportunities;
  if (!Array.isArray(items)) {
    findings.push("opportunities-not-array");
    return {
      caseId: benchmarkCase.id,
      candidateCount: null,
      ecosystems: [],
      machineFindings: findings,
      humanReview: benchmarkCase.expectation.review,
    };
  }
  if (items.length > 5) findings.push("too-many-candidates");
  if (benchmarkCase.expectation.requiresEmpty && items.length > 0) findings.push("ignored-explicit-rest-or-refusal");
  if (!benchmarkCase.expectation.requiresEmpty && items.length === 0) findings.push("missed-positive-occasion");

  const seenEcosystems = new Set();
  items.forEach((item, index) => {
    const at = `candidate-${index + 1}`;
    if (!item || typeof item !== "object" || Array.isArray(item)) {
      findings.push(`${at}:not-an-object`);
      return;
    }
    for (const [field, maximum] of [["title", 120], ["explanation", 600], ["whyNow", 300]]) {
      if (!nonBlank(item[field], maximum)) findings.push(`${at}:${field}-invalid`);
    }
    if ([item.title, item.explanation, item.whyNow].some(isObviousDailyTask)) {
      findings.push(`${at}:daily-task-pressure`);
    }
    if (!ECOSYSTEMS.has(item.ecosystem)) findings.push(`${at}:ecosystem-invalid`);
    else if (seenEcosystems.has(item.ecosystem)) findings.push(`${at}:duplicate-ecosystem`);
    else seenEcosystems.add(item.ecosystem);
    if (!GOALS.has(item.primaryGoal)) findings.push(`${at}:goal-invalid`);
    if (typeof item.childPull !== "boolean") findings.push(`${at}:child-pull-invalid`);
    if (item.primaryGoal === "CAREGIVER" && item.childPull === false) findings.push(`${at}:caregiver-goal-without-child-pull`);
    if (item.childPull === false) findings.push(`${at}:insufficient-child-pull`);
    if (!integerIn(item.timeMinutes, 1, 1440)) findings.push(`${at}:time-invalid`);
    else if (item.timeMinutes > benchmarkCase.context.constraints.timeMinutesMax) findings.push(`${at}:exceeds-time`);
    if (!integerIn(item.travelMinutes, 0, 1440)) findings.push(`${at}:travel-invalid`);
    else if (item.travelMinutes > benchmarkCase.context.constraints.travelMinutesMax) findings.push(`${at}:exceeds-travel`);
    if (!COSTS.includes(item.costBand)) findings.push(`${at}:cost-invalid`);
    else if (COSTS.indexOf(item.costBand) > COSTS.indexOf(benchmarkCase.context.constraints.costBandMax)) findings.push(`${at}:exceeds-cost`);
    if (!ENERGIES.includes(item.caregiverEnergy)) findings.push(`${at}:energy-invalid`);
    else if (ENERGIES.indexOf(item.caregiverEnergy) > ENERGIES.indexOf(benchmarkCase.context.constraints.caregiverEnergyMax)) findings.push(`${at}:exceeds-energy`);
    if (typeof item.confidence !== "number" || !Number.isFinite(item.confidence) || item.confidence < 0 || item.confidence > 1) findings.push(`${at}:confidence-invalid`);
    if (!integerIn(item.minAge, 0, 18) || !integerIn(item.maxAge, 4, 25) || item.minAge > item.maxAge) {
      findings.push(`${at}:age-range-invalid`);
    } else if (benchmarkCase.age < item.minAge || benchmarkCase.age > item.maxAge) findings.push(`${at}:age-mismatch`);
    if (item.naturalEntry !== true) findings.push(`${at}:not-a-natural-entry`);
    if (!PRESSURES.has(item.interventionPressure)) findings.push(`${at}:pressure-invalid`);
    else if (item.interventionPressure === "HIGH") findings.push(`${at}:high-intervention-pressure`);
  });

  return {
    caseId: benchmarkCase.id,
    candidateCount: items.length,
    ecosystems: [...seenEcosystems],
    machineFindings: findings,
    humanReview: benchmarkCase.expectation.review,
  };
}

export function evaluateBenchmark(corpus, run) {
  if (corpus?.schema !== "org.foe.public-model-benchmark/v1" || !Array.isArray(corpus.cases)) {
    throw new TypeError("Unsupported public synthetic benchmark corpus");
  }
  if (!run?.responses || typeof run.responses !== "object" || Array.isArray(run.responses)) {
    throw new TypeError("Run must contain responses keyed by case ID");
  }
  const cases = corpus.cases.map((benchmarkCase) => {
    if (!Object.hasOwn(run.responses, benchmarkCase.id)) {
      return {
        caseId: benchmarkCase.id,
        candidateCount: null,
        ecosystems: [],
        machineFindings: ["response-missing"],
        humanReview: benchmarkCase.expectation.review,
      };
    }
    return evaluateCase(benchmarkCase, run.responses[benchmarkCase.id]);
  });
  return {
    schema: "org.foe.public-model-benchmark-screen/v1",
    corpusVersion: corpus.version,
    model: run.model ?? null,
    cases,
    machineFindingCount: cases.reduce((total, item) => total + item.machineFindings.length, 0),
    conclusion: "仅为公开合成样例的机器筛查；人工复核、真实服务多次运行和来源证据完成前，不得发布模型评级。",
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [, , corpusPath, runPath] = process.argv;
  if (!corpusPath || !runPath) {
    process.stderr.write("Usage: node scripts/evaluate-model-benchmark.mjs <public-cases.json> <synthetic-responses.json>\n");
    process.exitCode = 2;
  } else {
    try {
      const corpus = JSON.parse(await readFile(corpusPath, "utf8"));
      const run = JSON.parse(await readFile(runPath, "utf8"));
      const report = evaluateBenchmark(corpus, run);
      process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
      if (report.machineFindingCount > 0) process.exitCode = 1;
    } catch (error) {
      process.stderr.write(`${error instanceof Error ? error.message : "Benchmark input failed"}\n`);
      process.exitCode = 2;
    }
  }
}
