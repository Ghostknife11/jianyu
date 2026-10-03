const CLAUSE_BOUNDARY = /[。！？!?；;，,\n]|但是|不过|可是|而是|但|\bbut\b/u;
const REFUSAL_CUES = [
  "不想", "不要", "不喜欢", "不愿", "不再", "不感兴趣", "没兴趣", "拒绝", "讨厌",
  "别看", "别玩", "别去", "别再", "避免",
  "don't want", "do not want", "doesn't want", "does not want", "doesn't like",
  "not interested", "no more", "hate", "avoid"
];
const PULL_CUES = [
  "想", "喜欢", "好奇", "主动", "问", "研究", "试试", "尝试", "探索", "关注", "迷上", "感兴趣",
  "want", "like", "curious", "ask", "explore", "try", "interested"
];
const CAREGIVER_LED_CUES = [
  "家长想", "家长希望", "父母想", "父母希望", "爸爸想", "妈妈想", "老师想", "监护人想",
  "parent wants", "caregiver wants", "teacher wants"
];
const EXTERNAL_BACKGROUND_CUES = [
  "天气预报说", "天气预报显示", "新闻说", "新闻报道", "学校通知",
  "weather forecast says", "news says", "school notice"
];
const CAREGIVER_REFUSAL = /(?:家长|父母|爸爸|妈妈|老师|监护人)(?:说)?(?:不想|不要|不喜欢|不愿|别看|别玩|别去|避免)|(?:parent|caregiver|teacher) (?:does not want|doesn't want|is not interested|doesn't like|wants to avoid)/u;

function hasExplicitPullCue(clause) {
  return !CAREGIVER_LED_CUES.some((cue) => clause.includes(cue)) &&
    PULL_CUES.some((cue) => clause.includes(cue));
}

function isOnlyExternalAgenda(clause) {
  return CAREGIVER_LED_CUES.some((cue) => clause.includes(cue)) ||
    (EXTERNAL_BACKGROUND_CUES.some((cue) => clause.includes(cue)) && !hasExplicitPullCue(clause));
}

function matchingClauses(expression, term) {
  const normalizedTerm = typeof term === "string" ? term.trim().toLowerCase() : "";
  if (normalizedTerm.length < 2 || typeof expression !== "string") return [];
  return expression.toLowerCase().split(CLAUSE_BOUNDARY).filter((clause) => clause.includes(normalizedTerm));
}

// A conservative lexical guard for optional sources, not general language understanding.
export function currentInterestMentionsTerm(expression, term) {
  return matchingClauses(expression, term).some((clause) =>
    !REFUSAL_CUES.some((cue) => clause.includes(cue)) && !isOnlyExternalAgenda(clause));
}

export function currentInterestRefusesTerm(expression, term) {
  return matchingClauses(expression, term).some((clause) =>
    REFUSAL_CUES.some((cue) => clause.includes(cue)) && !CAREGIVER_REFUSAL.test(clause));
}

// An isolated refusal or clearly adult-led/background clause does not justify a new entrance.
export function currentInterestHasNonRefusalClue(expression) {
  if (typeof expression !== "string") return false;
  const clauses = expression.toLowerCase().split(CLAUSE_BOUNDARY).filter((clause) => clause.trim());
  const refused = (clause) => REFUSAL_CUES.some((cue) => clause.includes(cue));
  const hasRefusal = clauses.some(refused);
  return clauses.some((clause) => !refused(clause) && !isOnlyExternalAgenda(clause) &&
    (!hasRefusal || hasExplicitPullCue(clause)));
}
