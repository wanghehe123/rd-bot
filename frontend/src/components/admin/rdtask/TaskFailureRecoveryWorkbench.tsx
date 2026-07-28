import { useEffect, useMemo, useRef, useState, type ChangeEvent, type ReactNode } from "react";
import { AlertTriangle, CheckCircle2, FileText, Paperclip, RotateCcw, Upload } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import {
  addTextTaskMaterial,
  uploadTaskMaterial,
  type RdTask,
  type TaskMaterial
} from "@/services/rdTaskService";
import {
  retryTaskFromFailure,
  type TaskFailureIssue,
  type TaskFailureRecoverySnapshot
} from "@/services/taskRetryService";
import { getErrorMessage } from "@/utils/error";

const ROLE_LABEL: Record<string, string> = {
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证"
};

// 与后端 AgentRole.requirementDeliveryOrder() 保持一致，用于计算可打回的上游角色。
const DELIVERY_ROLE_ORDER = [
  "REQUIREMENT_REVIEWER",
  "SOLUTION_ARCHITECT",
  "CODING_AGENT",
  "QA_AGENT"
];

const ISSUE_TONE: Record<string, string> = {
  issue: "border-amber-200 bg-amber-50/50",
  risk: "border-rose-200 bg-rose-50/50",
  gap: "border-sky-200 bg-sky-50/50"
};

export type TaskActionGuard = {
  isCurrent: () => boolean;
};

export type CaptureTaskActionGuard = (taskId: string) => TaskActionGuard;

type TaskFailureRecoveryWorkbenchProps = {
  task: RdTask;
  materials: TaskMaterial[];
  snapshot: TaskFailureRecoverySnapshot | null;
  loading: boolean;
  error: string;
  onRefresh: () => Promise<void>;
  captureTaskActionGuard: CaptureTaskActionGuard;
};

/**
 * Operator workbench for reviewing a failed requirement stage, attaching evidence, and resuming only from it.
 */
export function TaskFailureRecoveryWorkbench({
  task,
  materials,
  snapshot,
  loading,
  error,
  onRefresh,
  captureTaskActionGuard
}: TaskFailureRecoveryWorkbenchProps) {
  const [operatorNote, setOperatorNote] = useState("");
  const [selectedEvidenceIds, setSelectedEvidenceIds] = useState<string[]>([]);
  const [textTitle, setTextTitle] = useState("");
  const [textEvidence, setTextEvidence] = useState("");
  const [savingText, setSavingText] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [retryFromRole, setRetryFromRole] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const retryPointIdentity = snapshot ? [
    snapshot.retryPoint.failedStageRunId,
    snapshot.retryPoint.failedRetrievalRunId,
    snapshot.retryPoint.failedAiReviewRunId,
    snapshot.retryPoint.sourceTaskVersion
  ].join(":") : "";

  useEffect(() => {
    setOperatorNote("");
    setSelectedEvidenceIds([]);
    setTextTitle("");
    setTextEvidence("");
    setSavingText(false);
    setUploading(false);
    setRetrying(false);
    setRetryFromRole("");
  }, [retryPointIdentity, task.taskId]);

  useEffect(() => {
    setSelectedEvidenceIds((current) => current.filter((materialId) => (
      materials.some((material) => material.materialId === materialId)
    )));
  }, [materials]);

  const selectedEvidence = useMemo(() => (
    materials.filter((material) => selectedEvidenceIds.includes(material.materialId))
  ), [materials, selectedEvidenceIds]);
  // 只有角色阶段失败才允许选择重试起点；可选范围 = 失败角色及其上游（打回）。
  const retryRoleOptions = useMemo(() => {
    if (snapshot?.retryPoint.failurePhase !== "AGENT_ROLE") return [];
    const failedIndex = DELIVERY_ROLE_ORDER.indexOf(snapshot.retryPoint.retryFromRole);
    if (failedIndex < 0) return [];
    return DELIVERY_ROLE_ORDER.slice(0, failedIndex + 1);
  }, [snapshot]);
  const effectiveRetryRole = retryFromRole || snapshot?.retryPoint.retryFromRole || "";
  const isBounceBack = Boolean(
    retryFromRole && snapshot && retryFromRole !== snapshot.retryPoint.retryFromRole
  );
  const requiresSupplement = Boolean(snapshot?.diagnostic.requiresSupplement);
  const hasSupplement = Boolean(operatorNote.trim() || selectedEvidenceIds.length > 0);
  const retryDisabled = retrying || savingText || uploading || (requiresSupplement && !hasSupplement);

  const toggleEvidence = (materialId: string) => {
    setSelectedEvidenceIds((current) => (
      current.includes(materialId)
        ? current.filter((item) => item !== materialId)
        : [...current, materialId]
    ));
  };

  const addRecoveryTextEvidence = async () => {
    const actionGuard = captureTaskActionGuard(task.taskId);
    if (!actionGuard.isCurrent()) return;
    if (!snapshot || !textEvidence.trim()) {
      toast.error("请输入补充证据正文");
      return;
    }
    setSavingText(true);
    try {
      const material = await addTextTaskMaterial(task.taskId, {
        title: textTitle.trim() || "失败恢复补充",
        materialType: "REQUIREMENT_DOC",
        content: textEvidence.trim(),
        mimeType: "text/plain",
        recoveryStageRunId: snapshot.retryPoint.failedStageRunId
      });
      if (!actionGuard.isCurrent()) return;
      setSelectedEvidenceIds((current) => current.includes(material.materialId)
        ? current
        : [...current, material.materialId]);
      setTextTitle("");
      setTextEvidence("");
      await onRefresh();
      if (!actionGuard.isCurrent()) return;
      toast.success("补充证据已加入本次恢复");
    } catch (requestError) {
      if (!actionGuard.isCurrent()) return;
      toast.error(getErrorMessage(requestError, "保存补充证据失败"));
    } finally {
      if (actionGuard.isCurrent()) setSavingText(false);
    }
  };

  const uploadRecoveryEvidence = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!snapshot || !file) return;
    const actionGuard = captureTaskActionGuard(task.taskId);
    if (!actionGuard.isCurrent()) return;
    setUploading(true);
    try {
      const material = await uploadTaskMaterial(task.taskId, file, {
        title: file.name,
        materialType: file.type.startsWith("image/") ? "SCREENSHOT" : "REQUIREMENT_DOC",
        recoveryStageRunId: snapshot.retryPoint.failedStageRunId
      });
      if (!actionGuard.isCurrent()) return;
      setSelectedEvidenceIds((current) => current.includes(material.materialId)
        ? current
        : [...current, material.materialId]);
      await onRefresh();
      if (!actionGuard.isCurrent()) return;
      toast.success("文件证据已加入本次恢复");
    } catch (requestError) {
      if (!actionGuard.isCurrent()) return;
      toast.error(getErrorMessage(requestError, "上传证据失败"));
    } finally {
      if (actionGuard.isCurrent()) setUploading(false);
    }
  };

  const resumeFromFailure = async () => {
    if (!snapshot || retryDisabled) return;
    const actionGuard = captureTaskActionGuard(task.taskId);
    if (!actionGuard.isCurrent()) return;
    setRetrying(true);
    try {
      const checkpoint = await retryTaskFromFailure(task.taskId, {
        expectedFailedStageRunId: snapshot.retryPoint.failedStageRunId,
        expectedFailedRetrievalRunId: snapshot.retryPoint.failedRetrievalRunId,
        expectedFailedAiReviewRunId: snapshot.retryPoint.failedAiReviewRunId,
        expectedSourceTaskVersion: snapshot.retryPoint.sourceTaskVersion,
        operatorNote: operatorNote.trim(),
        evidenceMaterialIds: selectedEvidenceIds,
        retryFromRole: isBounceBack ? retryFromRole : ""
      });
      if (!actionGuard.isCurrent()) return;
      toast.success(
        `已从 ${roleLabel(checkpoint.retryFromRole || checkpoint.failurePhase)} 创建 attempt ${checkpoint.attemptNo}`
      );
      await onRefresh();
    } catch (requestError) {
      if (!actionGuard.isCurrent()) return;
      toast.error(getErrorMessage(requestError, "任务重试失败"));
    } finally {
      if (actionGuard.isCurrent()) setRetrying(false);
    }
  };

  if (loading) {
    return <RecoverySection><div className="py-5 text-sm text-muted-foreground">正在加载失败诊断...</div></RecoverySection>;
  }

  if (error || !snapshot) {
    return (
      <RecoverySection>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="text-sm text-destructive">{error || "失败恢复信息暂不可用"}</div>
          <Button type="button" size="sm" variant="outline" onClick={() => void onRefresh()}>
            重新加载
          </Button>
        </div>
      </RecoverySection>
    );
  }

  const { diagnostic, retryPoint } = snapshot;
  return (
    <RecoverySection>
      <div className="flex flex-col justify-between gap-4 border-b border-amber-200 pb-5 lg:flex-row lg:items-start">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <AlertTriangle className="h-5 w-5 text-amber-700" aria-hidden="true" />
            <h2 className="text-lg font-semibold text-slate-950">失败诊断与恢复</h2>
            <Badge variant="outline" className="border-amber-300 bg-amber-50 text-amber-800">
              {roleLabel(retryPoint.retryFromRole || retryPoint.failurePhase)}
            </Badge>
            <Badge variant="outline" className="border-slate-300 bg-white text-slate-700">
              attempt {snapshot.failedAttemptNo || "-"}
            </Badge>
          </div>
          <p className="mt-2 max-w-3xl text-sm text-slate-700">{diagnostic.summary || snapshot.errorMessage || "已定位失败阶段。"}</p>
          <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-600">
            <span>失败阶段：{snapshot.failedStageStatus || "-"}</span>
            <span>Provider：{snapshot.providerName || "-"}</span>
            <span>错误分类：{snapshot.errorCategory || diagnostic.category || "-"}</span>
          </div>
        </div>
        <div className="border-l-2 border-teal-600 pl-3 text-sm text-slate-700 lg:max-w-sm">
          <div className="font-medium text-slate-900">{diagnostic.suggestedAction || "补充证据后继续执行。"}</div>
          <div className="mt-1 text-xs text-slate-600">上游已成功阶段不会重跑。</div>
        </div>
      </div>

      <div className="grid gap-6 py-5 xl:grid-cols-[minmax(0,1fr)_minmax(360px,0.92fr)]">
        <div className="min-w-0 space-y-4">
          <div className="grid gap-3 lg:grid-cols-3">
            <FailureIssueList title="待补充信息" items={diagnostic.issues} tone="issue" />
            <FailureIssueList title="风险" items={diagnostic.risks} tone="risk" />
            <FailureIssueList title="验收缺口" items={diagnostic.acceptanceGaps} tone="gap" />
          </div>

          {snapshot.errorMessage ? (
            <div className="border-l-2 border-rose-500 bg-rose-50/60 px-3 py-2 text-sm text-rose-900">
              <span className="font-medium">阶段错误：</span>{snapshot.errorMessage}
            </div>
          ) : null}

          {snapshot.rawResultPreview ? (
            <details className="border border-slate-200 bg-white">
              <summary className="cursor-pointer px-3 py-2 text-sm font-medium text-slate-700">
                原始角色结果
                {snapshot.rawResultArtifactId ? <span className="ml-2 font-mono text-xs text-slate-400">{snapshot.rawResultArtifactId}</span> : null}
              </summary>
              <pre className="max-h-80 overflow-auto border-t border-slate-200 bg-slate-950 p-3 text-xs leading-5 text-slate-100">
                {snapshot.rawResultPreview}
              </pre>
            </details>
          ) : null}

          <CheckpointHistory history={snapshot.history} />
        </div>

        <div className="min-w-0 border-l-0 border-slate-200 xl:border-l xl:pl-6">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h3 className="text-sm font-semibold text-slate-950">恢复输入</h3>
              <p className="mt-1 text-xs text-slate-600">将随新 attempt 进入失败阶段及下游角色的独立上下文。</p>
            </div>
            {requiresSupplement ? <Badge className="bg-amber-600 text-white">需要补充</Badge> : null}
          </div>

          <div className="mt-4 space-y-4">
            <div className="space-y-2">
              <Label htmlFor="recovery-operator-note">补充说明</Label>
              <Textarea
                id="recovery-operator-note"
                value={operatorNote}
                onChange={(event) => setOperatorNote(event.target.value)}
                placeholder="说明已确认的事实、约束或恢复决策"
                className="min-h-28 rounded-md"
              />
            </div>

            <div className="border-y border-slate-200 py-4">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div>
                  <div className="text-sm font-medium text-slate-900">选择已有材料</div>
                  <div className="mt-1 text-xs text-slate-600">仅可选择当前任务已归档的材料。</div>
                </div>
                <span className="text-xs text-slate-500">已选 {selectedEvidence.length}</span>
              </div>
              <div className="max-h-52 space-y-2 overflow-auto pr-1">
                {materials.length === 0 ? (
                  <div className="border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-500">暂无可选材料</div>
                ) : materials.map((material) => {
                  const checked = selectedEvidenceIds.includes(material.materialId);
                  return (
                    <label
                      key={material.materialId}
                      className={cn(
                        "flex cursor-pointer items-start gap-3 border px-3 py-2 text-sm transition-colors",
                        checked ? "border-teal-400 bg-teal-50" : "border-slate-200 bg-white hover:bg-slate-50"
                      )}
                    >
                      <Checkbox
                        checked={checked}
                        onCheckedChange={() => toggleEvidence(material.materialId)}
                        aria-label={`选择材料 ${material.title || material.materialId}`}
                      />
                      <span className="min-w-0">
                        <span className="block truncate font-medium text-slate-800">{material.title || material.materialId}</span>
                        <span className="mt-1 block line-clamp-2 text-xs text-slate-600">{material.contentPreview || material.mimeType}</span>
                      </span>
                    </label>
                  );
                })}
              </div>
            </div>

            <div className="border-b border-slate-200 pb-4">
              <div className="mb-3 text-sm font-medium text-slate-900">新增证据</div>
              <div className="space-y-2">
                <Label htmlFor="recovery-evidence-title">标题</Label>
                <Input
                  id="recovery-evidence-title"
                  value={textTitle}
                  onChange={(event) => setTextTitle(event.target.value)}
                  placeholder="例如：顾客测试账号确认"
                  className="h-10 rounded-md"
                />
                <Label htmlFor="recovery-evidence-text">正文</Label>
                <Textarea
                  id="recovery-evidence-text"
                  value={textEvidence}
                  onChange={(event) => setTextEvidence(event.target.value)}
                  placeholder="输入可供当前失败阶段核验的事实或验收证据"
                  className="min-h-24 rounded-md"
                />
                <div className="flex flex-wrap gap-2">
                  <Button type="button" size="sm" variant="outline" onClick={() => void addRecoveryTextEvidence()} disabled={savingText || !textEvidence.trim()}>
                    <FileText className="h-4 w-4" />
                    {savingText ? "保存中" : "添加文本证据"}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={uploading}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <Upload className="h-4 w-4" />
                    {uploading ? "上传中" : "上传文件"}
                  </Button>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept="image/png,image/jpeg,image/webp,image/gif,text/plain,text/markdown,text/csv,text/xml,text/html,application/json,application/xml"
                    className="sr-only"
                    onChange={(event) => void uploadRecoveryEvidence(event)}
                  />
                </div>
              </div>
            </div>

            {requiresSupplement && !hasSupplement ? (
              <div className="flex items-start gap-2 border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-xs text-amber-950">
                <Paperclip className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                请添加补充说明或至少选择一份任务材料后再重试。
              </div>
            ) : null}

            {retryRoleOptions.length > 1 ? (
              <div className="space-y-2">
                <Label htmlFor="recovery-retry-role">重试起点</Label>
                <Select value={effectiveRetryRole} onValueChange={setRetryFromRole}>
                  <SelectTrigger id="recovery-retry-role" className="rounded-md">
                    <SelectValue placeholder="选择重试起点角色" />
                  </SelectTrigger>
                  <SelectContent>
                    {retryRoleOptions.map((role) => (
                      <SelectItem key={role} value={role}>
                        {roleLabel(role)}
                        {role === snapshot.retryPoint.retryFromRole ? "（失败阶段）" : "（打回重做）"}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {isBounceBack ? (
                  <p className="text-xs text-amber-800">
                    将从 {roleLabel(retryFromRole)} 重新执行，其后置阶段（含 {roleLabel(snapshot.retryPoint.retryFromRole)}）会重新跑；下游失败原因会自动注入该角色提示词。
                  </p>
                ) : null}
              </div>
            ) : null}

            <Button type="button" onClick={() => void resumeFromFailure()} disabled={retryDisabled} className="w-full rounded-md">
              {retrying ? <RotateCcw className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
              {retrying
                ? "正在创建恢复 attempt"
                : isBounceBack
                  ? `打回至 ${roleLabel(retryFromRole)} 重试`
                  : `从 ${roleLabel(retryPoint.retryFromRole || retryPoint.failurePhase)} 重试`}
            </Button>
          </div>
        </div>
      </div>
    </RecoverySection>
  );
}

function RecoverySection({ children }: { children: ReactNode }) {
  return (
    <section id="failure-recovery-workbench" className="border-y border-amber-200 bg-amber-50/35 px-4 py-5 sm:px-6">
      {children}
    </section>
  );
}

function FailureIssueList({ title, items, tone }: { title: string; items: TaskFailureIssue[]; tone: "issue" | "risk" | "gap" }) {
  return (
    <section className="min-w-0 border-t-2 border-slate-300 bg-white px-3 py-3">
      <h3 className="text-sm font-semibold text-slate-900">{title}</h3>
      {items.length === 0 ? (
        <div className="mt-3 text-sm text-slate-500">无</div>
      ) : (
        <div className="mt-3 space-y-2">
          {items.map((item, index) => (
            <article key={`${item.kind}-${item.title}-${index}`} className={cn("border px-3 py-2", ISSUE_TONE[tone])}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0 font-medium text-slate-900">{item.title || item.kind}</div>
                {item.severity ? <Badge variant="outline" className="border-slate-300 bg-white text-slate-600">{item.severity}</Badge> : null}
              </div>
              {item.detail ? <p className="mt-1 whitespace-pre-wrap break-words text-xs leading-5 text-slate-700">{item.detail}</p> : null}
              {item.sourceField ? <div className="mt-2 font-mono text-[11px] text-slate-500">{item.sourceField}</div> : null}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function CheckpointHistory({ history }: { history: TaskFailureRecoverySnapshot["history"] }) {
  if (history.length === 0) return null;
  return (
    <section className="border-t border-slate-200 pt-4">
      <h3 className="text-sm font-semibold text-slate-900">恢复记录</h3>
      <div className="mt-3 divide-y divide-slate-200 border border-slate-200 bg-white text-xs">
        {history.map((checkpoint) => (
          <dl key={checkpoint.checkpointId} className="grid min-w-0 grid-cols-2 gap-x-3 gap-y-2 px-3 py-3 text-slate-700 sm:grid-cols-4">
            <HistoryValue label="Attempt" value={String(checkpoint.attemptNo)} mono />
            <HistoryValue label="状态" value={checkpoint.status} />
            <HistoryValue label="阶段" value={roleLabel(checkpoint.retryFromRole || checkpoint.failurePhase)} />
            <HistoryValue
              label="证据"
              value={checkpoint.operatorNote || (checkpoint.evidenceMaterialIds.length ? `${checkpoint.evidenceMaterialIds.length} 份材料` : "-")}
            />
          </dl>
        ))}
      </div>
    </section>
  );
}

function HistoryValue({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-slate-500">{label}</dt>
      <dd className={cn("mt-1 break-words text-slate-700", mono && "font-mono")}>{value || "-"}</dd>
    </div>
  );
}

function roleLabel(value: string) {
  return ROLE_LABEL[value] || value || "当前失败阶段";
}
