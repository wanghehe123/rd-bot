import { useEffect, useMemo, useState } from "react";
import {
  FileSearch,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  Trash2
} from "lucide-react";
import { toast } from "sonner";

import { ProjectScopeSelector } from "@/components/ProjectScopeSelector";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { Empty, PageHeader } from "@/components/Ui";
import { useAsyncData } from "@/hooks";
import { useProjectScope } from "@/hooks/useProjectScope";
import { getErrorMessage } from "@/utils/error";
import { getProjectsPage, type RdProjectPage } from "@/services/projectService";
import {
  createRetrievalRule,
  deleteRetrievalRule,
  getRetrievalRules,
  previewRetrievalRuleRewrite,
  updateRetrievalRule,
  type QueryRewritePreview,
  type RetrievalRule,
  type RetrievalRuleCommand,
  type RetrievalRuleScope
} from "@/services/retrievalRuleService";

import { formatRuleTime, retrievalRuleSummary, scopeForNewRule } from "./retrievalRulePresentation";

const EMPTY_PROJECT_PAGE: RdProjectPage = { records: [], total: 0, page: 1, pageSize: 100, pages: 0 };
const ALL = "all";

export function RetrievalRulesPage() {
  const projectsState = useAsyncData(
    () => getProjectsPage({ page: 1, pageSize: 100 }),
    [],
    EMPTY_PROJECT_PAGE
  );
  const projectScope = useProjectScope(projectsState.data.records, { allowAll: true });
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [scopeFilter, setScopeFilter] = useState<"all" | RetrievalRuleScope>("all");
  const [enabledFilter, setEnabledFilter] = useState<"all" | "enabled" | "disabled">("all");
  const [editingRule, setEditingRule] = useState<RetrievalRule | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [previewText, setPreviewText] = useState("");
  const [preview, setPreview] = useState<QueryRewritePreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  const selectedProjectId = projectScope.projectId === ALL ? undefined : projectScope.projectId || undefined;
  const rulesState = useAsyncData(
    () => getRetrievalRules({
      projectId: selectedProjectId,
      scope: scopeFilter === "all" ? undefined : scopeFilter,
      enabled: enabledFilter === "all" ? undefined : enabledFilter === "enabled",
      keyword
    }),
    [selectedProjectId, scopeFilter, enabledFilter, keyword],
    [] as RetrievalRule[]
  );
  const summary = useMemo(() => retrievalRuleSummary(rulesState.data), [rulesState.data]);

  useEffect(() => {
    setPreview(null);
  }, [selectedProjectId]);

  const refresh = () => {
    void projectsState.refresh();
    void rulesState.refresh();
  };

  const openCreate = () => {
    setEditingRule(null);
    setDialogOpen(true);
  };

  const submitRule = async (command: RetrievalRuleCommand) => {
    try {
      if (editingRule) {
        await updateRetrievalRule(editingRule.id, command);
        toast.success("检索规则已保存");
      } else {
        await createRetrievalRule(command);
        toast.success("检索规则已创建");
      }
      setDialogOpen(false);
      setEditingRule(null);
      await rulesState.refresh();
    } catch (error) {
      toast.error(getErrorMessage(error, "无法保存检索规则"));
    }
  };

  const toggleRule = async (rule: RetrievalRule) => {
    try {
      await updateRetrievalRule(rule.id, {
        projectId: rule.projectId,
        scope: rule.scope,
        sourceTerm: rule.sourceTerm,
        targetTerm: rule.targetTerm,
        priority: rule.priority,
        enabled: !rule.enabled,
        remark: rule.remark
      });
      toast.success(rule.enabled ? "规则已停用" : "规则已启用");
      await rulesState.refresh();
    } catch (error) {
      toast.error(getErrorMessage(error, "无法更新规则状态"));
    }
  };

  const removeRule = async (rule: RetrievalRule) => {
    if (!window.confirm(`确定删除规则“${rule.sourceTerm}”吗？`)) return;
    try {
      await deleteRetrievalRule(rule.id);
      toast.success("规则已删除");
      await rulesState.refresh();
    } catch (error) {
      toast.error(getErrorMessage(error, "无法删除检索规则"));
    }
  };

  const runPreview = async () => {
    if (!previewText.trim()) {
      toast.error("请输入需要预览的需求或 Bug 描述");
      return;
    }
    setPreviewLoading(true);
    try {
      setPreview(await previewRetrievalRuleRewrite(selectedProjectId, previewText));
    } catch (error) {
      toast.error(getErrorMessage(error, "无法生成改写预览"));
    } finally {
      setPreviewLoading(false);
    }
  };

  return (
    <div className="admin-page retrieval-rules-page">
      <PageHeader
        title="检索规则"
        description="按项目控制查询改写；预览只读，不会创建任务或改变检索数据。"
        action={
          <div className="retrieval-header-actions">
            <ProjectScopeSelector
              projects={projectsState.data.records}
              projectId={projectScope.projectId}
              onProjectChange={projectScope.setProjectId}
              allowAll
              loading={projectsState.loading}
              unavailable={Boolean(projectsState.error)}
            />
            <Button variant="outline" size="icon" onClick={refresh} aria-label="刷新检索规则" title="刷新检索规则">
              <RefreshCw className={rulesState.loading ? "spin" : undefined} aria-hidden="true" />
            </Button>
            <Button onClick={openCreate}>
              <Plus aria-hidden="true" />
              新建规则
            </Button>
          </div>
        }
      />

      <section className="retrieval-summary-grid" aria-label="检索规则统计">
        <RuleSummary label="全部规则" value={summary.total} tone="teal" />
        <RuleSummary label="全局规则" value={summary.global} tone="blue" />
        <RuleSummary label="项目规则" value={summary.project} tone="violet" />
        <RuleSummary label="已启用" value={summary.enabled} tone="green" />
        <RuleSummary label="已停用" value={summary.disabled} tone="amber" />
      </section>

      <div className="retrieval-workspace">
        <section className="retrieval-list-panel" aria-label="检索规则列表">
          <div className="retrieval-filter-bar">
            <div className="retrieval-search-field">
              <Search aria-hidden="true" />
              <Input
                value={keywordInput}
                onChange={(event) => setKeywordInput(event.target.value)}
                onKeyDown={(event) => event.key === "Enter" && setKeyword(keywordInput.trim())}
                placeholder="搜索源词、目标表达或备注"
                aria-label="搜索检索规则"
              />
            </div>
            <Select value={scopeFilter} onValueChange={(value) => setScopeFilter(value as typeof scopeFilter)}>
              <SelectTrigger aria-label="筛选规则作用域"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部作用域</SelectItem>
                <SelectItem value="PROJECT">项目规则</SelectItem>
                <SelectItem value="GLOBAL">全局规则</SelectItem>
              </SelectContent>
            </Select>
            <Select value={enabledFilter} onValueChange={(value) => setEnabledFilter(value as typeof enabledFilter)}>
              <SelectTrigger aria-label="筛选规则状态"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部状态</SelectItem>
                <SelectItem value="enabled">已启用</SelectItem>
                <SelectItem value="disabled">已停用</SelectItem>
              </SelectContent>
            </Select>
            <Button variant="outline" onClick={() => setKeyword(keywordInput.trim())}>筛选</Button>
          </div>

          {rulesState.error ? <InlineNotice text={rulesState.error} /> : null}
          <Card className="retrieval-table-card">
            <CardHeader>
              <CardTitle>生效规则</CardTitle>
              <CardDescription>项目规则优先于全局规则；同作用域按优先级、词条长度和稳定 ID 排序。</CardDescription>
            </CardHeader>
            <CardContent>
              {rulesState.data.length ? (
                <Table className="min-w-[920px]">
                  <TableHeader>
                    <TableRow>
                      <TableHead>源词</TableHead>
                      <TableHead>目标表达</TableHead>
                      <TableHead>作用域</TableHead>
                      <TableHead>优先级</TableHead>
                      <TableHead>状态</TableHead>
                      <TableHead>更新时间</TableHead>
                      <TableHead className="text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rulesState.data.map((rule) => (
                      <TableRow key={rule.id}>
                        <TableCell className="font-medium">{rule.sourceTerm}</TableCell>
                        <TableCell><code className="retrieval-target-code">{rule.targetTerm}</code></TableCell>
                        <TableCell>
                          <span className={`retrieval-scope-tag retrieval-scope-tag--${rule.scope.toLowerCase()}`}>
                            {rule.scope === "PROJECT" ? "项目" : "全局"}
                          </span>
                        </TableCell>
                        <TableCell className="tabular-nums">{rule.priority}</TableCell>
                        <TableCell>
                          <label className="retrieval-enabled-control">
                            <Checkbox checked={rule.enabled} onCheckedChange={() => void toggleRule(rule)} aria-label={`${rule.enabled ? "停用" : "启用"}${rule.sourceTerm}`} />
                            <span>{rule.enabled ? "启用" : "停用"}</span>
                          </label>
                        </TableCell>
                        <TableCell className="text-muted-foreground">{formatRuleTime(rule.updatedAtEpochMillis)}</TableCell>
                        <TableCell>
                          <div className="retrieval-row-actions">
                            <Button variant="ghost" size="icon" onClick={() => { setEditingRule(rule); setDialogOpen(true); }} aria-label={`编辑${rule.sourceTerm}`} title="编辑规则">
                              <Pencil aria-hidden="true" />
                            </Button>
                            <Button variant="ghost" size="icon" onClick={() => void removeRule(rule)} aria-label={`删除${rule.sourceTerm}`} title="删除规则" className="text-rose-600 hover:text-rose-700">
                              <Trash2 aria-hidden="true" />
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              ) : (
                <Empty>{rulesState.loading ? "正在加载检索规则..." : "当前范围没有检索规则。"}</Empty>
              )}
            </CardContent>
          </Card>
        </section>

        <Card className="retrieval-preview-panel">
          <CardHeader>
            <CardTitle className="flex items-center gap-2"><Sparkles className="h-4 w-4 text-teal-700" aria-hidden="true" />改写预览</CardTitle>
            <CardDescription>按当前项目范围应用有效规则，查看顺序和最终文本。</CardDescription>
          </CardHeader>
          <CardContent className="retrieval-preview-content">
            <Textarea
              value={previewText}
              onChange={(event) => setPreviewText(event.target.value)}
              placeholder="输入真实需求或 Bug 描述"
              aria-label="改写预览输入"
              className="min-h-36 resize-y"
            />
            <Button onClick={() => void runPreview()} disabled={previewLoading}>
              <FileSearch aria-hidden="true" />
              {previewLoading ? "预览中" : "运行预览"}
            </Button>
            {preview ? <RewritePreview preview={preview} /> : <p className="retrieval-preview-empty">预览结果会在这里展示。</p>}
          </CardContent>
        </Card>
      </div>

      <RuleDialog
        open={dialogOpen}
        rule={editingRule}
        projectId={editingRule?.scope === "PROJECT" ? editingRule.projectId : selectedProjectId}
        onOpenChange={(open) => { setDialogOpen(open); if (!open) setEditingRule(null); }}
        onSubmit={submitRule}
      />
    </div>
  );
}

function RuleSummary({ label, value, tone }: { label: string; value: number; tone: string }) {
  return <div className={`retrieval-summary-item retrieval-summary-item--${tone}`}><span>{label}</span><strong>{value.toLocaleString("zh-CN")}</strong></div>;
}

function InlineNotice({ text }: { text: string }) {
  return <div className="retrieval-inline-notice" role="status">{text}</div>;
}

function RewritePreview({ preview }: { preview: QueryRewritePreview }) {
  return (
    <div className="retrieval-preview-result">
      <div><span>原始文本</span><p>{preview.originalText}</p></div>
      <div><span>改写结果</span><p className="retrieval-preview-rewritten">{preview.rewrittenText}</p></div>
      <div className="retrieval-match-list">
        <span>命中规则 {preview.matches.length}</span>
        {preview.matches.length ? preview.matches.map((match) => (
          <div key={match.mappingId} className="retrieval-match-row">
            <code>{match.sourceTerm}</code><span>to</span><code>{match.targetTerm}</code><em>{match.scope === "PROJECT" ? "项目" : "全局"}</em>
          </div>
        )) : <p>没有命中规则，原始文本保持不变。</p>}
      </div>
    </div>
  );
}

function RuleDialog({
  open,
  rule,
  projectId,
  onOpenChange,
  onSubmit
}: {
  open: boolean;
  rule: RetrievalRule | null;
  projectId?: string;
  onOpenChange: (open: boolean) => void;
  onSubmit: (command: RetrievalRuleCommand) => Promise<void>;
}) {
  const [scope, setScope] = useState<RetrievalRuleScope>(rule?.scope || scopeForNewRule(projectId || ""));
  const [sourceTerm, setSourceTerm] = useState(rule?.sourceTerm || "");
  const [targetTerm, setTargetTerm] = useState(rule?.targetTerm || "");
  const [priority, setPriority] = useState(String(rule?.priority ?? 10));
  const [enabled, setEnabled] = useState(rule?.enabled ?? true);
  const [remark, setRemark] = useState(rule?.remark || "");
  const [saving, setSaving] = useState(false);
  const projectRuleUnavailable = scope === "PROJECT" && !projectId;

  useEffect(() => {
    if (open) {
      resetForRule(rule);
    }
  }, [open, projectId, rule]);

  const resetForRule = (nextRule: RetrievalRule | null) => {
    setScope(nextRule?.scope || scopeForNewRule(projectId || ""));
    setSourceTerm(nextRule?.sourceTerm || "");
    setTargetTerm(nextRule?.targetTerm || "");
    setPriority(String(nextRule?.priority ?? 10));
    setEnabled(nextRule?.enabled ?? true);
    setRemark(nextRule?.remark || "");
  };

  const submit = async () => {
    if (!sourceTerm.trim() || !targetTerm.trim() || projectRuleUnavailable) return;
    setSaving(true);
    try {
      await onSubmit({
        projectId: scope === "PROJECT" ? projectId || "" : "",
        scope,
        sourceTerm: sourceTerm.trim(),
        targetTerm: targetTerm.trim(),
        priority: Number(priority) || 0,
        enabled,
        remark: remark.trim()
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => { if (!nextOpen) resetForRule(null); onOpenChange(nextOpen); }}>
      <DialogContent className="max-h-[calc(100vh-2rem)] overflow-y-auto sm:max-w-[620px]">
        <DialogHeader>
          <DialogTitle>{rule ? "编辑检索规则" : "新建检索规则"}</DialogTitle>
          <DialogDescription>项目规则仅对当前选择的项目生效；全局规则可由所有项目作为后备使用。</DialogDescription>
        </DialogHeader>
        <div className="retrieval-rule-form">
          <label>作用域
            <Select value={scope} onValueChange={(value) => setScope(value as RetrievalRuleScope)}>
              <SelectTrigger><SelectValue /></SelectTrigger>
              <SelectContent><SelectItem value="PROJECT">项目规则</SelectItem><SelectItem value="GLOBAL">全局规则</SelectItem></SelectContent>
            </Select>
          </label>
          {scope === "PROJECT" ? <p className="retrieval-project-hint">{projectId ? `将应用到当前项目：${projectId}` : "请先在页面顶部选择一个具体项目。"}</p> : null}
          <label>源词<Input value={sourceTerm} onChange={(event) => setSourceTerm(event.target.value)} placeholder="例如：创建订单" /></label>
          <label>目标表达<Input value={targetTerm} onChange={(event) => setTargetTerm(event.target.value)} placeholder="例如：POST /api/orders" /></label>
          <label>优先级<Input type="number" value={priority} onChange={(event) => setPriority(event.target.value)} /></label>
          <label className="retrieval-enabled-control"><Checkbox checked={enabled} onCheckedChange={(checked) => setEnabled(checked === true)} />启用规则</label>
          <label>备注<Textarea value={remark} onChange={(event) => setRemark(event.target.value)} placeholder="记录适用场景或变更原因" /></label>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={() => void submit()} disabled={saving || !sourceTerm.trim() || !targetTerm.trim() || projectRuleUnavailable}>{saving ? "保存中" : "保存规则"}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
