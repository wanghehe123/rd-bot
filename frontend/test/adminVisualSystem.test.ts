import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path: string) => readFileSync(new URL(path, import.meta.url), "utf8");

test("isolates admin tokens from shadcn HSL tokens", () => {
  const main = read("../src/main.tsx");
  const styles = read("../src/styles.css");
  const globals = read("../src/styles/globals.css");

  assert.ok(main.indexOf('"./styles/globals.css"') < main.indexOf('"./styles.css"'));
  assert.match(styles, /--admin-workspace:/);
  assert.match(styles, /--admin-border:/);
  assert.doesNotMatch(styles, /(^|\n)\s*--border\s*:/);
  assert.doesNotMatch(styles, /(^|\n)\s*--accent\s*:/);
  assert.doesNotMatch(globals, /\.admin-layout \.admin-sidebar/);
});

test("uses an independent accessible mobile navigation drawer", () => {
  const layout = read("../src/components/AdminLayout.tsx");

  assert.match(layout, /mobileSidebarOpen/);
  assert.match(layout, /admin-sidebar-backdrop/);
  assert.match(layout, /aria-expanded=\{mobileSidebarOpen\}/);
  assert.match(layout, /event\.key === "Escape"/);
  assert.match(layout, /matchMedia\("\(max-width: 860px\)"\)/);
  assert.match(layout, /aria-hidden=\{mobileSidebarHidden \? true : undefined\}/);
  assert.match(layout, /const mobileSidebarInertProps = mobileSidebarHidden/);
  assert.match(layout, /\{ inert: "" \} as Record<string, string>/);
  assert.match(layout, /\{\.\.\.mobileSidebarInertProps\}/);
  assert.match(layout, /previousMobileSidebarOpenRef/);
  assert.match(layout, /data-admin-mobile-toggle/);
  assert.match(layout, /querySelector<HTMLElement>\("a\[href\], button:not\(\[disabled\]\)"\)/);
});

test("removes secondary topbar actions before the tablet layout is squeezed", () => {
  const layout = read("../src/components/AdminLayout.tsx");
  const styles = read("../src/styles.css");

  assert.equal((layout.match(/admin-topbar-secondary-action/g) || []).length, 2);
  assert.match(
    styles,
    /@media \(max-width: 1024px\) \{[\s\S]*?\.admin-topbar-secondary-action\s*\{[\s\S]*?display:\s*none;/
  );
});

test("replaces the broad knowledge search with a direct task creation action", () => {
  const layout = read("../src/components/AdminLayout.tsx");
  const taskList = read("../src/pages/admin/rdtask/RdTaskListPage.tsx");

  assert.doesNotMatch(layout, /搜索知识库 \/ 文档/);
  assert.match(layout, /navigate\("\/admin\/rd-tasks\?create=true"\)/);
  assert.match(taskList, /searchParams\.get\("create"\) !== "true"/);
  assert.match(taskList, /setCreateOpen\(true\)/);
});

test("dashboard uses live delivery data instead of a knowledge-first inventory", () => {
  const dashboard = read("../src/pages/DashboardPage.tsx");

  assert.doesNotMatch(dashboard, /chartPoints/);
  assert.doesNotMatch(dashboard, /7\.91s|25\.56s|100\.0%|17\.5%|9\.1%/);
  assert.doesNotMatch(dashboard, /资产构成|知识资产健康|知识库快照/);
  assert.match(dashboard, /需求总数/);
  assert.match(dashboard, /Bug 总数/);
  assert.match(dashboard, /当前执行/);
  assert.match(dashboard, /近期交付/);
  assert.match(dashboard, /getDashboardOverview/);
  assert.match(dashboard, /未选择项目/);
});

test("keeps the supported admin routes and removes obsolete intent/ingestion/mapping surfaces", () => {
  const app = read("../src/App.tsx");
  const layout = read("../src/components/AdminLayout.tsx");
  const legacyApi = read("../src/api.ts");
  const routes = [
    "dashboard",
    "knowledge",
    "knowledge/:kbId",
    "knowledge/:kbId/docs/:docId",
    "projects",
    "rd-tasks",
    "rd-tasks/:taskId",
    "users",
    "skills",
    "traces",
    "traces/:taskId",
    "observability",
    "settings"
  ];

  for (const route of routes) {
    assert.ok(app.includes(`path="${route}"`), `missing route: ${route}`);
  }

  assert.doesNotMatch(app, /intent-tree|intent-list|IngestionPage|RetrievalRulesPage/);
  assert.doesNotMatch(layout, /意图管理|数据通道|检索规则|intent-tree|intent-list|\/admin\/ingestion|\/admin\/mappings/);
  assert.match(layout, /label: "执行追踪"/);
  assert.match(layout, /label: "交付观测"/);
  assert.match(layout, /path: "\/admin\/observability"/);
  assert.match(layout, /path: "\/admin\/skills"/);
  assert.match(layout, /label: "Skill Hub"/);
  assert.match(layout, /skills: "Skill Hub"/);
  assert.doesNotMatch(layout, /示例问题/);
  assert.doesNotMatch(app, /sample-questions|SampleQuestionPage/);
  assert.doesNotMatch(legacyApi, /listSampleQuestions|createSampleQuestion|updateSampleQuestion|deleteSampleQuestion/);
  assert.doesNotMatch(legacyApi, /listIntentTree|createIntent|listPipelines|createPipeline/);
  assert.match(app, /ExecutionTraceDetailPage/);
  assert.doesNotMatch(app, /const MappingPage|const TracePage =|const TraceDetailPage/);
});

test("loads route modules lazily behind a stable fallback", () => {
  const app = read("../src/App.tsx");
  const layout = read("../src/components/AdminLayout.tsx");

  assert.match(app, /lazy\(\(\) => import\(/);
  assert.match(app, /<Suspense fallback=\{<AdminRouteFallback \/>\}>/);
  assert.match(app, /function AdminRouteFallback/);
  assert.match(app, /<BrowserRouter future=\{\{ v7_startTransition: true, v7_relativeSplatPath: true \}\}>/);
  assert.match(layout, /<Suspense fallback=\{<AdminContentFallback \/>\}>[\s\S]*<Outlet \/>[\s\S]*<\/Suspense>/);
});

test("defers the heavy markdown preview renderer until it is needed", () => {
  const documentsPage = read("../src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx");

  assert.doesNotMatch(documentsPage, /import \{ MarkdownRenderer \} from/);
  assert.match(documentsPage, /lazy\(\(\) => import\("@\/components\/chat\/MarkdownRenderer"\)/);
  assert.match(documentsPage, /<Suspense fallback=/);
});

test("uses the light syntax highlighter with explicit language registration", () => {
  const markdown = read("../src/components/chat/MarkdownRenderer.tsx");

  assert.doesNotMatch(markdown, /\{ Prism as SyntaxHighlighter \}/);
  assert.match(markdown, /PrismLight as SyntaxHighlighter/);
  assert.match(markdown, /SyntaxHighlighter\.registerLanguage\("typescript"/);
  assert.match(markdown, /SyntaxHighlighter\.registerLanguage\("java"/);
});

test("keeps actions visible in long mobile dialogs", () => {
  const tasks = read("../src/pages/admin/rdtask/RdTaskListPage.tsx");
  const projects = read("../src/pages/admin/project/ProjectListPage.tsx");
  const documents = read("../src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx");

  assert.match(tasks, /flex max-h-\[calc\(100vh-2rem\)\] flex-col overflow-hidden sm:max-w-\[860px\]/);
  assert.match(tasks, /min-h-0 flex-1 space-y-4 overflow-y-auto pr-1/);
  assert.match(tasks, /DialogFooter className="shrink-0 border-t border-slate-200 pt-4"/);

  assert.doesNotMatch(projects, /max-h-\[90vh\] overflow-y-auto sm:max-w-\[720px\]/);
  assert.doesNotMatch(documents, /max-h-\[90vh\] overflow-y-auto sidebar-scroll/);
});
