import { ageFor, lifecycleLabel } from "./opportunity-service.js";

export const escapeHtml = (value) => String(value ?? "")
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;")
  .replaceAll("'", "&#039;");

const brandMark = '<span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>';

export function vaultEntryView(hasVault) {
  const currentYear = new Date().getFullYear();
  return `<main class="vault-entry">
    <section class="entry-story">
      <div class="entry-brand">${brandMark}<div><strong>见隅</strong><small>Jianyu</small></div></div>
      <div class="entry-copy">
        <p class="eyebrow">AI 在家长身后</p>
        <h1>从一隅，<br>看见更多可能。</h1>
        <p>从孩子当下真实想做的事出发，帮家庭找到自然、可行的现实入口。不是课程表，也不是另一套成长 KPI。</p>
      </div>
      <p class="entry-principle">用 AI 缩小家庭获得教育机会的差距，<br>而不是缩小孩子之间的差异。</p>
    </section>
    <section class="entry-panel">
      ${hasVault ? unlockForm() : createForm(currentYear)}
    </section>
  </main>`;
}

function unlockForm() {
  return `<form id="unlock-form" class="entry-form">
    <p class="eyebrow">Local Family Vault</p>
    <h2>欢迎回来</h2>
    <p>家庭资料已经加密保存在这台设备上。输入口令，在本地解锁。</p>
    <div class="form-stack">
      <label><span>保险箱口令</span><input name="passphrase" type="password" autocomplete="current-password" required autofocus></label>
      <p id="entry-error" class="form-error" role="alert"></p>
      <button class="primary-button wide-button" type="submit">打开家庭保险箱</button>
    </div>
    <div class="import-row">
      <label class="secondary-button" for="backup-import">从加密备份恢复</label>
      <input id="backup-import" class="file-input" type="file" accept="application/json,.json">
      <span class="form-note">导入后仍需原口令</span>
    </div>
  </form>`;
}

function createForm(currentYear) {
  return `<form id="create-vault-form" class="entry-form">
    <p class="eyebrow">第一次使用 · 约 1 分钟</p>
    <h2>建立你的家庭空间</h2>
    <p>这些信息只用于本设备上的初始体验。当前版本尚未完成外部安全审计，建议先用测试称呼体验。</p>
    <div class="form-stack">
      <div class="field-row">
        <label><span>家庭空间名称</span><input name="familyName" value="我的家庭" required maxlength="30"></label>
        <label><span>你的称呼</span><input name="caregiverName" placeholder="例如：妈妈、爸爸、小林" required maxlength="30"></label>
      </div>
      <div class="field-row">
        <label><span>孩子的称呼</span><input name="childName" placeholder="昵称即可" required maxlength="30"></label>
        <label><span>出生年份</span><input name="birthYear" type="number" min="${currentYear - 22}" max="${currentYear - 4}" value="${currentYear - 10}" required></label>
      </div>
      <div class="field-row">
        <label><span>设置保险箱口令</span><input name="passphrase" type="password" minlength="8" autocomplete="new-password" required></label>
        <label><span>再输入一次</span><input name="confirmation" type="password" minlength="8" autocomplete="new-password" required></label>
      </div>
      <p class="privacy-note">口令不会上传，也无法由项目方找回。浏览器原型使用 PBKDF2 与 AES-GCM；正式版本仍需内存困难型 KDF、原生密钥存储和独立审计。</p>
      <p id="entry-error" class="form-error" role="alert"></p>
      <button class="primary-button wide-button" type="submit">创建并进入见隅</button>
    </div>
  </form>`;
}

export function shellView(state, route, content, activeMemberId) {
  const navigation = [
    ["today", "⌂", "此刻"],
    ["children", "○", "孩子"],
    ["timeline", "⌇", "回望"],
    ["settings", "◇", "设置"]
  ];
  const activeMember = state.members.find((member) => member.id === activeMemberId) ?? state.members[0];
  return `<div class="shell">
    <aside class="sidebar">
      <div class="sidebar-brand">${brandMark}<div><strong>见隅</strong><small>MORE DOORS</small></div></div>
      <nav class="nav-list" aria-label="主要导航">
        ${navigation.map(([id, icon, label]) => `<button class="nav-item ${route === id ? "active" : ""}" data-route="${id}"><span class="nav-icon">${icon}</span><span class="nav-label">${label}</span></button>`).join("")}
      </nav>
      <div class="sidebar-foot"><p>Your child is<br>not our dataset.</p><button class="lock-button" data-action="lock">锁定保险箱</button></div>
    </aside>
    <div class="main-column">
      <header class="topbar"><p>多看见几扇门，少规定一条路。</p><div class="family-chip"><span>${escapeHtml(activeMember.displayName.slice(0, 1))}</span><select id="active-member" class="actor-select" aria-label="当前记录者">${state.members.map((member) => `<option value="${member.id}" ${member.id === activeMember.id ? "selected" : ""}>${escapeHtml(member.displayName)} · ${memberRoleName(member.role)}</option>`).join("")}</select></div></header>
      ${content}
    </div>
  </div>`;
}

export function todayView(state, resultState) {
  const child = state.children.find((item) => item.id === resultState.childId) ?? state.children[0];
  if (!child) return emptyChildView();
  const recentChoices = state.choices.filter((choice) => choice.childId === child.id).slice(0, 4);
  return `<main class="page">
    <div class="page-heading">
      <div><p class="eyebrow">从孩子当前的意愿开始</p><h1>${escapeHtml(child.displayName)} 最近在想什么？</h1><p>说一句你真正观察到的事就够了，不需要把孩子描述完整。</p></div>
      <span class="stage-badge">${escapeHtml(lifecycleLabel(child.birthYear))} · 约 ${ageFor(child.birthYear)} 岁</span>
    </div>
    <form id="interest-form" class="capture-card surface">
      <div class="capture-head"><h2>记录一个当下的兴趣信号</h2><label><span>为谁寻找</span><select name="childId">${state.children.map((item) => `<option value="${item.id}" ${item.id === child.id ? "selected" : ""}>${escapeHtml(item.displayName)}</option>`).join("")}</select></label></div>
      <label><span>孩子最近反复提起、主动选择、想玩或想弄明白的事情</span><textarea name="expression" required maxlength="500" placeholder="例如：最近一直在赛车游戏里研究调车，还问为什么有的车过弯更快。">${escapeHtml(resultState.draftExpression ?? "")}</textarea></label>
      <div class="context-grid">
        <label><span>你怎么知道的</span><select name="evidenceKind"><option value="child-stated">孩子自己说的</option><option value="child-choice">孩子反复选择</option><option value="caregiver-observed">家长观察到</option><option value="shared-experience">一起经历时发现</option></select></label>
        <label><span>这次最多用时</span><select name="timeMinutes"><option value="20">20 分钟</option><option value="45">45 分钟</option><option value="90" selected>90 分钟</option><option value="120">2 小时</option></select></label>
        <label><span>预算</span><select name="costBand"><option value="free">免费</option><option value="low" selected>少量</option><option value="medium">适中</option></select></label>
        <label><span>家长精力</span><select name="caregiverEnergy"><option value="low">轻松一点</option><option value="medium" selected>可以一起动手</option><option value="high">愿意投入</option></select></label>
        <label class="check-control"><input name="canTravel" type="checkbox" checked><span>可以顺路出门</span></label>
      </div>
      <label class="school-field"><span>学校最近或快要接触的内容（可留空，不会变成必学任务）</span><input name="schoolWindow" maxlength="120" placeholder="例如：速度、时间和简单比例"></label>
      <div class="goal-grid"><label><span>家长希望得到什么（可留空）</span><input name="caregiverGoal" maxlength="160" placeholder="例如：希望以后遇到物理概念时不陌生"></label><label><span>你们共同想要什么（可留空）</span><input name="sharedGoal" maxlength="160" placeholder="例如：周末一起做一件都觉得有趣的事"></label></div>
      <div class="capture-footer"><p>输入保存在本地加密保险箱中。World Brief 默认只收到地区、时间等公开查询。</p><button class="primary-button" type="submit">帮我看看几扇门 →</button></div>
    </form>
    ${resultState.outcome ? opportunityResults(resultState) : ""}
    ${recentChoices.length ? recentChoiceSection(recentChoices) : ""}
  </main>`;
}

function emptyChildView() {
  return `<main class="page"><div class="empty-state"><p class="eyebrow">还差一步</p><h2>先添加一个孩子</h2><p>只需要昵称和出生年份；以后随时可以继续添加。</p><button class="primary-button" data-route="children">去添加</button></div></main>`;
}

const ecosystemNames = {
  "digital-game": "游戏",
  "family-life": "家庭生活",
  "live-sport": "正在发生",
  "existing-interest": "已有兴趣",
  making: "动手制作",
  "family-knowledge": "身边的人",
  "nearby-world": "现实观察",
  nothing: "留白"
};

const goalNames = { child: "孩子意愿", shared: "共同目标", caregiver: "家长目标" };

const rejectionNames = {
  "blocked-safety-risk": "安全风险不合适",
  "exceeds-time": "超过可用时间",
  "exceeds-travel": "超过可接受路程",
  "exceeds-cost": "超过预算",
  "exceeds-caregiver-energy": "超过家长今天的精力",
  "caregiver-goal-without-child-pull": "只有家长目标，没有孩子当前意愿",
  "child-veto": "孩子已经表示不想做"
};

function opportunityResults(resultState) {
  const selected = resultState.outcome.selected.map(({ candidate, decision }) => ({ ...candidate, gateWarnings: decision.warnings }));
  const options = [...selected, resultState.outcome.nothing];
  const rejected = resultState.outcome.evaluated.filter(({ decision }) => decision.result === "reject");
  return `<section class="results-section" id="opportunity-results">
    <div class="section-row"><div><p class="eyebrow">Opportunity Gate 已检查</p><h2 class="section-title">这次可以看看这些</h2></div><p>${selected.length} 个不同方向 + 随时停下</p></div>
    <div class="option-grid">${options.map((option) => optionCard(option)).join("")}</div>
    ${rejected.length ? `<details class="gate-details"><summary>为什么有些建议没有出现？</summary><ul>${rejected.map(({ candidate, decision }) => `<li><strong>${escapeHtml(candidate.title)}</strong>：${escapeHtml(decision.reasons.map((reason) => rejectionNames[reason] ?? reason).join("；"))}</li>`).join("")}</ul></details>` : ""}
  </section>`;
}

function optionCard(option) {
  const isNothing = option.type === "nothing";
  const title = isNothing ? "这次什么都不做" : option.title;
  const explanation = isNothing ? "兴趣可以只是兴趣。今天停在这里，不会破坏任何进度，也不需要以后补回来。" : option.explanation;
  const why = isNothing ? "不行动也是家庭的正式选择。" : option.entryPoint?.whyNow;
  const ecosystem = isNothing ? "nothing" : option.ecosystem;
  const minutes = isNothing ? "无负担" : `${option.requirements?.timeMinutes ?? "?"} 分钟`;
  const source = isNothing ? "家庭选择" : option.source?.kind === "world-brief" ? "World Brief · 待确认" : "本地发现";
  const goal = isNothing ? "家庭选择" : goalNames[option.goalAlignment?.primary] ?? "未标明目标";
  return `<article class="option-card ${isNothing ? "nothing" : ""}">
    <div class="option-meta"><span>${escapeHtml(ecosystemNames[ecosystem] ?? ecosystem)} · ${escapeHtml(goal)}</span><span>${escapeHtml(minutes)}</span></div>
    <h3>${escapeHtml(title)}</h3><p>${escapeHtml(explanation)}</p><p class="why-now">为什么现在：${escapeHtml(why)}</p>
    <div class="card-foot"><span class="source-label">${escapeHtml(source)}</span><div>${isNothing ? "" : `<button class="veto-button" data-action="veto" data-opportunity-id="${escapeHtml(option.opportunityId)}">孩子不想做</button>`}<button class="choice-button" data-action="choose" data-opportunity-id="${escapeHtml(option.opportunityId)}">${isNothing ? "今天就到这里" : "想试试"}</button></div></div>
  </article>`;
}

function recentChoiceSection(choices) {
  return `<section class="recent-section"><div class="section-row"><h2 class="section-title">最近选择</h2><p>反馈完全可选</p></div><div class="choice-list">${choices.map((choice) => `<article class="choice-row"><div><h3>${escapeHtml(choice.opportunity.title)}</h3><p>${formatDate(choice.chosenAt)} · ${choice.status === "nothing" ? "选择留白" : ecosystemNames[choice.opportunity.ecosystem] ?? choice.opportunity.ecosystem}</p></div>${choice.status === "nothing" ? "" : choice.feedback ? `<span class="feedback-done">已记录：${feedbackName(choice.feedback.value)}</span>` : `<div class="feedbacks"><button class="feedback-button" data-action="feedback" data-choice-id="${choice.id}" data-value="liked">喜欢</button><button class="feedback-button" data-action="feedback" data-choice-id="${choice.id}" data-value="neutral">一般</button><button class="feedback-button" data-action="feedback" data-choice-id="${choice.id}" data-value="disliked">不喜欢</button></div>`}</article>`).join("")}</div></section>`;
}

export function childrenView(state) {
  const currentYear = new Date().getFullYear();
  return `<main class="page">
    <div class="page-heading"><div><p class="eyebrow">家庭成员</p><h1>每个孩子都有自己的节奏</h1><p>年龄只决定默认的权力与隐私边界，不代表能力等级。</p></div></div>
    <div class="two-column"><section class="child-list">${state.children.map((child) => `<article class="child-card surface"><div class="child-main"><span class="child-avatar">${escapeHtml(child.displayName.slice(0, 1))}</span><div><h2>${escapeHtml(child.displayName)}</h2><p>${child.birthYear} 年出生 · 约 ${ageFor(child.birthYear)} 岁 · ${lifecycleLabel(child.birthYear)}</p></div></div><span class="stage-badge">${lifecycleLabel(child.birthYear)}</span></article>`).join("") || '<div class="empty-state"><p>还没有孩子资料。</p></div>'}</section>
      <form id="add-child-form" class="add-panel surface"><h2>添加孩子</h2><div class="form-stack"><label><span>称呼或昵称</span><input name="displayName" required maxlength="30"></label><label><span>出生年份</span><input name="birthYear" type="number" min="${currentYear - 22}" max="${currentYear - 4}" value="${currentYear - 8}" required></label><p class="form-error" id="child-error"></p><button class="primary-button" type="submit">添加到家庭</button></div></form>
    </div>
  </main>`;
}

const eventNames = {
  "child.added": "添加了家庭成员",
  "member.added": "添加了照护成员",
  "interest.observed": "记录了一个兴趣信号",
  "opportunity.chosen": "选择了一扇门",
  "opportunity.nothing-chosen": "选择了这次不行动",
  "opportunity.child-vetoed": "孩子否决了一个建议",
  "evidence.corrected": "纠正了此前的理解",
  "opportunity.feedback-recorded": "留下了真实反馈"
};

function eventDescription(event, state) {
  const child = state.children.find((item) => item.id === event.subjectId)?.displayName ?? "孩子";
  if (event.eventType === "interest.observed") return `${child}：${event.payload.expression}`;
  if (event.eventType === "opportunity.chosen") return `${child} · ${event.payload.title}`;
  if (event.eventType === "opportunity.nothing-chosen") return `${child} · 没有把兴趣变成安排`;
  if (event.eventType === "opportunity.child-vetoed") return `${child}不想做：${event.payload.title}`;
  if (event.eventType === "evidence.corrected") return `${child}：${event.payload.correction}`;
  if (event.eventType === "opportunity.feedback-recorded") return `${child} · ${feedbackName(event.payload.value)}`;
  if (event.eventType === "child.added") return `${event.payload.displayName}加入了家庭空间`;
  if (event.eventType === "member.added") return `${event.payload.displayName}以${memberRoleName(event.payload.role)}身份加入`;
  return child;
}

export function timelineView(state) {
  const events = [...state.events].reverse();
  const correctedIds = new Set(state.events.filter((event) => event.eventType === "evidence.corrected").map((event) => event.payload.targetEventId));
  return `<main class="page"><div class="page-heading"><div><p class="eyebrow">Longitudinal Record</p><h1>回望，而不是评分</h1><p>这里只保留发生过的表达、选择与反馈，不生成“全面发展”分数。</p></div></div>${events.length ? `<section class="timeline">${events.map((event) => { const author = state.members.find((member) => member.id === event.authorId)?.displayName ?? "未知记录者"; return `<article class="timeline-item"><span class="timeline-dot"></span><div class="timeline-card surface"><time>${formatDate(event.occurredAt)} · ${escapeHtml(author)}记录</time><h3>${escapeHtml(eventNames[event.eventType] ?? event.eventType)}</h3><p>${escapeHtml(eventDescription(event, state))}</p>${event.eventType === "interest.observed" ? correctedIds.has(event.eventId) ? '<p class="timeline-corrected">这条理解后来被纠正；原记录为保留来龙去脉而没有被覆盖。</p>' : `<button class="timeline-action" data-action="correct-evidence" data-event-id="${event.eventId}" data-child-id="${event.subjectId}">这不是孩子的意思，纠正它</button>` : ""}</div></article>`; }).join("")}</section>` : '<div class="empty-state"><h2>还没有记录</h2><p>等一个真实的家庭时刻出现再来，不需要为了填满时间线而记录。</p></div>'}</main>`;
}

export function settingsView(state) {
  return `<main class="page"><div class="page-heading"><div><p class="eyebrow">Family Vault</p><h1>数据属于这个家庭</h1><p>初版先把本地加密、可导出和清晰边界做实。</p></div></div><section class="settings-list">
    <article class="setting-card surface"><div class="setting-row"><div><h2>本地家庭保险箱</h2><p>浏览器初版经 PBKDF2 派生密钥和 AES-GCM 加密后写入 IndexedDB，口令和解密密钥不持久化。它证明边界可行，但尚未达到真实家庭数据的正式发布安全门槛。</p></div><span class="status-pill">初版加密</span></div><div class="setting-actions"><button class="secondary-button" data-action="export-vault">导出加密备份</button><button class="quiet-button" data-action="lock">立即锁定</button></div></article>
    <article class="setting-card surface"><div class="setting-row"><div><h2>AI 与发现服务</h2><p>当前使用本地声明式 Pack、确定性 Opportunity Gate 和合成 World Brief。后续 LLMProvider、SearchProvider 与 WorldBriefProvider 通过公开接口接入，家庭上下文必须先经过 Context Firewall。</p></div><span class="status-pill neutral">本地演示</span></div></article>
    <article class="setting-card surface"><div class="setting-row"><div><h2>同步</h2><p>当前设备是唯一权威副本。NAS、WebDAV、S3 和第三方托管将作为可替换 SyncProvider 接入；同步前必须在客户端完成加密。</p></div><span class="status-pill neutral">仅此设备</span></div></article>
    <article class="setting-card surface"><div class="setting-row"><div><h2>安静模式</h2><p>见隅不是每日打卡工具。没有连续天数、覆盖率或“错过机会”提醒；大多数日子不打开完全正常。</p></div><span class="status-pill">默认开启</span></div></article>
    <article class="setting-card surface"><div class="setting-row"><div><h2>家庭记录者</h2><p>每个人的观察保留独立作者，不会被揉成一个匿名的“家庭判断”。孩子作为自己的记录者存在。</p></div><span class="status-pill neutral">${state.members.length} 位成员</span></div><form id="add-member-form" class="setting-actions"><input name="displayName" required maxlength="30" placeholder="称呼或昵称"><select name="role"><option value="caregiver">照护者</option><option value="guardian">监护人</option><option value="observer">家庭观察者</option></select><button class="secondary-button" type="submit">添加</button></form></article>
    <article class="setting-card surface"><div class="setting-row"><div><h2>擦除本设备数据</h2><p>删除本地密文并丢弃当前解密密钥。此操作无法撤销；如需保留，请先导出加密备份。</p></div><span class="status-pill neutral">密码学擦除</span></div><div class="setting-actions"><button class="danger-button" data-action="erase-vault">擦除家庭保险箱</button></div></article>
  </section></main>`;
}

function feedbackName(value) {
  return ({ liked: "喜欢", neutral: "一般", disliked: "不喜欢" })[value] ?? value;
}

function formatDate(value) {
  return new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function memberRoleName(role) {
  return ({ caregiver: "照护者", guardian: "监护人", observer: "观察者", child: "本人" })[role] ?? role;
}
