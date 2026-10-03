const now = () => new Date().toISOString();

function baseOpportunity({ id, title, ecosystem, explanation, whyNow, minutes, energy = "low", travel = 0, score }) {
  return {
    schema: "org.foe.opportunity/v1",
    opportunityId: id,
    title,
    entryPoint: { motivation: "child-current-pull", whyNow, startupCost: "low" },
    ecosystem,
    goalAlignment: { primary: "child", secondary: ["shared"] },
    childPull: true,
    requirements: {
      timeMinutes: minutes,
      costBand: "free-existing",
      caregiverEnergy: energy,
      travelMinutes: travel
    },
    source: { kind: "local-template", publisher: "org.jianyu.local-discovery", retrievedAt: now() },
    verification: "idea",
    risks: [],
    score,
    explanation,
    sponsorship: null
  };
}

export function discoverLocalOpportunities(interest) {
  const subject = interest.trim().slice(0, 42) || "这件事";
  const token = crypto.randomUUID().slice(0, 8);
  return [
    baseOpportunity({
      id: `local-existing-${token}`,
      title: `从已经在做的「${subject}」里，多问一个真问题`,
      ecosystem: "existing-interest",
      whyNow: "孩子已经主动投入，不需要另造一个学习任务",
      minutes: 20,
      score: 0.9,
      explanation: "跟着孩子的玩法或话题走，只在自然停顿时问：你最想弄明白哪一件事？不要求马上找到答案。"
    }),
    baseOpportunity({
      id: `local-make-${token}`,
      title: `把「${subject}」变成一个能动手的小东西`,
      ecosystem: "making",
      whyNow: "从喜欢走向制作，仍然保留孩子自己的方向",
      minutes: 45,
      energy: "medium",
      score: 0.84,
      explanation: "用家里已有的纸、积木、旧物或绘画工具做一个版本。成品不重要，重点是让直觉有地方落脚。"
    }),
    baseOpportunity({
      id: `local-family-${token}`,
      title: `找一个认识的人，听听他与「${subject}」的真实经验`,
      ecosystem: "family-knowledge",
      whyNow: "真实的人和经历有时比再看一段内容更有连接感",
      minutes: 30,
      score: 0.78,
      explanation: "先从家人和朋友中想一想谁可能知道一点；由孩子决定要不要问、问什么，也可以只听故事。"
    }),
    baseOpportunity({
      id: `local-observe-${token}`,
      title: `出门时顺便找找「${subject}」在现实里的痕迹`,
      ecosystem: "nearby-world",
      whyNow: "把屏幕、书本或想象里的兴趣接回日常世界",
      minutes: 60,
      energy: "medium",
      travel: 20,
      score: 0.76,
      explanation: "不专门安排课程，只在本来要出门时留意相关的物体、工作、场所或现象，并让孩子决定停不停。"
    })
  ];
}

export function looksLikeMotorsport(interest) {
  return /赛车|汽车|跑车|轮胎|过弯|f1|kart|卡丁车/i.test(interest);
}
