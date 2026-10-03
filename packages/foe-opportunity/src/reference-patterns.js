/** Narrow reference-policy clause pattern, not a child-intent or recommendation-quality classifier. */
export function isObviousDailyTask(text) {
  if (typeof text !== "string") return false;
  return text.split(/[，,。；;：:！!？?\n]+/u).some((part) => {
    const clause = part.trim().toLowerCase();
    return /^(?:然后|接着|之后|回家后|家长)?\s*(每天|每日|连续[0-9一二三四五六七十]+天).{0,16}(刷题|做题|作业|练习|打卡|做.{0,8}题)/u.test(clause) ||
      /^(?:然后|接着|之后|回家后|家长)?\s*(必须|强制|要求孩子).{0,16}(完成|刷题|做题|打卡|练习)/u.test(clause) ||
      /^(?:then\s+)?(daily|every day|mandatory|must).{0,32}(worksheet|homework|drill|quiz|check-in)/u.test(clause);
  });
}
