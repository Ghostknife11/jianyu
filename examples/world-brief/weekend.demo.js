export function createDemoWorldBriefProvider() {
  return {
    kind: "world-brief",
    describe() {
      return {
        id: "org.foe.demo.world-brief",
        kind: "world-brief",
        version: "0.1.0",
        dataCategories: ["public-region", "public-time-window", "public-category"]
      };
    },
    async getBrief(query) {
      if (query.region !== "demo-city") return [];
      return [
        {
          schema: "org.foe.opportunity/v1",
          opportunityId: "demo-public-race-screening",
          title: "周末公共赛车赛事直播活动（合成示例）",
          topics: ["赛车", "汽车"],
          entryPoint: {
            motivation: "watch-together",
            whyNow: "公开世界信息中出现了与当前兴趣相关的限时事件",
            startupCost: "low"
          },
          ecosystem: "live-sport",
          goalAlignment: { primary: "shared", secondary: ["child"] },
          childPull: true,
          requirements: {
            timeMinutes: 90,
            costBand: "low",
            caregiverEnergy: "low",
            travelMinutes: 25
          },
          source: {
            kind: "world-brief",
            publisher: "org.foe.demo.world-brief",
            retrievedAt: "2026-09-12T08:00:00+08:00",
            url: "https://example.invalid/synthetic-event"
          },
          verification: "likely",
          freshUntil: "2099-09-13T00:00:00+08:00",
          risks: [],
          score: 0.82,
          explanation: "需由家庭确认时间、地点和名额；示例不对应真实活动",
          sponsorship: null
        }
      ];
    }
  };
}
