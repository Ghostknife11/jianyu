// The offline demonstration pack.
//
// It is built in the browser from the child's own words and from a handful of
// fixed templates. Nothing is sent anywhere and nothing is stored: it exists so
// a family can see the shape of the result — several doors and留白 — before
// connecting any provider, and so the app still works on a NAS with no outbound
// access. The templates carry no scores that outrank the family's judgement and
// no curriculum of their own.

import { assertDeclarativePack } from "../../../../packages/pack-sdk/src/index.js";
import { assertOpportunity } from "../../../../packages/foe-schema/src/index.js";

export const OFFLINE_DEMO_PACK_ID = "org.jianyu.web.offline-demo";

// A template only surfaces when the interest itself carries the child's pull,
// which is what these terms test for. An expression that is only a refusal, or
// only an adult's agenda, therefore produces no door at all.
const PULL_TERMS = Object.freeze([
  "喜欢", "想", "好奇", "问", "主动", "迷上", "感兴趣", "试试", "研究", "探索", "关注",
  "want", "like", "curious", "ask", "try", "explore", "interested"
]);

const MAX_SUBJECT_LENGTH = 42;

function template({
  id,
  title,
  ecosystem,
  whyNow,
  explanation,
  minutes,
  energy = "low",
  travel = 0,
  score,
  startupCost = "low"
}) {
  const opportunity = {
    schema: "org.foe.opportunity/v1",
    opportunityId: id,
    title,
    entryPoint: { motivation: "child-current-pull", whyNow, startupCost },
    ecosystem,
    goalAlignment: { primary: "child", secondary: ["shared"] },
    childPull: true,
    requirements: { timeMinutes: minutes, costBand: "free-existing", caregiverEnergy: energy, travelMinutes: travel },
    source: { kind: "pack", publisher: OFFLINE_DEMO_PACK_ID },
    verification: "idea",
    risks: [],
    score,
    explanation,
    sponsorship: null,
    triggerTerms: PULL_TERMS
  };
  assertOpportunity(opportunity);
  return opportunity;
}

/** The child's own words, trimmed, for a title that names what they said. */
export function subjectFromInterest(interest) {
  const subject = typeof interest === "string" ? interest.trim().replace(/\s+/gu, " ") : "";
  return subject.slice(0, MAX_SUBJECT_LENGTH) || "这件事";
}

/**
 * Builds the demonstration pack. One template is built from the child's own
 * words so the page can show that the entry point came from them; the rest are
 * fixed, deliberately different ecosystems.
 */
export function createOfflineDemoPack(interest) {
  const subject = subjectFromInterest(interest);
  const pack = {
    schema: "org.foe.pack/v1",
    id: OFFLINE_DEMO_PACK_ID,
    version: "0.1.0",
    publisher: "Jianyu offline demonstration",
    license: "Apache-2.0",
    opportunities: [
      template({
        id: "offline-listen-first",
        title: `先听孩子把「${subject}」说清楚`,
        ecosystem: "child-question",
        whyNow: "孩子自己提到了它，先从他的版本开始，而不是从我们的解释开始",
        explanation: "请他讲一两句：最喜欢哪一点、最想弄明白什么。不纠正、不补充，只记下来。",
        minutes: 15,
        score: 0.9
      }),
      template({
        id: "offline-one-more-question",
        title: "从已经在做的事情里，多问一个真问题",
        ecosystem: "existing-interest",
        whyNow: "孩子已经主动投入，不需要另造一个学习任务",
        explanation: "跟着他的玩法或话题走，只在自然停顿时问：你最想弄明白哪一件事？不要求马上找到答案。",
        minutes: 20,
        score: 0.86
      }),
      template({
        id: "offline-make-something",
        title: "把兴趣变成一个能动手的小东西",
        ecosystem: "making",
        whyNow: "从喜欢走向制作，仍然保留孩子自己的方向",
        explanation: "用家里已有的纸、积木、旧物或绘画工具做一个版本。成品不重要，重点是让直觉有地方落脚。",
        minutes: 45,
        energy: "medium",
        score: 0.82
      }),
      template({
        id: "offline-ask-a-person",
        title: "找一个认识的人，听听他真实的经验",
        ecosystem: "family-knowledge",
        whyNow: "真实的人和经历有时比再看一段内容更有连接感",
        explanation: "先从家人和朋友中想一想谁可能知道一点；由孩子决定要不要问、问什么，也可以只听故事。",
        minutes: 30,
        score: 0.78
      }),
      template({
        id: "offline-notice-the-world",
        title: "出门时顺便找找它在现实里的痕迹",
        ecosystem: "nearby-world",
        whyNow: "把屏幕、书本或想象里的兴趣接回日常世界",
        explanation: "不专门安排课程，只在本来要出门时留意相关的物体、工作、场所或现象，并让孩子决定停不停。",
        minutes: 60,
        energy: "medium",
        travel: 20,
        score: 0.74
      })
    ]
  };
  assertDeclarativePack(pack);
  return Object.freeze(pack);
}
