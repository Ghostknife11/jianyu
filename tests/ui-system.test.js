import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

const uiRoot = path.resolve(
  "apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/ui",
);

async function kotlinFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(
    entries.map((entry) => {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) return kotlinFiles(target);
      return entry.name.endsWith(".kt") ? [target] : [];
    }),
  );
  return nested.flat();
}

test("Android product screens use the shared card and selection language", async () => {
  const files = (await kotlinFiles(uiRoot)).filter(
    (file) => !file.endsWith(`${path.sep}JianyuComponents.kt`),
  );
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    if (/\bCard\s*\(/.test(source)) violations.push(`${path.basename(file)} uses Card directly`);
    if (/\bFilterChip\s*\(/.test(source)) violations.push(`${path.basename(file)} uses FilterChip directly`);
    if (/RoundedCornerShape\(99\.dp\)/.test(source)) violations.push(`${path.basename(file)} draws a pill directly`);
  }

  assert.deepEqual(
    violations,
    [],
    "Use JianyuCard, JianyuChoiceChip, and JianyuPill so later screens cannot silently invent another visual language.",
  );
});

test("all main Android pages share the same page header and layout rhythm", async () => {
  const files = ["TodayScreen.kt", "FamilyScreens.kt", "SettingsScreen.kt"];
  for (const file of files) {
    const source = await readFile(path.join(uiRoot, file), "utf8");
    assert.match(source, /JianyuPageHeader\(/, `${file} needs the shared page hierarchy`);
    assert.match(source, /JianyuLayout\.screenHorizontal/, `${file} needs the shared gutter`);
    assert.match(source, /JianyuLayout\.screenVertical/, `${file} needs the shared gutter`);
    assert.match(source, /JianyuLayout\.sectionGap/, `${file} needs the shared rhythm`);
  }
});

test("shared page and section titles keep readable contrast in dark mode", async () => {
  const components = await readFile(path.join(uiRoot, "JianyuComponents.kt"), "utf8");
  const pageHeader = components.slice(components.indexOf("internal fun JianyuPageHeader("), components.indexOf("internal fun JianyuSectionHeader("));
  const sectionHeader = components.slice(components.indexOf("internal fun JianyuSectionHeader("), components.indexOf("internal fun JianyuCardTitle("));

  assert.match(pageHeader, /style = MaterialTheme\.typography\.headlineSmall,\s*color = MaterialTheme\.colorScheme\.onSurface/);
  assert.match(sectionHeader, /style = MaterialTheme\.typography\.titleLarge,\s*color = MaterialTheme\.colorScheme\.onSurface/);
});

test("forked brand names cannot push the local-encryption status out of the app bar", async () => {
  const app = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  const appBar = app.slice(app.indexOf("internal fun JianyuTopAppBar("), app.indexOf("private fun JianyuNavigation("));

  assert.match(app, /topBar = \{ JianyuTopAppBar\(state\.brandName\) \}/);
  assert.match(appBar, /Modifier\.weight\(1f, fill = false\)/);
  assert.match(appBar, /maxLines = 1/);
  assert.match(appBar, /overflow = TextOverflow\.Ellipsis/);
  assert.match(appBar, /Text\("本机加密保存"/);
});

test("Android product screens use one inset language for nested information", async () => {
  const files = ["TodayScreen.kt", "FamilyScreens.kt", "SettingsScreen.kt", "LifecycleUi.kt"];
  for (const file of files) {
    const source = await readFile(path.join(uiRoot, file), "utf8");
    assert.doesNotMatch(source, /\bSurface\s*\(/, `${file} should use JianyuInset for nested surfaces`);
  }
});

test("Today optional sections share disclosure controls and demo naming", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const composer = today.slice(today.indexOf("private fun OpportunityComposer("), today.indexOf("private fun ProviderStatus("));
  const graduation = today.slice(today.indexOf("if (!belowMinimumAge && stage == LifecycleStage.GRADUATION) {"), today.indexOf("if (!belowMinimumAge) discoveryResultItems("));
  const provider = today.slice(today.indexOf("private fun ProviderStatus("), today.indexOf("private fun RequestContextPreview("));

  assert.match(composer, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看现实条件"[\s\S]*?expandedLabel = "收起现实条件"/);
  assert.match(graduation, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看家庭副本选项"[\s\S]*?expandedLabel = "收起家庭副本选项"/);
  assert.equal(provider.match(/"选择离线演示"/g)?.length, 2);
  assert.doesNotMatch(provider, /"查看演示"/);
});

test("Android product copy keeps one family-facing opportunity vocabulary", async () => {
  const files = await kotlinFiles(uiRoot);
  const violations = [];
  let ecosystemLabelDefinitions = 0;

  for (const file of files) {
    const source = await readFile(file, "utf8");
    ecosystemLabelDefinitions += source.match(/fun\s+String\.asEcosystemLabel\s*\(/g)?.length ?? 0;
    for (const phrase of ["推荐", "候选", "方向", "方案", "上下文", "画像", "档案"]) {
      if (source.includes(phrase)) {
        violations.push(`${path.basename(file)} exposes the drift phrase: ${phrase}`);
      }
    }
    if (!file.endsWith(`${path.sep}UiLabels.kt`) && source.includes('"其他入口"')) {
      violations.push(`${path.basename(file)} defines an ecosystem fallback outside UiLabels.kt`);
    }
  }

  assert.equal(
    ecosystemLabelDefinitions,
    1,
    "Define the ecosystem display vocabulary once in UiLabels.kt.",
  );
  assert.deepEqual(
    violations,
    [],
    "Use 入口 for family-facing options and keep protocol wording behind progressive disclosure.",
  );
});

test("Android product layouts do not freeze localized copy into narrow columns", async () => {
  const files = await kotlinFiles(uiRoot);
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    if (/\.chunked\(2\)/.test(source)) {
      violations.push(`${path.basename(file)} fixes dynamic choices into two columns`);
    }
    const singleLineCount = source.match(/maxLines\s*=\s*1\b/g)?.length ?? 0;
    const appBarBrandOnly = path.basename(file) === "JianyuApp.kt" && singleLineCount === 1 &&
      /internal fun JianyuTopAppBar\([\s\S]*?brandName,[\s\S]*?maxLines = 1,[\s\S]*?overflow = TextOverflow\.Ellipsis/.test(source);
    if (singleLineCount > 0 && !appBarBrandOnly) {
      violations.push(`${path.basename(file)} truncates family-facing copy to one line`);
    }
  }

  assert.deepEqual(
    violations,
    [],
    "Let localized content wrap or stack at 360 dp and enlarged font scales.",
  );
});

test("opportunity and Nothing cards share accessible, heading-aware title wrapping", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const components = await readFile(path.join(uiRoot, "JianyuComponents.kt"), "utf8");

  assert.equal(today.match(/JianyuOpportunityTitle\(item\.title\)/g)?.length, 2);
  assert.match(components, /fun JianyuOpportunityTitle\(text: String\)/);
  assert.match(components, /lineBreak = LineBreak\.Heading/);
  assert.match(components, /modifier = Modifier\.semantics \{ heading\(\) \}/);
});

test("Android product copy separates brand identity from operational language", async () => {
  const files = await kotlinFiles(uiRoot);
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, "utf8");
    const hardCodedBrandStrings = source.match(/"[^"\n]*见隅[^"\n]*"/g) ?? [];
    if (hardCodedBrandStrings.length > 0) {
      violations.push(`${path.basename(file)} hard-codes the default brand in product copy`);
    }
  }

  const appSource = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  assert.match(appSource, /hero\s*=\s*state\.brandHero/);
  assert.match(appSource, /mission\s*=\s*state\.brandMission/);
  assert.deepEqual(
    violations,
    [],
    "Show the configured name only on identity surfaces; write operational, safety, and privacy copy in a brand-neutral voice.",
  );
});

test("Android navigation uses the same selected-state color semantics as product controls", async () => {
  const appSource = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");

  assert.match(appSource, /selectedIconColor\s*=\s*MaterialTheme\.colorScheme\.primary/);
  assert.match(appSource, /selectedTextColor\s*=\s*MaterialTheme\.colorScheme\.primary/);
  assert.match(appSource, /indicatorColor\s*=\s*MaterialTheme\.colorScheme\.primaryContainer/);
});

test("development preview and destructive danger use distinct theme families", async () => {
  const theme = await readFile(path.join(uiRoot, "theme", "Theme.kt"), "utf8");
  const components = await readFile(path.join(uiRoot, "JianyuComponents.kt"), "utf8");

  assert.match(theme, /tertiaryContainer = Color\(0xFFE0E5F2\)/);
  assert.match(theme, /tertiaryContainer = Color\(0xFF41495E\)/);
  assert.match(theme, /errorContainer = Color\(0xFFFFDAD6\)/);
  assert.match(theme, /errorContainer = Color\(0xFF93000A\)/);
  assert.match(components, /JianyuCardTone\.PREVIEW -> MaterialTheme\.colorScheme\.tertiaryContainer/);
  assert.match(components, /JianyuCardTone\.DANGER -> MaterialTheme\.colorScheme\.errorContainer/);
});

test("Settings groups sources and data without a duplicate status dashboard", async () => {
  const source = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const sourceSection = source.indexOf('title = "机会来源"');
  const connection = source.indexOf('JianyuCardTitle("AI 发现")');
  const world = source.indexOf('JianyuCardTitle("世界信息")');
  const dataSection = source.indexOf('title = "资料与设备"');
  const privacy = source.indexOf('JianyuCardTitle("你的数据，边界清楚")');

  assert.ok(sourceSection > 0 && connection > sourceSection && world > connection);
  assert.ok(dataSection > world && privacy > dataSection);
  assert.doesNotMatch(source, /CapabilityOverview|CapabilityStatusRow|"现在的状态"/);
});

test("optional world information setup stays collapsed until requested", async () => {
  const source = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");

  assert.match(source, /showWorldBriefForm by remember \{ mutableStateOf\(false\) \}/);
  assert.match(source, /if \(!showWorldBriefForm\)/);
  assert.match(source, /Text\("连接世界信息服务"\)/);
  assert.match(source, /showWorldBriefForm = false[\s\S]*?worldBriefApiKey = ""[\s\S]*?onClearSourceFormError\(SourceFormKind\.WORLD\)/);
});

test("AI setup keeps technical fields behind an explicit connection action", async () => {
  const source = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");

  assert.match(source, /showAiForm by remember \{ mutableStateOf\(false\) \}/);
  assert.match(source, /if \(!showAiForm\) \{[\s\S]*?if \(state\.aiConfigured\)/);
  assert.match(source, /Button\(onClick = \{ onClearSourceFormError\(SourceFormKind\.AI\); showAiForm = true \}, enabled = !sourceFormBusy\) \{ Text\("连接 AI 服务"\) \}/);
  assert.match(source, /showAiForm = false[\s\S]*?apiKey = ""[\s\S]*?onClearSourceFormError\(SourceFormKind\.AI\)/);
  assert.match(source, /if \(state\.aiConfigured \|\| showAiForm\) \{/);
});

test("saved connections do not turn maintenance into a primary action", async () => {
  const source = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");

  assert.match(source, /TextButton\(onClick = onCheckAi, enabled = !state\.aiCapabilityChecking && !sourceFormBusy\)/);
  assert.equal(source.match(/SourceMaintenanceActions\(/g)?.length, 3);
  assert.match(source, /SourceMaintenanceActions\(\s*onReplace = \{ onClearSourceFormError\(SourceFormKind\.AI\); showAiForm = true \},\s*onDisconnect = \{ pendingSourceDisconnect = SourceFormKind\.AI \}/);
  assert.match(source, /SourceMaintenanceActions\(\s*onReplace = \{ onClearSourceFormError\(SourceFormKind\.WORLD\); showWorldBriefForm = true \},\s*onDisconnect = \{ pendingSourceDisconnect = SourceFormKind\.WORLD \}/);
  assert.match(source, /pendingSourceDisconnect\?\.let \{ kind ->[\s\S]*?JianyuDangerTextButton\(onClick = \{[\s\S]*?if \(kind == SourceFormKind\.AI\) onRemoveAi\(\) else onRemoveWorldBrief\(\)/);
  assert.match(source, /dismissButton = \{ TextButton\(onClick = \{ pendingSourceDisconnect = null \}\) \{ Text\("保留连接"\) \} \}/);
  assert.match(source, /private fun SourceMaintenanceActions\([\s\S]*?FlowRow\([\s\S]*?OutlinedButton\(onClick = onReplace, enabled = enabled\) \{ Text\("更换连接"\) \}[\s\S]*?TextButton\(onClick = onDisconnect, enabled = enabled\) \{ Text\("断开"\) \}/);
  const aiCard = source.slice(source.indexOf('JianyuCardTitle("AI 发现")'), source.indexOf('JianyuCardTitle("世界信息")'));
  assert.ok(aiCard.indexOf('SourceMaintenanceActions(') < aiCard.indexOf('collapsedLabel = "查看连接检查"'));
  assert.ok(aiCard.indexOf('collapsedLabel = "查看连接检查"') < aiCard.indexOf('TextButton(onClick = onCheckAi'));
  assert.doesNotMatch(aiCard, /FlowRow\([^)]*\) \{\s*TextButton\(onClick = onCheckAi/);
});

test("saving BYOK settings does not claim a live connection or model grade", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const components = await readFile(path.join(uiRoot, "JianyuComponents.kt"), "utf8");

  assert.match(components, /fun JianyuSavedConnection\([\s\S]*?JianyuTextPill\("连接信息已保存在本机"\)/);
  assert.match(components, /"服务：\$\{providerName \?: "名称待确认"\}"[\s\S]*?bodyMedium\.copy\(lineBreak = LineBreak\.Heading\)/);
  assert.match(components, /"模型"[\s\S]*?\bit,[\s\S]*?bodySmall\.copy\(lineBreak = LineBreak\.Heading\)/);
  assert.match(settings, /JianyuSavedConnection\(state\.aiProviderName, state\.aiModel \?: "名称待确认"\)/);
  assert.match(settings, /尚未设置 AI 服务/);
  assert.match(settings, /保存不代表服务可用/);
  assert.match(settings, /仅发虚构样例，不含家庭资料。/);
  assert.match(settings, /服务商可能收费。/);
  assert.match(settings, /只检查一次回复。/);
  assert.match(settings, /不评测入口质量或安全性。/);
  assert.match(today, /调用你设置的 AI 服务可能产生费用/);
  assert.match(settings, /collapsedLabel = "查看连接检查"/);
  assert.match(settings, /collapsedLabel = "查看信息边界"/);
  assert.match(today, /JianyuSavedConnection\(providerName\)/);
  assert.doesNotMatch(today, /JianyuSavedConnection\(providerName, model/);
  assert.match(today, /JianyuTextPill\("离线演示已选", tone = JianyuPillTone\.DISCOVERY\)/);
  assert.match(today, /你确认后才会尝试发送这次需要的内容/);
  assert.doesNotMatch(today, /连接信息已保存在本机；你确认后/);
  assert.doesNotMatch(today, /"\$providerName · \$model"/);
});

test("unfinished capabilities and onboarding use their shared visual semantics", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const app = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  const recovery = settings.slice(settings.indexOf('JianyuCardTitle("加密恢复包 · 开发预览")'));

  assert.match(settings, /JianyuCardTone\.PREVIEW[\s\S]*?JianyuCardTitle\("加密文件夹传输 · 开发预览"\)/);
  assert.match(settings, /JianyuCardTone\.PREVIEW[\s\S]*?JianyuCardTitle\("加密恢复包 · 开发预览"\)/);
  assert.ok(recovery.length > 0);
  assert.match(app, /tone = if \(emphasized\) JianyuCardTone\.DISCOVERY else JianyuCardTone\.NEUTRAL/);
  assert.match(app, /JianyuCardTitle\(title\)/);
  assert.match(app, /Text\(\s*mission,\s*style = MaterialTheme\.typography\.bodyLarge,\s*fontWeight = FontWeight\.Medium/);
  assert.match(app, /日后每次寻找，都由你决定提供哪些线索。现在不会发送家庭资料。/);
  assert.match(app, /Text\(if \(saving\) "正在保存…" else "保存家庭资料"\)/);
  assert.doesNotMatch(app, /开始看见机会/);
});

test("portable bundle replacement cannot reuse a stale preview after leaving Settings or selecting a new file", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const model = await readFile(path.join(uiRoot, "..", "MainViewModel.kt"), "utf8");
  assert.match(settings, /DisposableEffect\(Unit\) \{\s*onDispose \{ onCancelPortableImport\(\) \}/);
  assert.match(settings, /ActivityResultContracts\.OpenDocument\(\)[\s\S]*?if \(uri != null\) \{\s*onCancelPortableImport\(\)\s*pendingImportUri = uri/);
  assert.match(settings, /visualTransformation = PasswordVisualTransformation\(\)/);
  assert.match(settings, /PortableImportDialog\([\s\S]*?onPreview = onPreviewPortable,[\s\S]*?onConfirm = onConfirmPortableImport/);
  assert.match(model, /if \(repository\.load\(\) != original\) throw PortableImportStaleException\(\)/);
});

test("local-only device sharing is explained in the App rather than only in README", async () => {
  const app = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");

  assert.match(app, /两位家长需共用设备。\\n恢复包不会自动同步/);
  assert.match(settings, /两位家长若要看同一份资料，需要共用这台设备/);
  assert.match(settings, /局域网同步只在同一网络交换资料，异地不能及时同步/);
  assert.match(settings, /现在还不能自动让多台设备同步/);
});

test("unfinished data tools stay optional except for a configured or unreadable folder", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");

  assert.match(settings, /showAdvancedDataTools by remember\(state\.folderSyncConfigured, state\.folderSyncSettingsUnreadable\) \{\s*mutableStateOf\(state\.folderSyncConfigured \|\| state\.folderSyncSettingsUnreadable\)/);
  assert.match(settings, /JianyuCardTitle\("加密传输与恢复"\)/);
  assert.match(settings, /if \(showAdvancedDataTools\) \{[\s\S]*?JianyuCardTitle\("加密文件夹传输 · 开发预览"\)[\s\S]*?JianyuCardTitle\("加密恢复包 · 开发预览"\)/);
  assert.match(settings, /已选择文件夹：\$\{state\.folderSyncName\}/);
  assert.match(settings, /已选择文件夹；不会自动同步。\\n手动传输；恢复包另存。/);
  assert.match(settings, /先前的文件夹连接信息暂时无法读取；不会继续传输/);
  assert.match(settings, /重新选择文件夹不能找回原来的传输密钥/);
  assert.doesNotMatch(settings, /已连接：\$\{state\.folderSyncName\}/);
  assert.doesNotMatch(settings, /已设置加密文件夹|客户端加密传输链|已连接文件夹的本机传输密钥/);
});

test("recent selections are a visible restraint option, not a hidden engagement veto", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const labels = await readFile(path.join(uiRoot, "UiLabels.kt"), "utf8");
  const policy = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/OpportunityEngine.kt"), "utf8");

  assert.match(today, /set\.selected\.any \{ "recent-intervention-load" in it\.warnings \}/);
  assert.match(today, /近 7 天曾选过几个入口，但点选不代表真的参与/);
  assert.match(labels, /"recent-intervention-load" -> "近 7 天已选入口较多，可以考虑留白；点选不代表已经参与"/);
  const rejectRules = policy.slice(policy.indexOf("val reasons = buildList"), policy.indexOf("val warnings = buildList"));
  assert.doesNotMatch(rejectRules, /recent-intervention-load/);
});

test("recent selection count needs per-call history consent before AI disclosure", async () => {
  const viewModel = await readFile(path.join(uiRoot, "../MainViewModel.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const firewall = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/DefaultContextFirewall.kt"), "utf8");
  const prompt = await readFile(path.resolve("apps/jianyu-android/core/data/src/main/kotlin/org/jianyu/core/data/OpenAiCompatibleOpportunitySource.kt"), "utf8");

  assert.match(viewModel, /includeRecentSelectionCountInProviderContext = effectiveIncludeRecentContext/);
  assert.match(firewall, /takeIf \{ request\.includeRecentSelectionCountInProviderContext && it > 0 \}/);
  assert.match(prompt, /context\.recentInterventionCount\?\.let \{ put\("recentSelectedOptionCount7Days", it\) \}/);
  assert.match(today, /近 7 天已选入口次数，也会一并发送，但点选不代表实际参与/);
});

test("optional details use one disclosure control in discovery and settings", async () => {
  const components = await readFile(path.join(uiRoot, "JianyuComponents.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");

  assert.match(components, /internal fun JianyuDisclosureToggle\(/);
  assert.match(components, /stateDescription = if \(expanded\) "已展开" else "已收起"/);
  assert.match(today, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看这次的数据边界"/);
  assert.match(today, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看判断与来源"/);
  assert.match(settings, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看保存、加密与同步边界"/);
  assert.match(settings, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看开发预览功能"/);
  assert.match(family, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看可选学校信息"/);
  assert.match(family, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看隐私边界"/);
  assert.match(family, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看旧版演示记录"/);
  assert.match(family, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看本机操作记录"/);
  assert.match(lifecycle, /JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看阶段与权利"/);
});

test("destructive actions keep danger styling through their confirmation flows", async () => {
  const components = await readFile(path.join(uiRoot, "JianyuComponents.kt"), "utf8");
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");

  assert.match(components, /fun JianyuDangerButton\(/);
  assert.match(components, /fun JianyuDangerOutlinedButton\(/);
  assert.match(components, /fun JianyuDangerTextButton\(/);
  assert.match(components, /MaterialTheme\.colorScheme\.error/);
  assert.match(settings, /JianyuDangerOutlinedButton\(onClick = \{ confirmErase = true \}/);
  assert.match(settings, /JianyuDangerTextButton\(onClick = \{ confirmErase = false; onErase\(\) \}/);
  assert.match(today, /JianyuDangerButton\([\s\S]*?graduationDeleteConfirmed/);
  assert.match(today, /JianyuDangerOutlinedButton\([\s\S]*?GraduationRetentionMode\.RELATIONSHIP_ONLY/);
  assert.match(family, /JianyuDangerTextButton\(onClick = \{ showDelete = true \}/);
});

test("World information uses the same saved-versus-live source language as AI", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const worldCard = settings.slice(settings.indexOf('JianyuCardTitle("世界信息")'), settings.indexOf('title = "资料与设备"'));

  assert.match(worldCard, /JianyuSavedConnection\(state\.worldBriefProviderName\)/);
  assert.match(worldCard, /保存不代表服务可用/);
  assert.match(worldCard, /填写的地区可能原样发送；请勿填精确地址或个人信息/);
  assert.match(settings, /不会可靠地从你填写的文字中自动删去姓名或地址/);
  assert.doesNotMatch(worldCard, /它不会收到孩子的姓名/);
  assert.match(worldCard, /label = \{ Text\("显示名称"\) \}/);
  assert.match(worldCard, /"保存新连接" else "保存连接"/);
  assert.doesNotMatch(worldCard, /"连接服务"|"保存新服务"|"更换服务"|"当前：/);
  assert.match(today, /世界信息已设置：\$worldBriefProviderName/);
  assert.match(today, /已设置 \$worldBriefProviderName；这次不发送地区/);
  assert.match(today, /这次会发送你填写的地区/);
  assert.match(today, /这次会发送你填写的地区：\$\{region\.trim\(\)\}/);
  assert.match(today, /正式寻找时，地区可能原样发给 AI 和已设置的世界信息服务/);
  for (const label of ["学校情况（可选）", "家庭安排（可选）", "世界 · 地区（可选）"]) {
    assert.ok(today.includes(label), `${label} should remain short at the narrow large-font baseline`);
  }
  assert.doesNotMatch(today, /学校 · 近期正在学什么（可选）|生活 · 近期已有的安排（可选）|世界 · 粗略城市或地区（可选）/);
  assert.doesNotMatch(today, /只用于公共世界信息|region\.compactForPreview\(40\)|世界信息服务只接收粗略地区与公共类别/);
  assert.doesNotMatch(today, /\$worldBriefProviderName 已连接/);
  assert.match(viewModel, /世界信息的连接信息已保存在本机。正式寻找入口时才会尝试访问/);
  assert.match(viewModel, /地区如有填写，可能原样发送/);
  assert.doesNotMatch(viewModel, /世界信息服务已连接/);
});

test("routine forms use neutral surfaces and optional details stay secondary", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const composer = today.slice(today.indexOf("private fun OpportunityComposer("), today.indexOf("private fun ProviderStatus("));

  assert.match(settings, /JianyuCard\s*\{[\s\S]*?JianyuCardTitle\("AI 发现"\)/);
  assert.match(family, /JianyuCard\s*\{[\s\S]*?JianyuCardTitle\("新记录由谁署名"\)/);
  assert.match(composer, /JianyuDisclosureToggle\(\s*expanded = showContextDetails,\s*onClick = onToggleContext/);
  assert.doesNotMatch(today, /FilledTonalButton\(onClick = onToggleContext/);
  assert.match(composer, /JianyuCard\(contentPadding = PaddingValues\(18\.dp\)\)/);
  assert.doesNotMatch(composer, /tone = JianyuCardTone\.DISCOVERY/);
  assert.match(today, /JianyuCardTitle\("这次的现实条件"\)/);
});

test("product UI uses one plain-language name for school context and API credentials", async () => {
  const files = await kotlinFiles(uiRoot);
  for (const file of files) {
    const source = await readFile(file, "utf8");
    for (const phrase of ["学校信号", "学校窗口", "生活窗口", "API Key"]) {
      assert.ok(!source.includes(phrase), `${path.basename(file)} should not expose ${phrase}`);
    }
  }
});

test("persistent recorder and shared timeline labels do not claim a one-time or caregiver-only choice", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");

  assert.match(family, /JianyuCardTitle\("新记录由谁署名"\)/);
  assert.match(family, /JianyuSectionHeader\("选择与拒绝"\)/);
  assert.match(family, /记录署名：\$authorLabel/);
  assert.match(family, /JianyuSupportingText\(formatInstant\(choice\.chosenAt\)\)/);
  assert.doesNotMatch(family, /记录署名：\$authorLabel · \$\{formatInstant/);
  assert.doesNotMatch(family, /JianyuSectionHeader\("家庭选择"\)/);
  assert.doesNotMatch(settings, /完成本次发现/);
});

test("Today keeps the current child visible without filling the first screen with every sibling", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");
  const selector = today.slice(today.indexOf("if (family.children.size > 1) {"), today.indexOf("acknowledgedChoice?.let"));

  assert.match(selector, /当前查看：\$\{selectedChild\.displayName\}/);
  assert.match(selector, /Text\(if \(showChildSelector\) "收起名单" else subjectSwitchLabel\(stage\)\)/);
  assert.match(lifecycle, /LifecycleStage\.HAND_OVER, LifecycleStage\.GRADUATION -> "切换查看人"/);
  assert.match(selector, /if \(showChildSelector\) \{[\s\S]*?family\.children\.forEach/);
  assert.match(selector, /else viewModel\.selectChild\(child\.id\)/);
});

test("unconnected source shows whether offline demonstration is selected without presenting it as AI", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");
  const source = today.slice(today.indexOf("private fun ProviderStatus("), today.indexOf("private fun RequestContextPreview("));

  assert.match(source, /if \(!configured\) \{[\s\S]*?if \(useOfflineDemo\) "离线演示已选" else "AI 服务尚未设置"/);
  assert.match(source, /这次只在本机查看固定模板，不调用 AI，也不保存演示输入或点选。/);
  assert.match(source, /Text\(if \(useOfflineDemo\) "退出离线演示" else "选择离线演示"\)/);
  assert.match(source, /if \(useOfflineDemo\) \{\s*OutlinedButton\(onClick = onOpenSettings\) \{ Text\(setupCopy\.actionLabel\) \}/);
  assert.match(source, /else \{\s*Button\(onClick = onOpenSettings\) \{ Text\(setupCopy\.actionLabel\) \}/);
  assert.match(lifecycle, /actionLabel = "请家长设置 AI"/);
  assert.match(lifecycle, /这台共享设备需由家长连接 AI/);
  assert.match(today, /if \(useOfflineDemo\) "我知道这只是本机演示，不发送或保存输入；由我决定是否继续预览。"/);
  assert.match(today, /onUseDemo = \{\s*useOfflineDemo = true\s*invalidateApprovals\(\)/);
  assert.doesNotMatch(source, /JianyuCardTitle\("还没有连接 AI"\)/);
});

test("demo page description follows the selected source and lifecycle audience", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");
  const handOver = lifecycle.slice(
    lifecycle.indexOf("LifecycleStage.HAND_OVER -> OpportunityComposerUiModel("),
    lifecycle.indexOf("LifecycleStage.GRADUATION -> OpportunityComposerUiModel("),
  );

  assert.match(today, /useOfflineDemo -> composerCopy\.demoDescription\s*else -> composerCopy\.description/);
  assert.match(handOver, /demoDescription = "你可以写一句想探索的事，先看本机固定模板；输入和点选都不发送，也不留进长期足迹。"/);
  assert.doesNotMatch(handOver, /demoDescription = "[^"]*决定是否发送/);
  for (const stage of ["CO_PLAY", "ACCOMPANY", "CO_SELECT", "HAND_OVER"]) {
    const start = lifecycle.indexOf(`LifecycleStage.${stage} -> OpportunityComposerUiModel(`);
    assert.notEqual(start, -1, `${stage} needs a composer model`);
    const nextStage = lifecycle.indexOf("LifecycleStage.", start + 1);
    const fragment = lifecycle.slice(start, nextStage === -1 ? undefined : nextStage);
    assert.match(fragment, /demoDescription = "[^"]*不发送、不保存|demoDescription = "[^"]*都不发送，也不留进长期足迹/);
  }
});

test("stage-specific Today headlines stay concise at the narrow large-font baseline", async () => {
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");

  assert.match(lifecycle, /title = "从一句话开始，一起玩"/);
  assert.match(lifecycle, /inputLabel = "最近反复玩或说起什么？"/);
  assert.match(lifecycle, /title = "一起比较，再一起选择"/);
  assert.match(lifecycle, /title = "找不找入口，由你决定"/);
});

test("co-play does not expose a scored assessment or school-curriculum prompt", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const viewModel = await readFile(
    path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"),
    "utf8",
  );

  assert.match(family, /assessmentChildren\s*=\s*family\.children\.filter\s*\{\s*canRecordScoredAssessment\(lifecycleStage\(it\)\)\s*\}/);
  assert.match(viewModel, /if\s*\(!canRecordScoredAssessment\(stage\)\)/);
  assert.match(today, /stage\s*==\s*LifecycleStage\.CO_PLAY[\s\S]*?老师观察（可选）/);
});

test("school assessment form explains invalid input before enabling save", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const form = family.slice(family.indexOf("private fun AssessmentEntryForm("), family.indexOf("internal fun TimelineScreen("));

  assert.match(form, /validationMessage = if \(requiredComplete\) assessmentDraftValidationMessage\(child, draft\) else null/);
  assert.match(form, /canSave = requiredComplete && validationMessage == null/);
  assert.match(form, /validationMessage\?\.let \{ message ->[\s\S]*?color = MaterialTheme\.colorScheme\.error/);
  assert.match(form, /Button\(\s*onClick = \{ onSave\(draft\) \},\s*enabled = canSave/);
});

test("family forms share add/save language and wait for durable local save", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const app = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  const viewModel = await readFile(
    path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"),
    "utf8",
  );
  const saveFamilyForm = viewModel.slice(viewModel.indexOf("    private fun saveFamilyForm("), viewModel.indexOf("    private fun mutate("));
  const mutate = viewModel.slice(viewModel.indexOf("    private fun mutate("), viewModel.indexOf("    private fun createFamily("));

  assert.match(family, /LaunchedEffect\(state\.familyFormSaveRevision, state\.lastSavedFamilyForm, pendingFormSave, pendingFromRevision\)/);
  assert.match(family, /state\.familyFormSaveRevision > pendingFromRevision && state\.lastSavedFamilyForm == pendingFormSave/);
  assert.match(family, /if \(viewModel\.addChild\(name, birthDate\)\) \{[\s\S]*?pendingFormSave = FamilyFormKind\.CHILD/);
  assert.match(family, /if \(viewModel\.addCaregiver\(caregiverName\)\) \{[\s\S]*?pendingFormSave = FamilyFormKind\.CAREGIVER/);
  assert.match(family, /if \(started\) \{[\s\S]*?pendingFormSave = FamilyFormKind\.ASSESSMENT/);
  assert.match(family, /enabled = !familyFormBusy/);
  assert.match(viewModel, /val authorId = family\.resolveCaregiverAuthor\(mutableState\.value\.activeMemberId\)\.id[\s\S]*?val author = current\.resolveCaregiverAuthor\(authorId\)/);
  assert.match(app, /JianyuNavigation\([\s\S]*?state\.familyFormSaving != null \|\| state\.sourceFormSaving != null \|\| state\.feedbackSavingChoiceId != null \|\|[\s\S]*?state\.portableImportChecking \|\| state\.portableImportSaving,[\s\S]*?viewModel::navigate/);
  assert.match(app, /icon = \{ androidx\.compose\.material3\.Icon\(icon, contentDescription = null\) \}/);
  assert.match(app, /NavigationBarItem\(\s*selected = current == route,\s*enabled = !formBusy/);
  assert.match(family, /Text\(if \(saving\) "正在保存…" else "添加孩子"\)/);
  assert.match(family, /Text\(if \(saving\) "正在保存…" else "添加家长或监护人"\)/);
  assert.match(family, /家长和监护人当前都按家长角色记录；这里只决定新记录的署名，不验证身份。/);
  assert.doesNotMatch(family, /成员权限体系仍会继续扩展/);
  assert.match(family, /Text\(if \(saving\) "正在保存…" else "保存这次学校记录"\)/);
  assert.match(saveFamilyForm, /familyFormSubmissionInFlight\.compareAndSet\(false, true\)/);
  assert.match(saveFamilyForm, /onSaved = \{ next ->[\s\S]*?familyFormSaveRevision = state\.familyFormSaveRevision \+ 1/);
  assert.match(mutate, /repository\.save\(next\)[\s\S]*?\.onSuccess \{ next ->[\s\S]*?onSaved\(next\)/);
  assert.doesNotMatch(family, /\.also \{ saved -> if \(saved\)/);
});

test("calendar selection looks like a form field, not a pill-shaped action", async () => {
  const dates = await readFile(path.join(uiRoot, "BirthdayField.kt"), "utf8");
  assert.match(dates, /OutlinedButton\([\s\S]*?shape = MaterialTheme\.shapes\.extraSmall/);
  assert.match(dates, /enabled = enabled/);
});

test("AI and world connection forms wait for encrypted settings to save before closing", async () => {
  const settings = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const saveSourceForm = viewModel.slice(viewModel.indexOf("    private fun saveSourceForm("), viewModel.indexOf("    private fun saveFamilyForm("));

  assert.match(settings, /LaunchedEffect\(state\.sourceFormSaveRevision, state\.lastSavedSourceForm, pendingSourceSave, pendingSourceFromRevision\)/);
  assert.match(settings, /state\.sourceFormSaveRevision > pendingSourceFromRevision && state\.lastSavedSourceForm == pendingSourceSave/);
  assert.match(settings, /SourceFormKind\.AI -> \{\s*showAiForm = false\s*apiKey = ""/);
  assert.match(settings, /SourceFormKind\.WORLD -> \{\s*showWorldBriefForm = false\s*worldBriefApiKey = ""/);
  assert.match(settings, /if \(onSaveAi\(providerName, baseUrl, model, apiKey\)\) \{[\s\S]*?pendingSourceSave = SourceFormKind\.AI/);
  assert.match(settings, /if \(onSaveWorldBrief\(worldBriefName, worldBriefEndpoint, worldBriefApiKey\)\) \{[\s\S]*?pendingSourceSave = SourceFormKind\.WORLD/);
  assert.match(settings, /enabled = !sourceFormBusy/);
  assert.match(settings, /OutlinedButton\(onClick = \{ confirmErase = true \}, enabled = !sourceFormBusy\)/);
  assert.match(settings, /SourceFormKind\.AI\) "正在保存…"/);
  assert.match(settings, /SourceFormKind\.WORLD\) "正在保存…"/);
  assert.match(saveSourceForm, /sourceFormSubmissionInFlight\.compareAndSet\(false, true\)/);
  assert.match(saveSourceForm, /runCatching\(write\)[\s\S]*?\.onSuccess \{[\s\S]*?sourceFormSaveRevision = state\.sourceFormSaveRevision \+ 1/);
  assert.match(saveSourceForm, /\.onFailure \{ error ->[\s\S]*?sourceFormError = kind[\s\S]*?ErrorContext\.LOCAL_SAVE/);
  assert.match(settings, /if \(state\.sourceFormError == SourceFormKind\.AI\) \{[\s\S]*?JianyuInset\(tone = JianyuCardTone\.HUMAN\)/);
  assert.match(settings, /if \(state\.sourceFormError == SourceFormKind\.WORLD\) \{[\s\S]*?JianyuInset\(tone = JianyuCardTone\.HUMAN\)/);
  assert.match(viewModel, /onFailure = ::reportLocalSave/);
  assert.match(viewModel, /fun eraseVault\(\) \{\s*if \(sourceFormSubmissionInFlight\.get\(\)\)/);
});

test("family keeps lifecycle boundaries readable without repeating an expanded guide for every child", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");

  assert.match(family, /expandedChildId\s+by\s+remember\(family\.household\.id\)\s*\{\s*mutableStateOf<String\?>\(null\)\s*\}/);
  assert.match(family, /expandedChildId\s*=\s*if\s*\(expandedChildId\s*==\s*child\.id\)\s*null\s*else\s*child\.id/);
  assert.match(lifecycle, /tone\s*=\s*JianyuCardTone\.NEUTRAL/);
  assert.match(lifecycle, /if\s*\(selected\)\s*JianyuTextPill\(/);
  assert.match(lifecycle, /LifecycleBoundaryRow\("现在谁决定", copy\.decision\)/);
  assert.match(lifecycle, /if\s*\(expanded\)\s*\{\s*LifecycleStageStrip\(stage\)/);
  assert.match(lifecycle, /LifecycleBoundaryRow\("权利与边界", copy\.childRight\)/);
  const boundary = lifecycle.slice(lifecycle.indexOf("private fun LifecycleBoundaryRow("));
  assert.match(boundary, /Column\([\s\S]*?Modifier\.fillMaxWidth\(\)/);
  assert.doesNotMatch(boundary, /\bRow\(|Modifier\.weight\(1f\)/);
  assert.match(lifecycle, /copy\.unfinishedBoundary\?\.let/);
  assert.match(family, /title\s*=\s*"阶段与决定权"/);
  assert.ok(family.indexOf('title = "阶段与决定权"') < family.indexOf('JianyuCardTitle("新记录由谁署名")'));
});

test("shared timeline keeps its key-holder warning visible while technical privacy details expand", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const notice = family.slice(family.indexOf("if (timeline.hasRestrictedRecords)"), family.indexOf("if (timeline.choices.isEmpty())"));

  assert.match(notice, /共享足迹不显示部分记录/);
  assert.match(notice, /持有家庭密钥或恢复包的人仍可能读取/);
  assert.match(notice, /JianyuDisclosureToggle\([\s\S]*?onClick = \{ showPrivacyBoundary = !showPrivacyBoundary \}/);
  assert.match(notice, /if \(showPrivacyBoundary\) \{/);
});

test("the family timeline does not turn record volume into a progress number", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  for (const collection of ["choices", "evidence", "hypotheses"]) {
    assert.ok(
      !family.includes(`\${timeline.${collection}.size}`),
      `${collection} volume should not headline the child's timeline`,
    );
  }
  assert.doesNotMatch(family, /查看旧版演示记录（\$\{/);
  assert.doesNotMatch(family, /查看本机操作记录（\$\{/);
});

test("the timeline distinguishes a saved choice from an activity that happened", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const labels = await readFile(path.join(uiRoot, "UiLabels.kt"), "utf8");
  assert.match(family, /回看选择和线索，不打卡、不评分/);
  assert.match(family, /点选不代表已参与/);
  assert.match(family, /后来的看法（\$sourceLabel）：/);
  assert.match(labels, /"opportunity\.reflected", "opportunity\.feedback-recorded" -> "补充一次后来的看法"/);
  assert.match(family, /相似入口仍可能出现/);
  assert.doesNotMatch(family + labels, /回看真正发生的事|真实反馈|可帮助它修正相近入口/);
});

test("timeline feedback preserves who signed the child's view", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const app = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  assert.match(app, /onCaregiverFeedback = viewModel::feedback/);
  assert.match(app, /onChildFeedback = viewModel::feedbackFromChild/);
  assert.match(family, /onFeedback = if \(stage == LifecycleStage\.HAND_OVER\) onChildFeedback else onCaregiverFeedback/);
  assert.match(family, /FeedbackProvenance\.UNKNOWN -> "旧记录，来源未确认"/);
  assert.match(family, /来源未确认的旧看法不会进入下一次 AI 摘要/);
  assert.match(viewModel, /fun feedbackFromChild\(choiceId: String, value: String\) = recordFeedback\(choiceId, value, childAuthored = true\)/);
  assert.match(viewModel, /appendChoiceFeedback\(/);
});

test("one feedback choice stays disabled until its local save completes", async () => {
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const timeline = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const record = viewModel.slice(viewModel.indexOf("    private fun recordFeedback("), viewModel.indexOf("    fun dismissChoiceAcknowledgement("));
  assert.match(record, /feedbackSubmissionInFlight\.compareAndSet\(false, true\)/);
  assert.match(record, /val preferredAuthorId = mutableState\.value\.activeMemberId/);
  assert.match(record, /feedbackSavingChoiceId = choiceId/);
  assert.match(record, /onFinished = \{[\s\S]*?feedbackSavingChoiceId = null/);
  assert.match(record, /onFailure = ::reportLocalSave/);
  assert.match(today, /if \(busy\) Text\("正在保存看法…"/);
  assert.match(timeline, /enabled = !feedbackBusy/);
  assert.match(timeline, /if \(savingThisChoice\) Text\("正在保存看法…"/);
  assert.match(viewModel, /fun navigate\(route: AppRoute\) \{[\s\S]*?if \(mutableState\.value\.portableImportChecking \|\| mutableState\.value\.portableImportSaving\) return[\s\S]*?if \(it\.feedbackSavingChoiceId != null \|\| it\.choiceDeletingId != null \|\| it\.portableImportChecking \|\| it\.portableImportSaving\)/);
  assert.match(viewModel, /fun selectChild\(childId: String\) \{\s*if \(discoverySubmissionInFlight\.get\(\) \|\| choiceSubmissionInFlight\.get\(\)\) return\s*mutableState\.update \{ it\.withSelectedChild\(childId\) \}/);
  assert.match(viewModel, /internal fun MainUiState\.withSelectedChild\(childId: String\)[\s\S]*?feedbackSavingChoiceId != null \|\| choiceDeletingId != null \|\| disclosureSaveRisk/);
  assert.match(today, /val canSwitchViewedChild = !state\.discovering && !state\.choiceSaving &&\s*state\.feedbackSavingChoiceId == null && state\.choiceDeletingId == null && !state\.disclosureSaveRisk/);
});

test("the shared timeline does not bury real evidence under a missing-choice empty state", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const emptyState = await readFile(path.join(uiRoot, "SettingsScreen.kt"), "utf8");
  assert.match(family, /val hasVisibleRealContent = realChoices\.isNotEmpty\(\) \|\| realEvidence\.isNotEmpty\(\) \|\| realHypotheses\.isNotEmpty\(\)/);
  assert.match(family, /if \(!hasVisibleRealContent && !timeline\.hasRestrictedRecords\) \{\s*item \{\s*EmptyState\(/);
  assert.match(family, /family\.children\.any \{ lifecycleStage\(it\) != LifecycleStage\.GRADUATION \}/);
  assert.match(family, /if \(timeline\.events\.isNotEmpty\(\)\) "还没有留下选择或线索" else "这里还没有家庭足迹"/);
  assert.match(family, /本机操作记录可在下方查看。/);
  assert.match(family, /不必专门留下记录。\\n孩子有兴趣时，再来看看。/);
  assert.match(family, /不必专门留下记录。\\n资料由本人决定去留。/);
  assert.doesNotMatch(family, /孩子下次主动提起什么时，想用再回来/);
  assert.doesNotMatch(family, /if \(realChoices\.isEmpty\(\)\) \{\s*item \{ EmptyState\(/);
  assert.match(emptyState, /internal fun EmptyState\([\s\S]*?Text\(body, color = MaterialTheme\.colorScheme\.onSurfaceVariant, textAlign = TextAlign\.Center\)/);
});

test("Today mode and viewed-person changes reset the scroll position before the next decision", async () => {
  const source = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");

  assert.match(source, /val\s+listState\s*=\s*rememberLazyListState\(\)/);
  assert.match(source, /LaunchedEffect\(selectedChild\.id, state\.opportunities\)[\s\S]*?listState\.scrollToItem\(0\)/);
  assert.match(source, /LazyColumn\(\s*state\s*=\s*listState/);
});

test("Today keeps the detailed data receipt behind progressive disclosure", async () => {
  const source = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");

  assert.match(source, /showResultDataBoundary\s+by\s+remember\(state\.sourceEventId\)/);
  assert.match(source, /showDataBoundary = showResultDataBoundary/);
  assert.match(source, /"查看这次的数据边界"/);
  assert.match(source, /if\s*\(showDataBoundary\)\s*\{/);
  assert.match(source, /if\s*\(isOfflineDemo\)\s*"这次本机可使用的线索"/);
  assert.match(source, /"这次 AI 服务可接收的线索"/);
});

test("result cards translate source kinds and do not call a local demo AI", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  assert.match(today, /sourceDisplayLabel\(item\.sourceKind, item\.sourceTitle\)/);
  assert.doesNotMatch(today, /item\.sourceTitle\s*\?:\s*item\.sourceKind/);
  assert.match(today, /if \(useOfflineDemo\) "正在整理离线演示…"/);
  assert.match(today, /"固定模板只替换关键词，本机按现实限制筛选；输入和点选不保存。"/);
  assert.match(today, /if \(previewOnly\) "演示 · \$\{item\.ecosystem\.asEcosystemLabel\(\)\}" else "入口 · \$\{item\.ecosystem\.asEcosystemLabel\(\)\}"/);
  assert.doesNotMatch(today, /doorNumber|RouteMapPill|第 \$doorNumber 扇门/);
});

test("the route overview names choices and cards keep safety facts visible", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const overview = today.slice(today.indexOf("private fun OpportunityRouteMap("), today.indexOf("private fun DiscoverySourceIssuesCard("));
  const opportunity = today.slice(today.indexOf("private fun OpportunityCard("), today.indexOf("private fun GateSummary("));
  const nothing = today.slice(today.indexOf("private fun NothingCard("), today.indexOf("private fun CostBand.asCostLabel("));

  assert.match(overview, /RouteOverviewEntry\(\s*label = item\.ecosystem\.asEcosystemLabel\(\),\s*title = item\.title/);
  assert.match(overview, /RouteOverviewEntry\(label = "留白", title = nothing\.title/);
  assert.match(today, /if \(set\.selected\.size > 1\)\s*\{\s*item \{\s*OpportunityRouteMap\(/);
  assert.match(overview, /private fun RouteOverviewEntry[\s\S]*?Column\(Modifier\.fillMaxWidth\(\)[\s\S]*?JianyuTextPill\(text = label, tone = tone\)[\s\S]*?Text\(title, style = MaterialTheme\.typography\.bodyMedium\)/);
  assert.doesNotMatch(overview, /Modifier\.weight\(1f\)/);
  assert.match(today, /这次有 \$count 个入口，也可以留白。/);
  assert.match(today, /count == 1\) "这次有一个入口，也可以留白。"/);
  assert.doesNotMatch(overview, /opportunities\.size == 1/);
  assert.doesNotMatch(today, /\$count 个入口；留白也在其中|这次看见一个入口；也可以选择留白/);
  assert.match(opportunity, /Text\(\s*item\.explanation,\s*maxLines = if \(showFullExplanation\) Int\.MAX_VALUE else 3/);
  assert.match(opportunity, /if \(explanationOverflows\) \{\s*JianyuDisclosureToggle\([\s\S]*?collapsedLabel = "查看完整说明"[\s\S]*?expandedLabel = "收起说明"/);
  assert.ok(opportunity.indexOf("sourceDisplayLabel(item.sourceKind, item.sourceTitle)") < opportunity.indexOf("if (showDecisionDetails)"));
  assert.match(opportunity, /Text\(\s*item\.verification\.asVerificationLabel\(\)/);
  assert.match(opportunity, /Text\(\s*sourceDisplayLabel\(item\.sourceKind, item\.sourceTitle\)/);
  assert.doesNotMatch(opportunity, /\$\{item\.verification\.asVerificationLabel\(\)\} · \$\{sourceDisplayLabel/);
  assert.ok(opportunity.indexOf("if (item.bookingRequired)") < opportunity.indexOf("if (showDecisionDetails)"));
  assert.ok(opportunity.indexOf("if (!item.sponsorship.isNullOrBlank() || item.trackingWarning)") < opportunity.indexOf("if (showDecisionDetails)"));
  assert.ok(opportunity.indexOf("Text(item.whyNow") > opportunity.indexOf("if (showDecisionDetails)"));
  assert.doesNotMatch(nothing, /JianyuTextPill\("0 分钟"\)/);
});

test("a child veto is confirmed before becoming durable feedback", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const opportunity = today.slice(today.indexOf("private fun OpportunityCard("), today.indexOf("private fun GateSummary("));

  assert.match(opportunity, /OutlinedButton\(onClick = \{ confirmChildVeto = true \}/);
  assert.match(opportunity, /if \(confirmChildVeto\) \{\s*AlertDialog\(/);
  assert.match(opportunity, /Button\(onClick = \{ confirmChildVeto = false; choose\(item, true\) \}, enabled = !busy\)/);
  assert.match(opportunity, /只有孩子明确拒绝时才记为孩子的意见/);
  assert.doesNotMatch(opportunity, /OutlinedButton\(onClick = \{ choose\(item, true\) \}/);
});

test("hand-over acknowledgements keep addressing the young person after their decision", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const acknowledgement = today.slice(today.indexOf("private fun ChoiceAcknowledgementCard("), today.indexOf("private fun OpportunityComposer("));

  assert.match(today, /ChoiceAcknowledgementCard\(\s*choice = choice,\s*stage = stage,/);
  assert.match(acknowledgement, /stage: LifecycleStage/);
  assert.match(acknowledgement, /LifecycleStage\.HAND_OVER\) "你说不想要——这也是答案"/);
  assert.match(acknowledgement, /LifecycleStage\.HAND_OVER\) "你选择这次留白"/);
  assert.match(acknowledgement, /LifecycleStage\.HAND_OVER\) "你选了这个入口"/);
  assert.match(acknowledgement, /有了明确的看法再说。点选不代表已经参与/);
  assert.match(acknowledgement, /等孩子表达了看法，再由家长代记/);
  assert.match(acknowledgement, /JianyuCardTitle\(/);
  assert.match(acknowledgement, /FlowRow\(/);
  assert.match(acknowledgement, /我想留个看法（可选）/);
  assert.match(acknowledgement, /"nothing" -> nothingChoiceAcknowledgement\(stage\)/);
  assert.match(acknowledgement, /LifecycleStage\.HAND_OVER\) \{\s*"这次不安排也可以。等你想继续探索时再说。"/);
  assert.match(acknowledgement, /"这次不安排也可以。等孩子有新想法时再说。"/);
  assert.doesNotMatch(acknowledgement, /没有错过任务|没有落下进度/);
});

test("empty interest input uses an ordinary hint and only shows the limit near the cap", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const composer = today.slice(today.indexOf("private fun OpportunityComposer("), today.indexOf("private fun ProviderStatus("));

  assert.match(composer, /interestSupportingText\(expression\.length\)/);
  assert.match(today, /length >= 700/);
  assert.match(today, /有线索时写一句就够了/);
  assert.match(today, /快到字数上限/);
  assert.match(composer, /minLines = 1/);
  assert.match(composer, /testTag\("interest-input"\)[\s\S]*?ProviderStatus\([\s\S]*?Text\("语音转文字"\)/);
  assert.doesNotMatch(composer, /0\/800 · 想起时记一句/);
  assert.doesNotMatch(composer, /语音输入（先成草稿）/);
});

test("discovery sources do not switch the hand-over result back to caregiver copy", async () => {
  const demo = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/OpportunityEngine.kt"), "utf8");
  const provider = await readFile(path.resolve("apps/jianyu-android/core/data/src/main/kotlin/org/jianyu/core/data/OpenAiCompatibleOpportunitySource.kt"), "utf8");

  assert.match(demo, /顺着「\$subject」继续看看/);
  assert.doesNotMatch(demo, /跟着孩子继续看看/);
  assert.match(provider, /LifecycleStage\.HAND_OVER ->/);
  assert.match(provider, /title、explanation、whyNow 会直接展示给孩子本人/);
  assert.match(provider, /用自然的第二人称‘你’表达/);
  assert.match(provider, /LifecycleStage\.CO_SELECT ->/);
  assert.match(provider, /title、explanation、whyNow 会由孩子和家长一起阅读/);
});

test("the hand-over page keeps one addressee from header through retention and result", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");
  const labels = await readFile(path.join(uiRoot, "UiLabels.kt"), "utf8");

  const handOverComposer = lifecycle.slice(lifecycle.indexOf("LifecycleStage.HAND_OVER -> OpportunityComposerUiModel("), lifecycle.indexOf("LifecycleStage.GRADUATION -> OpportunityComposerUiModel("));
  assert.match(handOverComposer, /stageLabel = "放权 · 你来决定"/);
  assert.match(handOverComposer, /description = "是否发送、是否保存，由你决定。"/);
  assert.doesNotMatch(handOverComposer, /\$childName/);
  assert.match(today, /这一步请由你本人参与/);
  assert.match(today, /这句话以后要不要保留/);
  assert.match(today, /如果选择保存，这句话会以你本人署名/);
  assert.match(today, /item\.primaryGoal\.asGoalLabel\(stage\)/);
  assert.match(labels, /LifecycleStage\.HAND_OVER\) "主要回应你想做的事"/);
});

test("handover retention does not promise child-only secrecy before independent keys exist", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const labels = await readFile(path.join(uiRoot, "UiLabels.kt"), "utf8");
  const retention = today.slice(today.indexOf("private fun PersistenceChoice("), today.indexOf("private fun GoalCard("));

  assert.match(retention, /label = "保存，不在共享足迹显示"/);
  assert.match(retention, /label = "保存到共享足迹"/);
  assert.match(retention, /持有家庭密钥或恢复包的人仍可能读取/);
  assert.doesNotMatch(retention, /只留给自己/);
  assert.match(today, /这次描述已保存在本机，但不在共享足迹显示/);
  assert.match(labels, /EvidenceVisibility\.CHILD_PRIVATE -> "不在共享足迹显示"/);
});

test("co-select shares caregiver-authored choices while hand-over keeps child authorship", async () => {
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const feedbackDomain = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/FeedbackProvenance.kt"), "utf8");
  const choose = viewModel.slice(viewModel.indexOf("    fun choose("), viewModel.indexOf("    fun feedback("));
  const feedback = viewModel.slice(viewModel.indexOf("    fun feedback("), viewModel.indexOf("    fun dismissChoiceAcknowledgement("));

  assert.match(choose, /current\.resolveDiscoveryAuthor\(child, stage, mutableState\.value\.activeMemberId\)/);
  assert.match(choose, /author = author/);
  assert.match(choose, /stage == LifecycleStage\.CO_SELECT \|\| stage == LifecycleStage\.HAND_OVER\)[\s\S]*?"shared-with-child"/);
  assert.match(choose, /onSaved = \{ next ->[\s\S]*?choiceAcknowledgementId = choiceId/);
  assert.match(today, /onFeedback = if \(stage == LifecycleStage\.HAND_OVER\) viewModel::feedbackFromChild else viewModel::feedback/);
  assert.match(feedback, /fun feedbackFromChild\(choiceId: String, value: String\) = recordFeedback\(choiceId, value, childAuthored = true\)/);
  assert.match(feedback, /family\.resolveDiscoveryAuthor\(child, LifecycleStage\.HAND_OVER/);
  assert.match(feedback, /appendChoiceFeedback\(/);
  assert.match(feedbackDomain, /val restrictedSource = choice\.hasRestrictedLinkedEvent\(family\.events\)/);
  assert.match(feedbackDomain, /visibility = if \(restrictedSource\) \{\s*"child-private"\s*\} else if \(stage == LifecycleStage\.CO_SELECT \|\| provenance == FeedbackProvenance\.CHILD_SIGNED\)[\s\S]*?"shared-with-child"/);
});

test("one discovery accepts only one durable choice while save is in flight", async () => {
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const choose = viewModel.slice(viewModel.indexOf("    fun choose("), viewModel.indexOf("    fun feedback("));
  const opportunity = today.slice(today.indexOf("private fun OpportunityCard("), today.indexOf("private fun GateSummary("));
  const nothing = today.slice(today.indexOf("private fun NothingCard("), today.indexOf("private fun CostBand.asCostLabel("));

  assert.match(viewModel, /private val choiceSubmissionInFlight = AtomicBoolean\(false\)/);
  assert.match(choose, /choiceSubmissionInFlight\.compareAndSet\(false, true\)/);
  assert.match(choose, /current\.choices\.none \{ it\.sourceEventId == sourceEventId \}/);
  assert.match(choose, /choiceSaving = true/);
  assert.match(choose, /onFinished = \{[\s\S]*?choiceSubmissionInFlight\.set\(false\)[\s\S]*?choiceSaving = false/);
  assert.match(opportunity, /enabled = !busy/);
  assert.match(nothing, /Button\(onClick = onChoose, enabled = !busy/);
  assert.match(today, /enabled = !state\.choiceSaving,[\s\S]*?Text\(if \(state\.discoverySourceIssues\.isNotEmpty\(\)\) "调整后再试" else "换个线索"\)/);
});

test("failed discovery writes cannot appear as saved family context", async () => {
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const checkpoint = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/ConfirmedVaultSnapshot.kt"), "utf8");
  const discover = viewModel.slice(viewModel.indexOf("    fun discover("), viewModel.indexOf("    fun choose("));

  assert.match(discover, /val confirmedVault = ConfirmedVaultSnapshot\(family, repository\)/);
  assert.match(discover, /if \(effectivePersistContext\) confirmedVault\.save\(nextFamily\)/);
  assert.match(discover, /if \(disclosureEvents\.isNotEmpty\(\)\) confirmedVault\.save\(familyWithReceipt\)/);
  assert.match(discover, /family = confirmedVault\.family, discovering = false/);
  assert.match(discover, /if \(confirmedVault\.saveFailed && externalRequestMayHaveStarted\) \{[\s\S]*?postRequestLocalSaveMessage\(effectivePersistContext\)/);
  assert.match(discover, /else if \(confirmedVault\.saveFailed\) \{\s*reportLocalSave\(error\)/);
  assert.match(discover, /author = author,\s*visibility = disclosureVisibility/);
  assert.doesNotMatch(discover, /family = nextFamily, discovering = false/);
  assert.match(checkpoint, /repository\.save\(next\)[\s\S]*?family = next/);
});

test("formal discovery saves an approval boundary before calling external sources", async () => {
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const engine = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/OpportunityEngine.kt"), "utf8");
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const labels = await readFile(path.join(uiRoot, "UiLabels.kt"), "utf8");
  const discover = viewModel.slice(viewModel.indexOf("    fun discover("), viewModel.indexOf("    fun choose("));

  const approvalWrite = discover.indexOf("confirmedVault.save(approvedFamily)");
  const externalCall = discover.indexOf("val result = engine.discover(");
  assert.ok(approvalWrite > 0 && externalCall > approvalWrite);
  assert.match(discover, /"provider\.disclosure-approved"/);
  assert.match(discover, /"deliveryStatus" to "not-confirmed"/);
  assert.match(discover, /approvedCategoriesBySource = approvedSourceScopes/);
  assert.match(engine, /"outside-approved-scope"/);
  assert.match(today, /确认这次可发给 \$providerName 的信息/);
  assert.match(today, /已设置的世界信息服务也可能收到你填写的地区/);
  assert.match(labels, /"provider\.disclosure-approved" -> "请求前确认的信息范围"/);
  assert.match(labels, /"provider\.context-disclosed" -> "请求后记录的信息范围"/);
});

test("Timeline audit rows reuse the neutral inset and translated disclosure details", async () => {
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const row = family.slice(family.indexOf("private fun EventRow("));
  assert.match(row, /JianyuInset \{/);
  assert.match(row, /event\.disclosureAuditLines\(\)\.forEach/);
  assert.doesNotMatch(row, /Text\("•"/);
});

test("possible post-request disclosure is a persistent Today warning, not a vanishing snackbar", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const app = await readFile(path.join(uiRoot, "JianyuApp.kt"), "utf8");
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");

  assert.match(today, /if \(state\.disclosureSaveRisk && state\.error != null\) \{[\s\S]*?DisclosureSaveRiskCard\(/);
  assert.match(today, /JianyuCard\(tone = JianyuCardTone\.HUMAN\)[\s\S]*?JianyuCardTitle\("这次请求可能已送出"\)/);
  assert.match(app, /if \(!state\.disclosureSaveRisk\) state\.error\?\.let \{ snackbar\.showSnackbar\(it\) \}/);
  assert.match(viewModel, /fun dismissDisclosureSaveRisk\(\)/);
});

test("hand-over consent is per request and follows the final disclosure choices", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const lifecycle = await readFile(path.join(uiRoot, "LifecycleUi.kt"), "utf8");
  const form = today.slice(today.indexOf("if (!belowMinimumAge && authority.allowsNewObservation &&"), today.indexOf("demoPreview?.let"));
  const ribbon = today.slice(today.indexOf("private fun TodayContextRibbon("), today.indexOf("private fun ChoiceAcknowledgementCard("));

  assert.match(today, /fun invalidateApprovals\(\) \{\s*childConfirmed = false\s*disclosureApproved = false\s*\}/);
  assert.ok(form.indexOf("TodayContextRibbon(") < form.indexOf("PersistenceChoice("));
  assert.ok(form.indexOf("TodayContextRibbon(") < form.indexOf("DisclosureCard("));
  assert.ok(form.indexOf("TodayContextRibbon(") < form.indexOf("if (authority.requiresChildConfirmation)"));
  assert.ok(form.indexOf("TodayContextRibbon(") < form.indexOf("viewModel.discover("));
  assert.equal(form.match(/TodayContextRibbon\(/g)?.length, 1);
  assert.match(form, /if \(!useOfflineDemo\) \{\s*item \{\s*TodayContextRibbon\(/);
  assert.match(today, /viewModel\.startNewDiscovery\(keepDraft = state\.discoverySourceIssues\.isNotEmpty\(\)\)[\s\S]*?invalidateApprovals\(\)[\s\S]*?includeRecentContext = false\s*useOfflineDemo = false/);
  assert.match(form, /onExpression = \{[\s\S]*?invalidateApprovals\(\)/);
  assert.match(form, /onChange = \{ persist, privateOnly ->[\s\S]*?invalidateApprovals\(\)/);
  assert.match(form, /onIncludeRecentEvidence = \{[\s\S]*?invalidateApprovals\(\)/);
  assert.ok(form.indexOf("DisclosureCard(") < form.indexOf("if (authority.requiresChildConfirmation)"));
  assert.match(form, /else "我已看过这次的数据选择，同意寻找入口；最后由我决定是否选择。"/);
  assert.match(form, /if \(!useOfflineDemo\) \{\s*item \{\s*TodayContextRibbon\(/);
  assert.match(form, /viewModel\.discover\([\s\S]*?\)\s*invalidateApprovals\(\)/);
  assert.match(ribbon, /if \(stage == LifecycleStage\.HAND_OVER\) \{[\s\S]*?本人署名；共享设备不能核验实际操作者/);
  assert.ok(ribbon.indexOf("if (stage == LifecycleStage.HAND_OVER)") < ribbon.indexOf('Text("这次由谁记录"'));
  assert.match(viewModel, /family\.resolveDiscoveryAuthor\(child, stage, mutableState\.value\.activeMemberId\)/);
  const discover = viewModel.slice(viewModel.indexOf("    fun discover("), viewModel.indexOf("    fun choose("));
  assert.ok(discover.indexOf("family.resolveDiscoveryAuthor(") < discover.indexOf("discovering = true"));
  assert.match(lifecycle, /title = "找不找入口，由你决定"/);
});

test("AI disclosure and timeline use the same family-facing history and audience language", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const family = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  const disclosure = today.slice(today.indexOf("private fun DisclosureCard("), today.indexOf("private fun ConstraintCard("));

  assert.match(today, /childFacing = stage == LifecycleStage\.HAND_OVER/);
  assert.match(disclosure, /最近 \$\{recentEvidence\.size\} 条共享足迹/);
  assert.match(disclosure, /不在共享足迹显示的记录和 AI 推测不会进入/);
  assert.match(disclosure, /明确拒绝或后来表达的看法/);
  assert.doesNotMatch(disclosure, /尝试后的反馈/);
  assert.match(disclosure, /if \(childFacing\) "你" else "孩子"/);
  assert.match(disclosure, /if \(childFacing\) "你的" else "孩子的"/);
  assert.match(disclosure, /家庭及成员编号/);
  assert.match(disclosure, /自由输入中的个人信息仍可能发送/);
  assert.match(disclosure, /填写的地区（请勿填精确地址）/);
  assert.doesNotMatch(disclosure, /非私密足迹|私密记录|内部 ID|三类目标/);
  assert.doesNotMatch(disclosure, /始终排除|不发送姓名、精确地址/);
  assert.match(today, /没有作为独立资料提供给 AI：/);
  assert.match(today, /不会被可靠地自动识别并删去/);
  assert.match(family, /查看本机操作记录/);
  assert.doesNotMatch(family, /本机审计记录/);
});

test("project handoff docs do not revive child-only or guaranteed free-text redaction claims", async () => {
  const zh = await readFile(path.resolve("README.zh-CN.md"), "utf8");
  const en = await readFile(path.resolve("README.md"), "utf8");
  const architecture = await readFile(path.resolve("ARCHITECTURE.md"), "utf8");

  assert.match(zh, /保存，不在共享足迹显示/);
  assert.match(zh, /自由输入或这些摘要里若写有姓名、精确地址等个人信息，目前不能保证自动识别并删去/);
  assert.doesNotMatch(zh, /只留给自己|姓名、内部 ID、精确地址、完整历史与密钥始终排除/);
  assert.match(en, /household-key and recovery-bundle holders may still read saved content/);
  assert.match(en, /does not reliably detect and remove them/);
  assert.match(architecture, /manually triggered document-folder preview wired through that coordinator/);
  assert.doesNotMatch(architecture, /Android folder authorization, background orchestration, WebDAV\/S3 adapters and App wiring remain outside/);
});

test("Graduation separates personal export from the household copy without a full discovery-colored card", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const graduation = today.slice(today.indexOf("if (!belowMinimumAge && stage == LifecycleStage.GRADUATION) {"), today.indexOf("if (!belowMinimumAge) discoveryResultItems("));

  assert.match(graduation, /JianyuTextPill\(text = "成年交接 · 本人掌控", tone = JianyuPillTone\.DISCOVERY\)/);
  assert.match(graduation, /JianyuCardTitle\("带走我的资料"\)/);
  assert.match(graduation, /JianyuCardTitle\("家庭副本"\)/);
  assert.match(graduation, /JianyuInset\(tone = JianyuCardTone\.PREVIEW\)/);
  assert.match(graduation, /Text\("导出我的加密资料包"\)/);
  assert.doesNotMatch(graduation, /JianyuCard\(tone = JianyuCardTone\.DISCOVERY/);
});

test("results do not turn provider confidence or neutral constraints into a hidden ranking", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const opportunity = today.slice(today.indexOf("private fun OpportunityCard("), today.indexOf("private fun GateSummary("));
  const nothing = today.slice(today.indexOf("private fun NothingCard("), today.indexOf("private fun CostBand.asCostLabel("));
  const sourceIssues = today.slice(today.indexOf("private fun DiscoverySourceIssuesCard("), today.indexOf("private fun PersistenceChoice("));

  assert.doesNotMatch(opportunity, /item\.confidence|asConfidenceLabel|JianyuPillTone\.HUMAN/);
  assert.doesNotMatch(today, /private fun Double\.asConfidenceLabel\(/);
  assert.match(opportunity, /JianyuCard\(\s*contentPadding = PaddingValues\(18\.dp\),\s*emphasized = true/);
  assert.match(nothing, /JianyuCard\(\s*contentPadding = PaddingValues\(18\.dp\),\s*emphasized = true/);
  assert.match(nothing, /Button\(onClick = onChoose/);
  assert.doesNotMatch(nothing, /JianyuPillTone\.SUBTLE/);
  assert.doesNotMatch(sourceIssues, /JianyuCardTone\.DANGER/);
});

test("results use one page title and a secondary mode and retention receipt", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");

  assert.match(today, /state\.opportunities != null && !showComposer ->[\s\S]*?if \(state\.discoveryMode == "offline-demo"\) "离线演示" else "这次的发现"/);
  assert.doesNotMatch(today, /private fun OpportunityResultHeader\(/);
  assert.match(today, /JianyuInset\(tone = JianyuCardTone\.NEUTRAL\)/);
  assert.match(today, /"离线演示：未调用 AI，也未发送资料。"/);
  assert.match(today, /AI 找入口，本机筛选；/);
  assert.match(today, /描述不存入长期足迹；外部 AI 可能按其条款处理已发送内容。/);
  assert.doesNotMatch(today, /AI 寻找入口，本机先检查安全与现实条件/);
  assert.match(today, /"换个线索"/);
});

test("discovery mode reflects the family's choice rather than which source succeeded", async () => {
  const source = await readFile(
    path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"),
    "utf8",
  );

  assert.match(source, /discoveryMode\s*=\s*if\s*\(useOfflineDemo\)\s*"offline-demo"\s*else\s*"byok-ai"/);
  assert.doesNotMatch(source, /discoveryMode\s*=\s*result\.sourceKinds\.joinToString\(/);
});

test("Android formal discovery keeps AI, Pack, and dynamic World sources behind public interfaces", async () => {
  const source = await readFile(
    path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"),
    "utf8",
  );

  assert.match(source, /ai = container\.aiOpportunitySource/);
  assert.match(source, /others = listOf\(container\.packOpportunitySource, container\.worldBriefOpportunitySource\)/);
  assert.match(source, /worldBrief = container\.worldBriefProvider/);
  assert.match(source, /listOf\(formalSources\.ai\) \+ formalSources\.others/);
  assert.match(source, /worldBriefProvider = if \(useOfflineDemo \|\| !worldBriefConfigured\) null else formalSources\.worldBrief/);
});

test("offline demonstration previews choices without adding family history", async () => {
  const today = await readFile(path.join(uiRoot, "TodayScreen.kt"), "utf8");
  const viewModel = await readFile(path.resolve("apps/jianyu-android/app/src/main/kotlin/org/jianyu/app/MainViewModel.kt"), "utf8");
  const projector = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/RecentEvidenceProjector.kt"), "utf8");
  const legacyDemo = await readFile(path.resolve("apps/jianyu-android/core/domain/src/main/kotlin/org/jianyu/core/domain/LegacyDemoRecords.kt"), "utf8");
  const familyScreens = await readFile(path.join(uiRoot, "FamilyScreens.kt"), "utf8");
  assert.match(viewModel, /val effectivePersistContext = discoveryContextMayPersist\(useOfflineDemo, persistContext\)/);
  assert.match(viewModel, /if \(!discoveryChoiceMayPersist\(snapshot\.discoveryMode\)\)/);
  assert.match(today, /if \(!useOfflineDemo && selectedChild\.ageAt\(\) >= 7\)/);
  assert.match(today, /onPreviewChoice = \{ opportunity, vetoed -> demoPreview = opportunity to vetoed \}/);
  assert.match(today, /if \(isOfflineDemo\) onPreviewChoice\(opportunity, vetoed\)/);
  assert.match(today, /if \(isOfflineDemo\) onPreviewChoice\(set\.nothing, false\)/);
  assert.match(today, /刚才只是预览|仅作演示/);
  assert.match(projector, /filterNot \{ isLegacyDemoChoice\(it, events\) \}/);
  assert.match(legacyDemo, /choice\.opportunity\.sourceKind == "offline-demo-template"/);
  assert.match(familyScreens, /title = "旧版演示记录"/);
  assert.match(familyScreens, /不代表当事人真实表态/);
  assert.match(familyScreens, /查看旧版演示记录/);
  assert.match(familyScreens, /isLegacyDemoHypothesis\(it, family\.evidence, family\.events\)/);
  assert.ok(familyScreens.indexOf("if (realHypotheses.isNotEmpty())") < familyScreens.indexOf("if (demoChoices.isNotEmpty()"));
  assert.match(familyScreens, /private fun HypothesisCard[\s\S]*?JianyuCard\(tone = JianyuCardTone\.NEUTRAL\)/);
});
