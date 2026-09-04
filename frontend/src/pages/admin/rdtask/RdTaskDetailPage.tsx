import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Activity, CheckCircle2, ChevronLeft, Download, FileArchive, FileInput, FileText, GitPullRequest, History, Image as ImageIcon, Pause, Play, RotateCcw, ShieldCheck, TerminalSquare, Upload, UsersRound, X } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { RelativeTime } from "@/components/RelativeTime";
import { TaskFailureRecoveryWorkbench } from "@/components/admin/rdtask/TaskFailureRecoveryWorkbench";
import { TaskRoleWorkbench, type RoleWorkbenchTab } from "@/components/admin/rdtask/TaskRoleWorkbench";
import { HostVerificationCard } from "@/components/admin/rdtask/HostVerificationCard";
import { AuditedTaskStateCard } from "@/components/admin/rdtask/AuditedTaskStateCard";
import { auditedCoverage } from "@/pages/admin/rdtask/auditedTaskStatePresentation";
import { getErrorMessage } from "@/utils/error";
import { cn } from "@/lib/utils";
import { createTaskRequestGuard, loadTaskDetailShell } from "@/pages/admin/rdtask/rdTaskDetailLoader";
import {
  canSubmitRequirementTask,
  evaluateRolePromptsFreshness,
  isRetryableRequirementTaskStatus,
  roleStageSignature,
  taskStatusNotice,
  type ExpectedStageIdentity
} from "@/pages/admin/rdtask/roleWorkbenchModel";

import {
  getRdTask,
  getRdTaskAuditContent,
  getRdTaskExecutionOverview,
  getRdTaskRolePrompts,
  getRdTaskMaterials,
  getRdTaskQaEvidence,
  getRdTaskHostVerifications,
  getRdTaskAuditedState,
  getRdTaskAuditRuns,
  getRdTaskTimeline,
  approveRdTask,
  pauseRdTask,
  resumeRdTask,
  STATUS_BADGE_CLASS,
  submitRdTask,
  taskMaterialContentUrl,
  uploadTaskMaterial,
  type RdTask,
  type RdTaskExecutionOverview,
  type RdTaskRolePromptStage,
  type TaskMaterial,
  type RdTaskQaEvidence,
  type HostVerificationList,
  type AuditedTaskState,
  type AuditRunList,
  type RdTaskStatusEvent
} from "@/services/rdTaskService";
import {
  cancelRetrievalRun,
  getRetrievalRun,
  getRetrievalRunArtifacts,
  getRetrievalRunTimeline,
  getRetrievalRuns,
  isRetrievalRunActive,
  retrievalRunStatusClass,
  retrievalRunStatusLabel,
  retryRetrievalRun,
  type RetrievalRun,
  type RetrievalRunArtifact,
  type RetrievalRunEvent
} from "@/services/retrievalRunService";
import { getTaskFailureRecovery, type TaskFailureRecoverySnapshot } from "@/services/taskRetryService";
import {
  aiReviewStatusLabel, cancelAiReview, getAiReview, getAiReviewArtifacts, getAiReviews,
  getAiReviewTimeline, isAiReviewActive, retryAiReview, type AiReviewArtifact,
  startAiReview, type AiReviewEvent, type AiReviewRun
} from "@/services/aiReviewService";

const MarkdownRenderer = lazy(() => import("@/components/chat/MarkdownRenderer").then((module) => ({ default: module.MarkdownRenderer })));

const formatDuration = (ms?: number) => {
  if (!ms || ms <= 0) return "首步";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)}min`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
};

const formatTokens = (value?: number) => Math.max(0, value || 0).toLocaleString("en-US");

const formatBytes = (value?: number) => {
  const bytes = Math.max(0, value || 0);
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const QA_EVIDENCE_LABEL: Record<string, string> = {
  QA_COMMAND_LOG: "命令日志",
  QA_SCREENSHOT: "浏览器截图",
  QA_TRACE: "Playwright trace",
  QA_CONSOLE_LOG: "控制台日志",
  QA_NETWORK_LOG: "网络记录",
  QA_HTTP_TRANSCRIPT: "HTTP 实链",
  QA_VIDEO: "失败录像",
  QA_EVIDENCE_MANIFEST: "证据清单"
};

const EVENT_DOT_TONE: Record<string, string> = {
  CREATED: "bg-blue-500",
  MATERIAL_COLLECTING: "bg-sky-500",
  MATERIAL_READY: "bg-teal-500",
  CONTEXT_BUILDING: "bg-cyan-500",
  CONTEXT_READY: "bg-teal-500",
  PLAN_GENERATING: "bg-blue-500",
  PLAN_GENERATED: "bg-cyan-500",
  WAITING_POLICY: "bg-amber-500",
  WAITING_APPROVAL: "bg-orange-500",
  SEARCHING: "bg-amber-500",
  EXECUTING: "bg-teal-500",
  VALIDATING: "bg-sky-500",
  PR_CREATING: "bg-emerald-500",
  COMMITTED: "bg-emerald-500",
  MERGED: "bg-green-600",
  REPORTING: "bg-slate-500",
  COMPLETED: "bg-green-600",
  REJECTED: "bg-red-500",
  FAILED_RETRYABLE: "bg-rose-500",
  FAILED_NEEDS_HUMAN: "bg-orange-500",
  CANCELLED: "bg-slate-500",
  DEAD_LETTERED: "bg-red-700",
  RECOVERING: "bg-cyan-500",
  PAUSED: "bg-slate-400",
  RESUMED: "bg-cyan-500",
  DELETED: "bg-slate-400"
};

const STAGE_STATUS_LABEL: Record<string, string> = {
  PENDING: "待开始", CONTEXT_READY: "上下文就绪", DISPATCHING: "调度中", RUNNING: "执行中",
  RESULT_COLLECTING: "收集结果", VERIFYING: "验证中", SUCCEEDED: "已成功",
  FAILED_RETRYABLE: "可重试失败", FAILED_NEEDS_HUMAN: "需人工处理",
  SKIPPED: "已跳过", CANCELLED: "已取消", RECOVERING: "恢复中"
};

const ROLE_LABEL: Record<string, string> = {
  BUG_EVIDENCE_COLLECTOR: "证据收集",
  BUG_RAG_RETRIEVER: "RAG 检索",
  BUG_ACCEPTANCE_PLANNER: "验收规划",
  BUG_CODING_AGENT: "修复执行",
  REQUIREMENT_REVIEWER: "需求评审",
  SOLUTION_ARCHITECT: "方案设计",
  CODING_AGENT: "编码执行",
  QA_AGENT: "质量验证"
};

const RETRIEVAL_CONTENT_ARTIFACT_TYPES = new Set([
  "CHANNEL_CANDIDATE", "SELECTED_EVIDENCE", "MATERIAL_EVIDENCE", "DOCUMENT_EXPANSION", "CONTEXT_PACKAGE"
]);

type TaskDetailView = "roles" | "delivery" | "audit";

/** Keeps secondary detail refreshes serialized and slower than the task-shell heartbeat. */
function scheduleActiveDetailRefresh(refresh: () => Promise<void>, delayMillis = 5_000) {
  let stopped = false;
  let timer = 0;
  const tick = async () => {
    await refresh();
    if (!stopped) timer = window.setTimeout(tick, delayMillis);
  };
  timer = window.setTimeout(tick, delayMillis);
  return () => {
    stopped = true;
    window.clearTimeout(timer);
  };
}

function mergeWorkbenchTask(shell: RdTask, current: RdTask | null): RdTask {
  if (!current || current.taskId !== shell.taskId) return shell;
  const keepPrompt = Boolean(current.promptSnapshot);
  const keepEvidence = Boolean(current.executionEvidence?.summary)
    || Boolean(current.executionEvidence?.prBody)
    || (current.executionEvidence?.changedFiles?.length || 0) > 0;
  if (!keepPrompt && !keepEvidence) return shell;
  return {
    ...shell,
    promptSnapshot: keepPrompt ? current.promptSnapshot : shell.promptSnapshot,
    executionResultJson: current.executionResultJson || shell.executionResultJson,
    executionEvidence: keepEvidence ? current.executionEvidence : shell.executionEvidence
  };
}

export function RdTaskDetailPage() {
  const { taskId = "" } = useParams();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [task, setTask] = useState<RdTask | null>(null);
  const [taskLoadError, setTaskLoadError] = useState("");
  const [panelErrors, setPanelErrors] = useState<Record<string, string>>({});
  const [events, setEvents] = useState<RdTaskStatusEvent[]>([]);
  const [materials, setMaterials] = useState<TaskMaterial[]>([]);
  const [qaEvidence, setQaEvidence] = useState<RdTaskQaEvidence[]>([]);
  const [qaEvidenceError, setQaEvidenceError] = useState("");
  const [executionOverview, setExecutionOverview] = useState<RdTaskExecutionOverview | null>(null);
  const [executionOverviewError, setExecutionOverviewError] = useState("");
  const [rolePromptStages, setRolePromptStages] = useState<RdTaskRolePromptStage[]>([]);
  const [rolePromptError, setRolePromptError] = useState("");
  const [loadingRolePrompts, setLoadingRolePrompts] = useState(false);
  const [loadingRoleEvidence, setLoadingRoleEvidence] = useState(false);
  const [retrievalRuns, setRetrievalRuns] = useState<RetrievalRun[]>([]);
  const [retrievalError, setRetrievalError] = useState("");
  const [selectedRetrievalRun, setSelectedRetrievalRun] = useState<RetrievalRun | null>(null);
  const [retrievalRunTimeline, setRetrievalRunTimeline] = useState<RetrievalRunEvent[]>([]);
  const [retrievalRunArtifacts, setRetrievalRunArtifacts] = useState<RetrievalRunArtifact[]>([]);
  const [loadingRetrievalDetail, setLoadingRetrievalDetail] = useState(false);
  const [aiReviews, setAiReviews] = useState<AiReviewRun[]>([]);
  const [selectedAiReview, setSelectedAiReview] = useState<AiReviewRun | null>(null);
  const [aiReviewTimeline, setAiReviewTimeline] = useState<AiReviewEvent[]>([]);
  const [aiReviewArtifacts, setAiReviewArtifacts] = useState<AiReviewArtifact[]>([]);
  const [loadingAiReviewDetail, setLoadingAiReviewDetail] = useState(false);
  const [startingAiReview, setStartingAiReview] = useState(false);
  const [failureRecovery, setFailureRecovery] = useState<TaskFailureRecoverySnapshot | null>(null);
  const [failureRecoveryError, setFailureRecoveryError] = useState("");
  const [loadingFailureRecovery, setLoadingFailureRecovery] = useState(false);
  const [hostVerifications, setHostVerifications] = useState<HostVerificationList | null>(null);
  const [hostVerificationsError, setHostVerificationsError] = useState("");
  const [loadingHostVerifications, setLoadingHostVerifications] = useState(false);
  const [auditedState, setAuditedState] = useState<AuditedTaskState | null>(null);
  const [auditRuns, setAuditRuns] = useState<AuditRunList | null>(null);
  const [auditedStateError, setAuditedStateError] = useState("");
  const [loadingAuditedState, setLoadingAuditedState] = useState(false);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [approving, setApproving] = useState(false);
  const [budgetApprovalOpen, setBudgetApprovalOpen] = useState(false);
  const [approvalMessage, setApprovalMessage] = useState("");
  const [uploading, setUploading] = useState(false);
  const loadSeqRef = useRef(0);
  const rolePromptLoadSeqRef = useRef(0);
  const roleEvidenceLoadSeqRef = useRef(0);
  const materialLoadSeqRef = useRef(0);
  const recoveryLoadSeqRef = useRef(0);
  const aiReviewLoadSeqRef = useRef(0);
  const hostVerificationLoadSeqRef = useRef(0);
  const auditedStateLoadSeqRef = useRef(0);
  const coreLoadSeqRef = useRef(0);
  const overviewLoadSeqRef = useRef(0);
  const retrievalDetailLoadSeqRef = useRef(0);
  const aiReviewDetailLoadSeqRef = useRef(0);
  const coreLoadInFlightRef = useRef("");
  const rolePromptInFlightRef = useRef("");
  const auditContentLoadedRef = useRef("");
  const requestGuardRef = useRef(createTaskRequestGuard());
  const loadedRolePromptSignatureRef = useRef<string | null>(null);
  const loadedRoleEvidenceSignatureRef = useRef<string | null>(null);

  const viewParam = searchParams.get("view");
  const view: TaskDetailView = viewParam === "delivery" || viewParam === "audit" ? viewParam : "roles";
  const selectedRole = searchParams.get("role") || "";
  const attemptParam = Number(searchParams.get("attempt") || "");
  const selectedAttemptNo = Number.isInteger(attemptParam) && attemptParam > 0 ? attemptParam : undefined;
  const tabParam = searchParams.get("tab");
  const selectedRoleTab: RoleWorkbenchTab = tabParam === "evidence" || tabParam === "runs" || tabParam === "trace"
    ? tabParam
    : "issues";

  const updateWorkspaceQuery = useCallback((updates: Record<string, string | number | undefined>) => {
    setSearchParams((current) => {
      const next = new URLSearchParams(current);
      Object.entries(updates).forEach(([key, value]) => {
        if (value === undefined || value === "") next.delete(key);
        else next.set(key, String(value));
      });
      return next;
    }, { replace: true });
  }, [setSearchParams]);

  const invalidateCoreLoad = useCallback(() => {
    coreLoadSeqRef.current += 1;
    coreLoadInFlightRef.current = "";
  }, []);

  const captureTaskActionGuard = useCallback((expectedTaskId: string) => {
    const requestToken = requestGuardRef.current.capture(expectedTaskId);
    if (requestGuardRef.current.isCurrent(requestToken)) invalidateCoreLoad();
    return {
      isCurrent: () => requestGuardRef.current.isCurrent(requestToken)
    };
  }, [invalidateCoreLoad]);

  const uploadScreenshots = async (files: FileList | null) => {
    if (!task || !files?.length) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    const requestSeq = ++materialLoadSeqRef.current;
    setUploading(true);
    try {
      for (const file of Array.from(files)) {
        await uploadTaskMaterial(taskSnapshot.taskId, file, { materialType: "SCREENSHOT" });
      }
      const nextMaterials = await getRdTaskMaterials(taskSnapshot.taskId);
      if (requestSeq !== materialLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setMaterials(nextMaterials);
      toast.success("图片材料已上传");
    } catch (error) {
      if (requestSeq !== materialLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "上传图片失败"));
    } finally {
      if (requestGuardRef.current.isCurrent(requestToken)) setUploading(false);
    }
  };

  const loadInitial = useCallback(async () => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestBaseKey = `${requestToken.taskId}:${requestToken.generation}`;
    setLoading(true);
    setTaskLoadError("");
    setPanelErrors({});
    const requestSeq = ++loadSeqRef.current;
    coreLoadInFlightRef.current = `${requestBaseKey}:initial`;
    try {
      await loadTaskDetailShell({
        loadTask: () => getRdTask(taskId),
        onTask: (detail) => {
          if (requestSeq === loadSeqRef.current && requestGuardRef.current.isCurrent(requestToken)) {
            setTask(detail);
            setLoading(false);
          }
        },
        panels: {}
      });
      if (requestSeq !== loadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
    } catch (error) {
      if (requestSeq !== loadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setTask(null);
      setTaskLoadError(getErrorMessage(error, "加载任务详情失败"));
    } finally {
      if (requestSeq === loadSeqRef.current && requestGuardRef.current.isCurrent(requestToken)) {
        setLoading(false);
      }
      if (coreLoadInFlightRef.current === `${requestBaseKey}:initial`) {
        coreLoadInFlightRef.current = "";
      }
    }
  }, [taskId]);

  const refreshCore = useCallback(async (silent = true) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestBaseKey = `${requestToken.taskId}:${requestToken.generation}`;
    if (coreLoadInFlightRef.current.startsWith(`${requestBaseKey}:`)) return;
    const requestSeq = ++coreLoadSeqRef.current;
    const requestKey = `${requestBaseKey}:${requestSeq}`;
    coreLoadInFlightRef.current = requestKey;
    try {
      const [taskResult, overviewResult] = await Promise.allSettled([
        getRdTask(taskId),
        view === "roles" ? getRdTaskExecutionOverview(taskId) : Promise.resolve(null)
      ]);
      if (requestSeq !== coreLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      if (taskResult.status === "fulfilled") {
        setTask((current) => mergeWorkbenchTask(taskResult.value, current));
        setTaskLoadError("");
      } else if (!silent) {
        toast.error(getErrorMessage(taskResult.reason, "刷新任务状态失败"));
      }
      if (view === "roles") {
        if (overviewResult.status === "fulfilled" && overviewResult.value) {
          setExecutionOverview(overviewResult.value);
          setExecutionOverviewError("");
        } else if (overviewResult.status === "rejected") {
          setExecutionOverviewError(getErrorMessage(overviewResult.reason, "刷新执行概览失败"));
        }
      }
    } finally {
      if (coreLoadInFlightRef.current === requestKey) coreLoadInFlightRef.current = "";
    }
  }, [taskId, view]);

  const loadExecutionOverview = useCallback(async () => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++overviewLoadSeqRef.current;
    try {
      const overview = await getRdTaskExecutionOverview(taskId);
      if (requestSeq !== overviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setExecutionOverview(overview);
      setExecutionOverviewError("");
    } catch (error) {
      if (requestSeq !== overviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setExecutionOverviewError(getErrorMessage(error, "加载执行概览失败"));
    }
  }, [taskId]);

  const loadAuditContent = useCallback(async () => {
    if (!taskId || auditContentLoadedRef.current === taskId) return;
    const requestToken = requestGuardRef.current.capture(taskId);
    try {
      const content = await getRdTaskAuditContent(taskId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      auditContentLoadedRef.current = taskId;
      setTask((current) => current && current.taskId === content.taskId
        ? {
          ...current,
          promptSnapshot: content.promptSnapshot,
          executionResultJson: content.executionResultJson,
          executionEvidence: content.executionEvidence
        }
        : current);
    } catch (error) {
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "加载任务审计内容失败"));
    }
  }, [taskId]);

  const loadRolePrompts = useCallback(async (signature: string) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestKey = `${requestToken.taskId}:${requestToken.generation}:${signature}`;
    if (rolePromptInFlightRef.current === requestKey) return;
    const requestSeq = ++rolePromptLoadSeqRef.current;
    rolePromptInFlightRef.current = requestKey;

    const expectedMap: Record<string, ExpectedStageIdentity> = {};
    (executionOverview?.stageRuns || []).forEach((stage) => {
      expectedMap[stage.stageRunId] = {
        stateSequence: stage.agentStateSequence,
        stateHash: stage.agentStateContentHash,
        injectionSequence: stage.agentLastInjectionSequence,
        injectedStateSequence: stage.agentLastInjectedStateSequence,
        injectedBlockHash: stage.agentLastInjectedBlockHash,
        promptHash: stage.agentLastInjectedPromptHash
      };
    });

    setLoadingRolePrompts(true);
    try {
      const response = await getRdTaskRolePrompts(taskId);
      if (requestSeq !== rolePromptLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;

      const freshness = evaluateRolePromptsFreshness(response.stagePrompts || [], expectedMap);
      if (freshness === "STALE_DISCARD") {
        return;
      }
      if (freshness === "CONSISTENCY_ERROR") {
        setRolePromptError("有效上下文校验失败，请查看阶段错误或联系管理员");
        return;
      }

      setRolePromptStages(response.stagePrompts || []);
      setRolePromptError("");
      loadedRolePromptSignatureRef.current = signature;

      if (freshness === "ACCEPT_AND_RECONCILE") {
        void refreshCore(true);
      }
    } catch (error) {
      if (requestSeq !== rolePromptLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setRolePromptError(getErrorMessage(error, "加载角色 Prompt 失败"));
    } finally {
      if (rolePromptInFlightRef.current === requestKey) rolePromptInFlightRef.current = "";
      if (requestSeq === rolePromptLoadSeqRef.current && requestGuardRef.current.isCurrent(requestToken)) {
        setLoadingRolePrompts(false);
      }
    }
  }, [executionOverview?.stageRuns, refreshCore, taskId]);

  const loadMaterialsData = useCallback(async () => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++materialLoadSeqRef.current;
    try {
      const nextMaterials = await getRdTaskMaterials(taskId);
      if (requestSeq !== materialLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setMaterials(nextMaterials);
      setPanelErrors((current) => ({ ...current, materials: "" }));
    } catch (error) {
      if (requestSeq !== materialLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setPanelErrors((current) => ({
        ...current,
        materials: getErrorMessage(error, "加载任务材料失败")
      }));
    }
  }, [taskId]);

  const loadHostVerificationsData = useCallback(async () => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++hostVerificationLoadSeqRef.current;
    setLoadingHostVerifications(true);
    try {
      const result = await getRdTaskHostVerifications(taskId);
      if (requestSeq !== hostVerificationLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setHostVerifications(result || { taskId, runs: [] });
      setHostVerificationsError("");
    } catch (error) {
      if (requestSeq !== hostVerificationLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setHostVerifications(null);
      setHostVerificationsError(getErrorMessage(error, "加载宿主验证记录失败"));
    } finally {
      if (requestSeq === hostVerificationLoadSeqRef.current && requestGuardRef.current.isCurrent(requestToken)) {
        setLoadingHostVerifications(false);
      }
    }
  }, [taskId]);

  const loadAuditedStateData = useCallback(async () => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++auditedStateLoadSeqRef.current;
    setLoadingAuditedState(true);
    try {
      const [nextState, nextRuns] = await Promise.all([
        getRdTaskAuditedState(taskId),
        getRdTaskAuditRuns(taskId)
      ]);
      if (requestSeq !== auditedStateLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setAuditedState(nextState);
      setAuditRuns(nextRuns);
      setAuditedStateError("");
      setPanelErrors((current) => ({ ...current, auditedState: "" }));
    } catch (error) {
      if (requestSeq !== auditedStateLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setAuditedState(null);
      setAuditRuns(null);
      const message = getErrorMessage(error, "加载已审计状态失败");
      setAuditedStateError(message);
      setPanelErrors((current) => ({ ...current, auditedState: message }));
    } finally {
      if (requestSeq === auditedStateLoadSeqRef.current && requestGuardRef.current.isCurrent(requestToken)) {
        setLoadingAuditedState(false);
      }
    }
  }, [taskId]);

  const loadRoleEvidenceData = useCallback(async (signature: string) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++roleEvidenceLoadSeqRef.current;
    if (loadedRoleEvidenceSignatureRef.current !== signature) setLoadingRoleEvidence(true);
    try {
      const [evidence, runs] = await Promise.allSettled([
        getRdTaskQaEvidence(taskId),
        getRetrievalRuns(taskId)
      ]);
      if (requestSeq !== roleEvidenceLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      const nextErrors: Record<string, string> = {};
      if (evidence.status === "fulfilled") {
        setQaEvidence(evidence.value || []);
        setQaEvidenceError("");
      } else {
        nextErrors.qaEvidence = getErrorMessage(evidence.reason, "加载 QA 证据失败");
        setQaEvidenceError(nextErrors.qaEvidence);
      }
      if (runs.status === "fulfilled") {
        setRetrievalRuns(runs.value || []);
        setRetrievalError("");
      } else {
        nextErrors.retrievalRuns = getErrorMessage(runs.reason, "加载检索运行失败");
        setRetrievalError(nextErrors.retrievalRuns);
      }
      setPanelErrors((current) => ({
        ...current,
        qaEvidence: "",
        retrievalRuns: "",
        ...nextErrors
      }));
      loadedRoleEvidenceSignatureRef.current = evidence.status === "fulfilled" && runs.status === "fulfilled"
        ? signature
        : null;
    } finally {
      if (requestSeq === roleEvidenceLoadSeqRef.current && requestGuardRef.current.isCurrent(requestToken)) {
        setLoadingRoleEvidence(false);
      }
    }
  }, [taskId]);

  const loadFailureRecoveryData = useCallback(async (taskSnapshot: RdTask) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++recoveryLoadSeqRef.current;
    const materialRequestSeq = ++materialLoadSeqRef.current;
    if (taskSnapshot.taskType !== "REQUIREMENT" || !canRetryRequirement(taskSnapshot)) {
      setFailureRecovery(null);
      setFailureRecoveryError("");
      setLoadingFailureRecovery(false);
      return;
    }
    setLoadingFailureRecovery(true);
    const [recovery, materialList] = await Promise.allSettled([
      getTaskFailureRecovery(taskId),
      getRdTaskMaterials(taskId)
    ]);
    const ragRuns = recovery.status === "fulfilled" && recovery.value.retryPoint.failurePhase === "RAG"
      ? await Promise.allSettled([getRetrievalRuns(taskId)]).then(([result]) => result)
      : null;
    if (
      requestSeq !== recoveryLoadSeqRef.current
      || materialRequestSeq !== materialLoadSeqRef.current
      || !requestGuardRef.current.isCurrent(requestToken)
    ) return;
    if (recovery.status === "fulfilled") {
      setFailureRecovery(recovery.value);
      if (ragRuns?.status === "rejected") {
        const message = getErrorMessage(ragRuns.reason, "无法定位 RAG 失败对应的检索运行");
        setFailureRecoveryError(message);
        setRetrievalError(message);
      } else if (ragRuns?.status === "fulfilled") {
        setRetrievalRuns(ragRuns.value || []);
        const failedRunId = recovery.value.retryPoint.failedRetrievalRunId;
        const matched = ragRuns.value.some((run) => run.runId === failedRunId);
        setFailureRecoveryError(matched ? "" : "RAG 失败记录未找到对应的检索运行，暂不能安全恢复");
        setRetrievalError("");
      } else {
        setFailureRecoveryError("");
      }
    } else {
      const message = getErrorMessage(recovery.reason, "加载失败恢复信息失败");
      setFailureRecoveryError(message);
      setPanelErrors((current) => ({ ...current, failureRecovery: message }));
    }
    if (materialList.status === "fulfilled") setMaterials(materialList.value || []);
    else setPanelErrors((current) => ({
      ...current,
      materials: getErrorMessage(materialList.reason, "加载任务材料失败")
    }));
    setLoadingFailureRecovery(false);
  }, [taskId]);

  const refreshSupportingData = useCallback(async (taskSnapshot: RdTask) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++roleEvidenceLoadSeqRef.current;
    const materialRequestSeq = ++materialLoadSeqRef.current;
    const recoveryRequestSeq = ++recoveryLoadSeqRef.current;
    const aiReviewRequestSeq = ++aiReviewLoadSeqRef.current;
    const hostVerificationSeq = ++hostVerificationLoadSeqRef.current;
    const failureRequest = taskSnapshot.taskType === "REQUIREMENT" && canRetryRequirement(taskSnapshot)
      ? getTaskFailureRecovery(taskId)
      : Promise.resolve(null);
    const [timeline, materialList, evidenceList, recovery, runs, reviews, verifications] = await Promise.allSettled([
      getRdTaskTimeline(taskId),
      getRdTaskMaterials(taskId),
      getRdTaskQaEvidence(taskId),
      failureRequest,
      getRetrievalRuns(taskId),
      getAiReviews(taskId),
      getRdTaskHostVerifications(taskId)
    ]);
    if (
      requestSeq !== roleEvidenceLoadSeqRef.current
      || materialRequestSeq !== materialLoadSeqRef.current
      || recoveryRequestSeq !== recoveryLoadSeqRef.current
      || aiReviewRequestSeq !== aiReviewLoadSeqRef.current
      || hostVerificationSeq !== hostVerificationLoadSeqRef.current
      || !requestGuardRef.current.isCurrent(requestToken)
    ) return;
    const nextErrors: Record<string, string> = {};
    if (timeline.status === "fulfilled") setEvents(timeline.value || []);
    else nextErrors.timeline = getErrorMessage(timeline.reason, "加载任务时间线失败");
    if (materialList.status === "fulfilled") setMaterials(materialList.value || []);
    else nextErrors.materials = getErrorMessage(materialList.reason, "加载任务材料失败");
    if (evidenceList.status === "fulfilled") {
      setQaEvidence(evidenceList.value || []);
      setQaEvidenceError("");
    } else {
      nextErrors.qaEvidence = getErrorMessage(evidenceList.reason, "加载 QA 证据失败");
      setQaEvidenceError(nextErrors.qaEvidence);
    }
    if (verifications.status === "fulfilled") {
      setHostVerifications(verifications.value || { taskId, runs: [] });
      setHostVerificationsError("");
    } else {
      setHostVerifications(null);
      setHostVerificationsError(getErrorMessage(verifications.reason, "加载宿主验证记录失败"));
    }
    if (recovery.status === "fulfilled") {
      setFailureRecovery(recovery.value);
      setFailureRecoveryError("");
    } else {
      nextErrors.failureRecovery = getErrorMessage(recovery.reason, "加载失败恢复信息失败");
      setFailureRecoveryError(nextErrors.failureRecovery);
    }
    if (runs.status === "fulfilled") {
      setRetrievalRuns(runs.value || []);
      setRetrievalError("");
    } else {
      nextErrors.retrievalRuns = getErrorMessage(runs.reason, "加载检索运行失败");
      setRetrievalError(nextErrors.retrievalRuns);
    }
    if (reviews.status === "fulfilled") setAiReviews(reviews.value || []);
    else nextErrors.aiReviews = getErrorMessage(reviews.reason, "加载 AI 复核失败");
    setPanelErrors((current) => ({
      ...current,
      timeline: "",
      materials: "",
      qaEvidence: "",
      failureRecovery: "",
      retrievalRuns: "",
      aiReviews: "",
      ...nextErrors
    }));
  }, [taskId]);

  const rolePromptSignature = roleStageSignature(executionOverview?.stageRuns || []);

  const refreshAll = useCallback(async (taskSnapshot: RdTask) => {
    const panelRefresh = view === "delivery"
      ? loadMaterialsData()
      : view === "audit"
        ? refreshSupportingData(taskSnapshot)
        : selectedRoleTab === "evidence"
          ? loadRoleEvidenceData(rolePromptSignature)
          : loadFailureRecoveryData(taskSnapshot);
    await Promise.all([refreshCore(true), loadHostVerificationsData(), loadAuditedStateData(), panelRefresh]);
  }, [loadAuditedStateData, loadFailureRecoveryData, loadHostVerificationsData, loadMaterialsData, loadRoleEvidenceData, refreshCore, refreshSupportingData, rolePromptSignature, selectedRoleTab, view]);

  useEffect(() => {
    requestGuardRef.current.beginTask(taskId);
    loadSeqRef.current += 1;
    rolePromptLoadSeqRef.current += 1;
    roleEvidenceLoadSeqRef.current += 1;
    materialLoadSeqRef.current += 1;
    recoveryLoadSeqRef.current += 1;
    aiReviewLoadSeqRef.current += 1;
    hostVerificationLoadSeqRef.current += 1;
    auditedStateLoadSeqRef.current += 1;
    coreLoadSeqRef.current += 1;
    retrievalDetailLoadSeqRef.current += 1;
    aiReviewDetailLoadSeqRef.current += 1;
    coreLoadInFlightRef.current = "";
    rolePromptInFlightRef.current = "";
    loadedRolePromptSignatureRef.current = null;
    loadedRoleEvidenceSignatureRef.current = null;
    auditContentLoadedRef.current = "";
    overviewLoadSeqRef.current += 1;
    setRolePromptStages([]);
    setExecutionOverview(null);
    setEvents([]);
    setMaterials([]);
    setQaEvidence([]);
    setQaEvidenceError("");
    setHostVerifications(null);
    setHostVerificationsError("");
    setLoadingHostVerifications(false);
    setAuditedState(null);
    setAuditRuns(null);
    setAuditedStateError("");
    setLoadingAuditedState(false);
    setRetrievalRuns([]);
    setAiReviews([]);
    setSelectedRetrievalRun(null);
    setRetrievalRunTimeline([]);
    setRetrievalRunArtifacts([]);
    setSelectedAiReview(null);
    setAiReviewTimeline([]);
    setAiReviewArtifacts([]);
    setFailureRecovery(null);
    setLoadingFailureRecovery(false);
    setLoadingRolePrompts(false);
    setLoadingRoleEvidence(false);
    setRolePromptError("");
    setRetrievalError("");
    setFailureRecoveryError("");
    setLoadingRetrievalDetail(false);
    setLoadingAiReviewDetail(false);
    setUploading(false);
    setSubmitting(false);
    setApproving(false);
    setStartingAiReview(false);
    setBudgetApprovalOpen(false);
    setApprovalMessage("");
    void loadInitial();
    void loadHostVerificationsData();
    void loadAuditedStateData();
    return () => {
      requestGuardRef.current.beginTask("");
      loadSeqRef.current += 1;
      rolePromptLoadSeqRef.current += 1;
      roleEvidenceLoadSeqRef.current += 1;
      materialLoadSeqRef.current += 1;
      recoveryLoadSeqRef.current += 1;
      aiReviewLoadSeqRef.current += 1;
      hostVerificationLoadSeqRef.current += 1;
      auditedStateLoadSeqRef.current += 1;
      coreLoadSeqRef.current += 1;
      overviewLoadSeqRef.current += 1;
      coreLoadInFlightRef.current = "";
      rolePromptInFlightRef.current = "";
      retrievalDetailLoadSeqRef.current += 1;
      aiReviewDetailLoadSeqRef.current += 1;
    };
  }, [loadAuditedStateData, loadHostVerificationsData, loadInitial, taskId]);

  useEffect(() => {
    if (view !== "roles") return;
    void loadExecutionOverview();
  }, [loadExecutionOverview, view]);

  useEffect(() => {
    if (view !== "delivery" && view !== "audit") return;
    void loadAuditContent();
  }, [loadAuditContent, view]);

  useEffect(() => {
    if (view !== "roles" || selectedRoleTab !== "evidence") return;
    if (!executionOverview || rolePromptSignature === loadedRolePromptSignatureRef.current) return;
    void loadRolePrompts(rolePromptSignature);
  }, [executionOverview, loadRolePrompts, rolePromptSignature, selectedRoleTab, view]);

  useEffect(() => {
    if (view !== "roles" || selectedRoleTab !== "evidence" || !rolePromptError) return;
    const stop = scheduleActiveDetailRefresh(() => loadRolePrompts(rolePromptSignature));
    return stop;
  }, [loadRolePrompts, rolePromptError, rolePromptSignature, selectedRoleTab, view]);

  useEffect(() => {
    if (!task) return;
    if (view === "delivery") {
      void loadMaterialsData();
      return;
    }
    if (view === "audit") {
      void refreshSupportingData(task);
      return;
    }
    if (selectedRoleTab === "evidence") {
      if (rolePromptSignature !== loadedRoleEvidenceSignatureRef.current) {
        void loadRoleEvidenceData(rolePromptSignature);
      }
      return;
    }
    if (selectedRoleTab === "issues") {
      void loadFailureRecoveryData(task);
    }
  }, [
    loadFailureRecoveryData,
    loadMaterialsData,
    loadRoleEvidenceData,
    refreshSupportingData,
    rolePromptSignature,
    selectedRoleTab,
    task?.status,
    task?.taskType,
    taskId,
    view
  ]);

  const hasActiveEvidenceRetrieval = retrievalRuns.some((run) => isRetrievalRunActive(run.status));
  const shouldPollRoleEvidence = hasActiveEvidenceRetrieval
    || Boolean(task && shouldPollTask(task))
    || Boolean(qaEvidenceError || retrievalError);

  useEffect(() => {
    if (view !== "roles" || selectedRoleTab !== "evidence" || !shouldPollRoleEvidence) return;
    const stop = scheduleActiveDetailRefresh(() => loadRoleEvidenceData(rolePromptSignature));
    return stop;
  }, [loadRoleEvidenceData, rolePromptSignature, selectedRoleTab, shouldPollRoleEvidence, view]);

  useEffect(() => {
    if (!task || !shouldPollTask(task)) {
      return;
    }
    const timer = window.setInterval(() => {
      void refreshCore(true);
    }, 2000);
    return () => window.clearInterval(timer);
  }, [refreshCore, task?.status, task?.paused]);

  useEffect(() => {
    if (!selectedRetrievalRun || !isRetrievalRunActive(selectedRetrievalRun.status)) {
      return;
    }
    const runId = selectedRetrievalRun.runId;
    const requestSeq = retrievalDetailLoadSeqRef.current;
    let cancelled = false;
    const stop = scheduleActiveDetailRefresh(async () => {
      const requestToken = requestGuardRef.current.capture(taskId);
      try {
        const [detail, timeline, artifacts] = await Promise.all([
          getRetrievalRun(runId),
          getRetrievalRunTimeline(runId),
          getRetrievalRunArtifacts(runId)
        ]);
        if (
          cancelled
          || requestSeq !== retrievalDetailLoadSeqRef.current
          || !requestGuardRef.current.isCurrent(requestToken)
        ) return;
        setSelectedRetrievalRun((current) => current?.runId === runId ? detail : current);
        setRetrievalRunTimeline(timeline || []);
        setRetrievalRunArtifacts(artifacts || []);
        setRetrievalRuns((current) => current.map((item) => item.runId === detail.runId ? detail : item));
      } catch {
        // The next serialized detail refresh retries transient failures.
      }
    });
    return () => {
      cancelled = true;
      stop();
    };
  }, [selectedRetrievalRun?.runId, selectedRetrievalRun?.status, taskId]);

  useEffect(() => {
    if (!selectedAiReview || !isAiReviewActive(selectedAiReview.status)) return;
    const runId = selectedAiReview.runId;
    const requestSeq = aiReviewDetailLoadSeqRef.current;
    let cancelled = false;
    const stop = scheduleActiveDetailRefresh(async () => {
      const requestToken = requestGuardRef.current.capture(taskId);
      try {
        const [detail, timeline, artifacts] = await Promise.all([
          getAiReview(runId), getAiReviewTimeline(runId), getAiReviewArtifacts(runId)
        ]);
        if (
          cancelled
          || requestSeq !== aiReviewDetailLoadSeqRef.current
          || !requestGuardRef.current.isCurrent(requestToken)
        ) return;
        setSelectedAiReview((current) => current?.runId === runId ? detail : current);
        setAiReviewTimeline(timeline || []);
        setAiReviewArtifacts(artifacts || []);
        setAiReviews((current) => current.map((item) => item.runId === detail.runId ? detail : item));
      } catch {
        // A later poll retries transient failures; terminal states stop polling.
      }
    });
    return () => {
      cancelled = true;
      stop();
    };
  }, [selectedAiReview?.runId, selectedAiReview?.status, taskId]);

  const handleTogglePause = async () => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    invalidateCoreLoad();
    try {
      const updated = taskSnapshot.paused
        ? await resumeRdTask(taskSnapshot.taskId)
        : await pauseRdTask(taskSnapshot.taskId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      setTask(updated);
      await refreshAll(updated);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      toast.success(updated.paused ? "已暂停" : "已恢复并重新触发");
    } catch (error) {
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "操作失败"));
    }
  };

  const handleSubmitTask = async () => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    invalidateCoreLoad();
    setSubmitting(true);
    try {
      const updated = await submitRdTask(taskSnapshot.taskId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      setTask(updated);
      await refreshAll(updated);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      toast.success(updated.pullRequestUrl ? "执行完成，已生成 PR" : "执行已提交");
    } catch (error) {
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "执行失败"));
    } finally {
      if (requestGuardRef.current.isCurrent(requestToken)) setSubmitting(false);
    }
  };

  const handleApproveTask = async () => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    invalidateCoreLoad();
    setApproving(true);
    try {
      const updated = await approveRdTask(
        taskSnapshot.taskId,
        approvalMessage.trim() || "管理台审批通过，继续执行需求交付"
      );
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      setTask(updated);
      await refreshAll(updated);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      setBudgetApprovalOpen(false);
      setApprovalMessage("");
      toast.success(updated.pullRequestUrl ? "审批通过，已生成 PR" : "审批通过，已继续执行");
    } catch (error) {
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "审批失败"));
    } finally {
      if (requestGuardRef.current.isCurrent(requestToken)) setApproving(false);
    }
  };

  const handleOpenFailureRecovery = async () => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    let recoverySnapshot = failureRecovery;
    if (!recoverySnapshot) {
      const requestSeq = ++recoveryLoadSeqRef.current;
      setLoadingFailureRecovery(true);
      try {
        recoverySnapshot = await getTaskFailureRecovery(taskSnapshot.taskId);
        if (
          requestSeq !== recoveryLoadSeqRef.current
          || !requestGuardRef.current.isCurrent(requestToken)
        ) return;
        setFailureRecovery(recoverySnapshot);
        setFailureRecoveryError("");
      } catch (error) {
        if (
          requestSeq !== recoveryLoadSeqRef.current
          || !requestGuardRef.current.isCurrent(requestToken)
        ) return;
        const message = getErrorMessage(error, "加载失败恢复信息失败");
        setFailureRecoveryError(message);
        toast.error(message);
        return;
      } finally {
        if (
          requestSeq === recoveryLoadSeqRef.current
          && requestGuardRef.current.isCurrent(requestToken)
        ) setLoadingFailureRecovery(false);
      }
    }
    if (!recoverySnapshot || !requestGuardRef.current.isCurrent(requestToken)) return;
    const roleBound = !isTaskLevelRecovery(recoverySnapshot);
    updateWorkspaceQuery(roleBound ? {
      view: "roles",
      role: recoverySnapshot.retryPoint.retryFromRole,
      attempt: recoverySnapshot.failedAttemptNo,
      tab: "issues"
    } : { view: "audit" });
  };

  const handleRetryRetrieval = async (runId: string) => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    const requestSeq = ++roleEvidenceLoadSeqRef.current;
    try {
      const retry = await retryRetrievalRun(runId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      const nextRuns = await getRetrievalRuns(taskSnapshot.taskId);
      if (requestSeq !== roleEvidenceLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setRetrievalRuns(nextRuns);
      setRetrievalError("");
      toast.success(`已创建检索重试 attempt ${retry.attemptNo}`);
    } catch (error) {
      if (requestSeq !== roleEvidenceLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "无法重试检索"));
    }
  };

  const handleCancelRetrieval = async (runId: string) => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    const requestSeq = ++roleEvidenceLoadSeqRef.current;
    try {
      await cancelRetrievalRun(runId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      const nextRuns = await getRetrievalRuns(taskSnapshot.taskId);
      if (requestSeq !== roleEvidenceLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setRetrievalRuns(nextRuns);
      setRetrievalError("");
      toast.success("检索已取消");
    } catch (error) {
      if (requestSeq !== roleEvidenceLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "无法取消检索"));
    }
  };

  const handleStartAiReview = async () => {
    if (!task || startingAiReview) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    const requestSeq = ++aiReviewLoadSeqRef.current;
    setStartingAiReview(true);
    try {
      const review = await startAiReview(taskSnapshot.taskId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      const nextReviews = await getAiReviews(taskSnapshot.taskId);
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setAiReviews(nextReviews);
      await inspectAiReview(review);
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.success(`AI 复核 attempt ${review.attemptNo} 已完成`);
    } catch (error) {
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "无法发起 AI 复核"));
    } finally {
      if (requestGuardRef.current.isCurrent(requestToken)) setStartingAiReview(false);
    }
  };

  const handleRetryAiReview = async (runId: string) => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    const requestSeq = ++aiReviewLoadSeqRef.current;
    try {
      const retry = await retryAiReview(runId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      const nextReviews = await getAiReviews(taskSnapshot.taskId);
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setAiReviews(nextReviews);
      toast.success(`已创建 AI 复核恢复 checkpoint ${retry.checkpointId}`);
    } catch (error) {
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "无法重试 AI 复核"));
    }
  };

  const handleCancelAiReview = async (runId: string) => {
    if (!task) return;
    const taskSnapshot = task;
    const requestToken = requestGuardRef.current.capture(taskSnapshot.taskId);
    if (!requestGuardRef.current.isCurrent(requestToken)) return;
    const requestSeq = ++aiReviewLoadSeqRef.current;
    try {
      await cancelAiReview(runId);
      if (!requestGuardRef.current.isCurrent(requestToken)) return;
      const nextReviews = await getAiReviews(taskSnapshot.taskId);
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      setAiReviews(nextReviews);
      toast.success("AI 复核已取消");
    } catch (error) {
      if (requestSeq !== aiReviewLoadSeqRef.current || !requestGuardRef.current.isCurrent(requestToken)) return;
      toast.error(getErrorMessage(error, "无法取消 AI 复核"));
    }
  };

  const inspectRetrievalRun = async (run: RetrievalRun) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++retrievalDetailLoadSeqRef.current;
    setSelectedRetrievalRun(run);
    setRetrievalRunTimeline([]);
    setRetrievalRunArtifacts([]);
    setLoadingRetrievalDetail(true);
    try {
      const [timeline, artifacts] = await Promise.all([
        getRetrievalRunTimeline(run.runId),
        getRetrievalRunArtifacts(run.runId)
      ]);
      if (
        requestSeq !== retrievalDetailLoadSeqRef.current
        || !requestGuardRef.current.isCurrent(requestToken)
      ) return;
      setRetrievalRunTimeline(timeline || []);
      setRetrievalRunArtifacts(artifacts || []);
    } catch (error) {
      if (
        requestSeq !== retrievalDetailLoadSeqRef.current
        || !requestGuardRef.current.isCurrent(requestToken)
      ) return;
      setRetrievalRunTimeline([]);
      setRetrievalRunArtifacts([]);
      toast.error(getErrorMessage(error, "加载检索详情失败"));
    } finally {
      if (
        requestSeq === retrievalDetailLoadSeqRef.current
        && requestGuardRef.current.isCurrent(requestToken)
      ) setLoadingRetrievalDetail(false);
    }
  };

  const inspectAiReview = async (run: AiReviewRun) => {
    const requestToken = requestGuardRef.current.capture(taskId);
    const requestSeq = ++aiReviewDetailLoadSeqRef.current;
    setSelectedAiReview(run);
    setAiReviewTimeline([]);
    setAiReviewArtifacts([]);
    setLoadingAiReviewDetail(true);
    try {
      const [timeline, artifacts] = await Promise.all([
        getAiReviewTimeline(run.runId), getAiReviewArtifacts(run.runId)
      ]);
      if (
        requestSeq !== aiReviewDetailLoadSeqRef.current
        || !requestGuardRef.current.isCurrent(requestToken)
      ) return;
      setAiReviewTimeline(timeline || []);
      setAiReviewArtifacts(artifacts || []);
    } catch (error) {
      if (
        requestSeq !== aiReviewDetailLoadSeqRef.current
        || !requestGuardRef.current.isCurrent(requestToken)
      ) return;
      toast.error(getErrorMessage(error, "加载 AI 复核详情失败"));
    } finally {
      if (
        requestSeq === aiReviewDetailLoadSeqRef.current
        && requestGuardRef.current.isCurrent(requestToken)
      ) setLoadingAiReviewDetail(false);
    }
  };

  if (loading) {
    return (
      <div className="admin-page">
        <div className="py-12 text-center text-muted-foreground">加载中...</div>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="admin-page">
        <div className="mx-auto max-w-xl border border-rose-200 bg-white px-5 py-8 text-center">
          <div className="text-sm font-semibold text-rose-900">无法加载任务</div>
          <p className="mt-2 text-sm text-slate-600">{taskLoadError || "任务不存在或已不可访问。"}</p>
          <Button className="mt-4" variant="outline" onClick={() => void loadInitial()}>重新加载</Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6 pb-12">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-slate-900">{task?.title || "任务详情"}</h1>
            {task ? <Badge className={STATUS_BADGE_CLASS[task.status]}>{task.status}</Badge> : null}
            {task?.taskType ? <Badge variant="outline">{task.taskType}</Badge> : null}
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
            <span>任务 ID: <code className="font-mono">{task?.taskId || taskId}</code></span>
            <span>创建时间: {task?.createTimeEpochMillis ? <RelativeTime value={new Date(task.createTimeEpochMillis).toISOString()} /> : "-"}</span>
            <span>更新时间: {task?.updateTimeEpochMillis ? <RelativeTime value={new Date(task.updateTimeEpochMillis).toISOString()} /> : "-"}</span>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => navigate(-1)}>
            <ChevronLeft className="mr-1.5 h-4 w-4" />
            返回列表
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => task && void refreshAll(task)}
            disabled={loading}
          >
            <RotateCcw className="mr-1.5 h-4 w-4" />
            刷新
          </Button>
          <Button variant="outline" size="sm" onClick={handleTogglePause}>
            {task?.paused ? (
              <>
                <Play className="mr-1.5 h-4 w-4 text-emerald-600" />
                恢复任务
              </>
            ) : (
              <>
                <Pause className="mr-1.5 h-4 w-4 text-amber-600" />
                暂停任务
              </>
            )}
          </Button>
          {task && task.taskType === "REQUIREMENT" && canRetryRequirement(task) ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => void handleOpenFailureRecovery()}
              disabled={loadingFailureRecovery}
            >
              <RotateCcw className="mr-1.5 h-4 w-4" />
              从失败阶段重试
            </Button>
          ) : null}
        </div>
      </div>

      {taskLoadError ? (
        <div className="rounded-lg border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">
          {taskLoadError}
        </div>
      ) : null}

      {task ? (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-slate-200 bg-slate-50/50 p-4">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-medium text-slate-600">快速操作：</span>
              {task.taskType === "REQUIREMENT" && canSubmitRequirementTask(task) ? (
                <Button size="sm" onClick={handleSubmitTask} disabled={submitting}>
                  <Play className="mr-1.5 h-4 w-4 text-emerald-600" />
                  {submitting ? "提交中..." : "开始推进"}
                </Button>
              ) : null}
              {task.status === "AWAITING_BUDGET_APPROVAL" ? (
                <Button size="sm" onClick={() => setBudgetApprovalOpen(true)} disabled={approving}>
                  <CheckCircle2 className="mr-1.5 h-4 w-4 text-emerald-600" />
                  审批预算
                </Button>
              ) : null}
            </div>
          </div>

          <TaskSummaryBand task={task} overview={executionOverview} auditedState={auditedState} />
          <TaskViewNavigation view={view} onChange={(nextView) => updateWorkspaceQuery({ view: nextView })} />

          {view === "roles" ? (
            <div className="space-y-6">
              <TaskRoleWorkbench
                task={task}
                overview={executionOverview}
                overviewError={executionOverviewError}
                promptStages={rolePromptStages}
                promptLoading={loadingRolePrompts}
                promptError={rolePromptError}
                evidenceLoading={loadingRoleEvidence}
                qaEvidence={qaEvidence}
                qaEvidenceError={qaEvidenceError}
                retrievalRuns={retrievalRuns}
                retrievalError={retrievalError}
                materials={materials}
                failureRecovery={failureRecovery}
                failureRecoveryLoading={loadingFailureRecovery}
                failureRecoveryError={failureRecoveryError}
                hostVerifications={hostVerifications}
                selectedRole={selectedRole}
                selectedAttemptNo={selectedAttemptNo}
                selectedTab={selectedRoleTab}
                onSelectionChange={(role, attemptNo) => updateWorkspaceQuery({ role, attempt: attemptNo })}
                onTabChange={(tab) => updateWorkspaceQuery({ tab })}
                onInspectRetrievalRun={inspectRetrievalRun}
                onRefresh={() => refreshAll(task)}
                captureTaskActionGuard={captureTaskActionGuard}
              />
              <HostVerificationCard
                taskId={task.taskId}
                verificationList={hostVerifications}
                loading={loadingHostVerifications}
                error={hostVerificationsError}
                onSelectCodingAttempt={(attemptNo) => updateWorkspaceQuery({ role: "CODING_AGENT", attempt: attemptNo })}
              />
            </div>
          ) : null}

          {view === "delivery" ? <PanelErrorsNotice errors={pickPanelErrors(panelErrors, ["materials"])} /> : null}
          {view === "audit" ? <PanelErrorsNotice errors={pickPanelErrors(panelErrors, ["timeline", "qaEvidence", "retrievalRuns", "aiReviews", "failureRecovery", "auditedState"])} /> : null}

          {view === "audit" ? (
            <AuditedTaskStateCard
              state={auditedState}
              runs={auditRuns}
              loading={loadingAuditedState}
              error={auditedStateError}
            />
          ) : null}

          {view === "audit" ? (
            <HostVerificationCard
              taskId={task.taskId}
              verificationList={hostVerifications}
              loading={loadingHostVerifications}
              error={hostVerificationsError}
              onSelectCodingAttempt={(attemptNo) => updateWorkspaceQuery({ view: "roles", role: "CODING_AGENT", attempt: attemptNo })}
            />
          ) : null}

          {view === "audit" && failureRecovery && isTaskLevelRecovery(failureRecovery) ? (
            <TaskFailureRecoveryWorkbench
              key={task.taskId}
              task={task}
              materials={materials}
              snapshot={failureRecovery}
              loading={loadingFailureRecovery}
              error={failureRecoveryError}
              onRefresh={() => refreshAll(task)}
              captureTaskActionGuard={captureTaskActionGuard}
            />
          ) : null}

          {view === "audit" ? <RetrievalRunsCard
            runs={retrievalRuns}
            error={retrievalError}
            onRetry={handleRetryRetrieval}
            onCancel={handleCancelRetrieval}
            onInspect={inspectRetrievalRun}
          /> : null}

          {view === "audit" && qaEvidence.length > 0 ? <QaEvidenceCard evidence={qaEvidence} /> : null}

          <RetrievalRunDetailDialog
            run={selectedRetrievalRun}
            timeline={retrievalRunTimeline}
            artifacts={retrievalRunArtifacts}
            loading={loadingRetrievalDetail}
            onOpenChange={(open) => {
              if (!open) {
                retrievalDetailLoadSeqRef.current += 1;
                setSelectedRetrievalRun(null);
                setRetrievalRunTimeline([]);
                setRetrievalRunArtifacts([]);
                setLoadingRetrievalDetail(false);
              }
            }}
          />

          {view === "audit" && task.taskType === "REQUIREMENT" ? (
            <AiReviewRunsCard
              runs={aiReviews}
              starting={startingAiReview}
              onStart={handleStartAiReview}
              onInspect={inspectAiReview}
              onRetry={handleRetryAiReview}
              onCancel={handleCancelAiReview}
            />
          ) : null}

          <AiReviewDetailDialog
            run={selectedAiReview}
            timeline={aiReviewTimeline}
            artifacts={aiReviewArtifacts}
            loading={loadingAiReviewDetail}
            onOpenChange={(open) => {
              if (!open) {
                aiReviewDetailLoadSeqRef.current += 1;
                setSelectedAiReview(null);
                setAiReviewTimeline([]);
                setAiReviewArtifacts([]);
                setLoadingAiReviewDetail(false);
              }
            }}
          />

          {view === "delivery" && task.taskType === "REQUIREMENT" ? (
            <Card>
              <CardHeader>
                <CardTitle>需求交付信息</CardTitle>
                <CardDescription>需求任务的仓库、分支与验收输入</CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-1 gap-4 text-sm md:grid-cols-2">
                  <InfoField
                    label="仓库"
                    value={
                      task.repositoryUrl ? (
                        <a
                          href={task.repositoryUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="break-all text-primary underline"
                        >
                          {task.repositoryUrl}
                        </a>
                      ) : (
                        "-"
                      )
                    }
                  />
                  <InfoField label="基准分支" value={task.baseBranch || "-"} mono />
                  <InfoField label="工作分支" value={task.workBranch || "-"} mono />
                  <InfoField label="来源" value={task.sourceType || "ADMIN"} />
                </div>
                <div>
                  <div className="mb-1 text-xs text-muted-foreground">预期结果</div>
                  <div className="whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-sm text-slate-700">
                    {task.expectedResult || "-"}
                  </div>
                </div>
                <div>
                  <div className="mb-1 text-xs text-muted-foreground">验收标准</div>
                  <div className="space-y-2 rounded-lg bg-slate-50 p-3 text-sm text-slate-700">
                    {parseCriteria(task.acceptanceCriteriaJson).length > 0 ? (
                      parseCriteria(task.acceptanceCriteriaJson).map((item, index) => (
                        <div key={`${item}-${index}`}>{index + 1}. {item}</div>
                      ))
                    ) : (
                      "-"
                    )}
                  </div>
                </div>
              </CardContent>
            </Card>
          ) : null}

          {view === "delivery" && hasExecutionEvidence(task) ? (
            <Card>
              <CardHeader>
                <CardTitle>执行结果与 PR</CardTitle>
                <CardDescription>自动编码、验证和代码评审请求的输出摘要</CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-1 gap-4 text-sm md:grid-cols-3">
                  <InfoField label="执行摘要" value={task.executionEvidence?.summary || "-"} />
                  <InfoField label="测试状态" value={task.executionEvidence?.testStatus || "-"} />
                  <InfoField label="风险等级" value={task.executionEvidence?.riskLevel || "-"} />
                </div>
                {task.pullRequestUrl || task.executionEvidence?.pullRequestUrl ? (
                  <a
                    href={task.pullRequestUrl || task.executionEvidence?.pullRequestUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-2 text-sm font-medium text-primary underline"
                  >
                    <GitPullRequest className="h-4 w-4" />
                    打开 PR
                  </a>
                ) : null}
                {task.executionEvidence?.prBody ? (
                  <div>
                    <div className="mb-1 text-xs text-muted-foreground">改动介绍</div>
                    <pre className="max-h-[260px] overflow-auto whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-xs leading-relaxed text-slate-700">
                      {task.executionEvidence.prBody}
                    </pre>
                  </div>
                ) : null}
                {task.executionEvidence?.changedFiles?.length ? (
                  <div>
                    <div className="mb-1 text-xs text-muted-foreground">变更文件</div>
                    <div className="flex flex-wrap gap-2">
                      {task.executionEvidence.changedFiles.map((file) => (
                        <code key={file} className="rounded-md bg-slate-100 px-2 py-1 text-xs text-slate-700">
                          {file}
                        </code>
                      ))}
                    </div>
                  </div>
                ) : null}
                {task.executionEvidence?.testCommands?.length ? (
                  <div>
                    <div className="mb-1 text-xs text-muted-foreground">测试命令</div>
                    <div className="space-y-2">
                      {task.executionEvidence.testCommands.map((command) => (
                        <code key={command} className="block rounded-md bg-slate-950 px-3 py-2 text-xs text-slate-100">
                          {command}
                        </code>
                      ))}
                    </div>
                  </div>
                ) : null}
              </CardContent>
            </Card>
          ) : null}

          {view === "delivery" ? <Card>
          <CardHeader className="flex flex-row items-start justify-between gap-3">
            <div>
              <CardTitle>任务材料</CardTitle>
              <CardDescription>输入文档、截图与安全预览</CardDescription>
            </div>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={uploading}
              onClick={() => document.getElementById("task-screenshot-upload")?.click()}
            >
              <Upload className="mr-2 h-4 w-4" />
              {uploading ? "上传中" : "补充图片"}
            </Button>
            <input
              id="task-screenshot-upload"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              multiple
              className="sr-only"
              onChange={(event) => {
                void uploadScreenshots(event.target.files);
                event.target.value = "";
              }}
            />
          </CardHeader>
          <CardContent>
            {materials.length === 0 ? (
              <div className="py-6 text-center text-sm text-muted-foreground">暂无材料</div>
            ) : <div className="divide-y divide-slate-200 border-y border-slate-200">{materials.map((material) => (
              <article key={material.materialId} className="py-3">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex min-w-0 items-center gap-2 text-sm font-medium text-slate-800">
                      {material.mimeType.startsWith("image/") ? <ImageIcon className="h-4 w-4 shrink-0" /> : null}
                      <span className="truncate">{material.title || material.materialId}</span>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-2 text-xs text-muted-foreground">
                      <span>{material.sourceType}</span><span>{material.materialType}</span>
                      <span className="max-w-full truncate font-mono" title={material.contentHash}>{material.contentHash}</span>
                    </div>
                  </div>
                  <Button asChild variant="outline" size="icon" title="下载原文件">
                    <a href={taskMaterialContentUrl(task.taskId, material.materialId)} download>
                      <Download className="h-4 w-4" />
                    </a>
                  </Button>
                </div>
                <details className="mt-2 border border-slate-200 bg-slate-50/50">
                  <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-slate-600">查看材料预览</summary>
                  <div className="border-t border-slate-200 p-3">
                    {material.mimeType.startsWith("image/") ? (
                      <img
                        src={taskMaterialContentUrl(task.taskId, material.materialId)}
                        alt={material.title || "任务截图"}
                        className="max-h-[360px] w-auto max-w-full border border-slate-200 object-contain"
                        loading="lazy"
                      />
                    ) : (
                      <pre className="max-h-[220px] overflow-auto whitespace-pre-wrap text-xs leading-relaxed text-slate-600">
                        {material.contentPreview || "-"}
                      </pre>
                    )}
                  </div>
                </details>
              </article>
            ))}</div>}
          </CardContent>
        </Card> : null}

        {view === "audit" ? <Card>
          <CardHeader>
            <CardTitle>全链路时间线</CardTitle>
            <CardDescription>任务经历的状态与每步耗时</CardDescription>
          </CardHeader>
          <CardContent>
            {events.length === 0 ? (
              <div className="py-8 text-center text-muted-foreground">暂无状态事件</div>
            ) : (
              <ol className="relative space-y-6 border-l border-slate-200 pl-6">
                {events.map((event, index) => (
                  <li key={`${event.id}-${index}`} className="relative">
                    <span
                      className={cn(
                        "absolute -left-[31px] top-1 h-3 w-3 rounded-full ring-4 ring-white",
                        EVENT_DOT_TONE[event.status] || "bg-slate-300"
                      )}
                    />
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="outline" className={STATUS_BADGE_CLASS[event.status] || ""}>
                        {event.status}
                      </Badge>
                      <span className="text-xs text-muted-foreground">
                        {event.trigger} · {formatDuration(event.durationMillis)}
                      </span>
                    </div>
                    <div className="mt-1 text-sm text-slate-700">
                      {event.title || event.message || "无说明"}
                    </div>
                    {event.message ? (
                      <div className="mt-0.5 text-xs text-muted-foreground">{event.message}</div>
                    ) : null}
                    <div className="mt-1 text-xs text-muted-foreground">
                      <RelativeTime value={new Date(event.enteredAtEpochMillis).toISOString()} />
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </CardContent>
        </Card> : null}

        {view === "audit" && task.promptSnapshot ? (
          <Card>
            <CardHeader>
              <CardTitle>任务执行基线 Prompt</CardTitle>
              <CardDescription>用于任务级审计，不等同于任一角色实际发送给模型的 Prompt。</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="max-h-[420px] overflow-auto rounded-md border border-slate-200 bg-slate-50/70 p-4 text-sm leading-relaxed text-slate-700">
                <Suspense fallback={<div className="py-6 text-center text-sm text-muted-foreground">正在渲染 Markdown...</div>}>
                  <MarkdownRenderer content={task.promptSnapshot} />
                </Suspense>
              </div>
            </CardContent>
          </Card>
        ) : null}
        <Dialog open={budgetApprovalOpen} onOpenChange={setBudgetApprovalOpen}>
          <DialogContent className="sm:max-w-[520px]">
            <DialogHeader>
              <DialogTitle>确认预算审批</DialogTitle>
              <DialogDescription>审批后将从已完成的需求评审阶段继续，不会重新执行评审。</DialogDescription>
            </DialogHeader>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <InfoField label="预估 Token" value={formatTokens(executionOverview?.tokenBudget.estimatedTotalTokens)} />
              <InfoField label="有效额度" value={executionOverview?.tokenBudget.effectiveTokenBudget ? formatTokens(executionOverview.tokenBudget.effectiveTokenBudget) : "不限制"} />
              <InfoField label="超出量" value={executionOverview?.tokenBudget.effectiveTokenBudget ? formatTokens(Math.max(0, (executionOverview.tokenBudget.estimatedTotalTokens || 0) - executionOverview.tokenBudget.effectiveTokenBudget)) : "0"} />
              <InfoField label="置信度" value={executionOverview?.tokenBudget.confidence || "-"} />
            </div>
            <div>
              <label className="mb-2 block text-sm font-medium">审批说明</label>
              <textarea value={approvalMessage} onChange={(event) => setApprovalMessage(event.target.value)} className="min-h-[88px] w-full resize-y rounded-md border border-slate-200 px-3 py-2 text-sm" />
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setBudgetApprovalOpen(false)} disabled={approving}>取消</Button>
              <Button onClick={() => void handleApproveTask()} disabled={approving}>{approving ? "审批中..." : "审批并继续"}</Button>
            </div>
          </DialogContent>
        </Dialog>
      </div>
    ) : null}
  </div>
  );
}

function TaskSummaryBand({
  task,
  overview,
  auditedState
}: {
  task: RdTask;
  overview: RdTaskExecutionOverview | null;
  auditedState: AuditedTaskState | null;
}) {
  const coverage = auditedCoverage(auditedState?.records);
  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm sm:p-5" aria-label="任务摘要">
      <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline" className={cn("font-medium", STATUS_BADGE_CLASS[task.status] || "")}>{task.status}</Badge>
            {task.paused ? <Badge variant="outline" className="border-amber-300 bg-amber-50 font-medium text-amber-700">已暂停</Badge> : null}
            <Badge variant="outline" className="border-slate-200 bg-slate-50 font-normal text-slate-600">
              {task.taskType === "REQUIREMENT" ? "需求交付" : "Bug 修复"}
            </Badge>
            {auditedState?.present ? (
              <Badge variant="outline" className="border-teal-200 bg-teal-50 font-medium text-teal-800">
                {coverage.label}
              </Badge>
            ) : null}
            <span className="text-xs text-slate-300">·</span>
            <span className="font-mono text-xs font-semibold text-slate-600">{task.priority || "未设置优先级"}</span>
          </div>
          <div className="mt-2.5 flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-slate-600">
            <span className="flex items-center gap-1.5">
              <span className="text-slate-400">项目</span>
              <strong className="font-semibold text-slate-900">{task.projectName || task.projectKey || "-"}</strong>
            </span>
            <span className="flex items-center gap-1.5 min-w-0">
              <span className="text-slate-400">任务 ID</span>
              <code className="break-all font-mono text-slate-800">{task.taskId}</code>
            </span>
            <span className="flex items-center gap-1.5">
              <span className="text-slate-400">更新于</span>
              <RelativeTime value={new Date(task.updateTimeEpochMillis).toISOString()} />
            </span>
          </div>
        </div>
        <dl className="grid grid-cols-2 gap-3 rounded-lg border border-slate-100 bg-slate-50/70 p-3 text-xs sm:grid-cols-4 xl:shrink-0">
          <SummaryMetric label="当前角色" value={ROLE_LABEL[overview?.currentRole || ""] || overview?.currentRole || "-"} />
          <SummaryMetric label="阶段状态" value={STAGE_STATUS_LABEL[overview?.currentStageStatus || ""] || overview?.currentStageStatus || "-"} />
          <SummaryMetric label="累计 Token" value={formatTokens(overview?.tokenBudget.actualAccumulatedTokens)} />
          <SummaryMetric label="任务耗时" value={formatDuration(overview?.elapsedMillis)} />
        </dl>
      </div>
      {(() => {
        const notice = taskStatusNotice(task);
        if (notice.kind === "recovery") {
          return (
            <div className="mt-4 flex items-start gap-2.5 rounded-lg border border-sky-200 bg-sky-50/90 p-3 text-sm text-sky-950">
              <span className="font-semibold shrink-0">恢复中：</span>
              <span className="break-words">{notice.message}</span>
            </div>
          );
        }
        if (notice.kind === "blocker") {
          return (
            <div className="mt-4 flex items-start gap-2.5 rounded-lg border border-rose-200 bg-rose-50/90 p-3 text-sm text-rose-900">
              <span className="font-semibold shrink-0">当前阻断：</span>
              <span className="break-words">{notice.message}</span>
            </div>
          );
        }
        return null;
      })()}
    </section>
  );
}

function SummaryMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] font-medium text-slate-500">{label}</dt>
      <dd className="mt-0.5 truncate font-semibold text-slate-900" title={value}>{value}</dd>
    </div>
  );
}

function TaskViewNavigation({ view, onChange }: { view: TaskDetailView; onChange: (view: TaskDetailView) => void }) {
  const items: Array<{ value: TaskDetailView; label: string; icon: typeof UsersRound }> = [
    { value: "roles", label: "角色工作台", icon: UsersRound },
    { value: "delivery", label: "任务输入与交付", icon: FileInput },
    { value: "audit", label: "任务审计", icon: History }
  ];
  return (
    <nav className="grid grid-cols-3 rounded-lg border border-slate-200 bg-slate-100/80 p-1 shadow-inner" aria-label="任务详情视图" role="tablist">
      {items.map((item) => {
        const Icon = item.icon;
        const active = item.value === view;
        return (
          <button
            key={item.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(item.value)}
            className={cn(
              "flex min-h-10 min-w-0 items-center justify-center gap-2 rounded-md px-2 py-2 text-xs font-medium transition-all sm:text-sm",
              active ? "bg-white text-slate-900 shadow-sm font-semibold" : "text-slate-600 hover:bg-white/50 hover:text-slate-900"
            )}
          >
            <Icon className={cn("h-4 w-4 shrink-0", active ? "text-primary" : "text-slate-400")} aria-hidden="true" />
            <span className="min-w-0 truncate">{item.label}</span>
          </button>
        );
      })}
    </nav>
  );
}

function PanelErrorsNotice({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null;
  return (
    <div className="border-l-2 border-amber-500 bg-amber-50 px-3 py-2 text-sm text-amber-900">
      部分信息暂不可用：{errors.join("；")}
    </div>
  );
}

function pickPanelErrors(errors: Record<string, string>, keys: string[]) {
  return keys.map((key) => errors[key]).filter(Boolean);
}

function AuditValue({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[11px] text-slate-500">{label}</dt>
      <dd className={cn("mt-1 break-words text-slate-700", mono && "font-mono")}>{value || "-"}</dd>
    </div>
  );
}

function AiReviewRunsCard({ runs, starting, onStart, onRetry, onCancel, onInspect }: {
  runs: AiReviewRun[];
  starting: boolean;
  onStart: () => Promise<void>;
  onRetry: (runId: string) => Promise<void>;
  onCancel: (runId: string) => Promise<void>;
  onInspect: (run: AiReviewRun) => Promise<void>;
}) {
  const latestFirst = [...runs].sort((left, right) => right.attemptNo - left.attemptNo);
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <div>
          <CardTitle className="flex items-center gap-2"><ShieldCheck className="h-5 w-5 text-teal-600" />AI 交付复核</CardTitle>
          <CardDescription>完整汇总任务材料、RAG 检索过程与结果、角色上下文及各角色产物</CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={() => void onStart()} disabled={starting || runs.some((run) => isAiReviewActive(run.status))}>
          <ShieldCheck className="mr-2 h-4 w-4" />{starting ? "复核中..." : "立即发起复核"}
        </Button>
      </CardHeader>
      <CardContent>
        {latestFirst.length === 0 ? <div className="py-3 text-sm text-muted-foreground">暂无 AI 复核运行记录</div> : (
          <div className="divide-y divide-slate-200 border border-slate-200">
            {latestFirst.map((run) => (
              <article key={run.runId} className="min-w-0 px-3 py-3">
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-xs text-slate-600">Attempt #{run.attemptNo}</span>
                      <Badge variant="outline" className="w-fit">{aiReviewStatusLabel(run.status)}</Badge>
                    </div>
                    <p className="mt-2 break-words text-xs text-muted-foreground">{run.summary || run.errorMessage || "暂无摘要"}</p>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <Button variant="ghost" size="icon" onClick={() => void onInspect(run)} title="查看 AI 复核详情" aria-label="查看 AI 复核详情"><FileText className="h-4 w-4" /></Button>
                    {run.status === "FAILED_RETRYABLE" ? <Button variant="ghost" size="icon" onClick={() => void onRetry(run.runId)} title="重试 AI 复核" aria-label="重试 AI 复核"><RotateCcw className="h-4 w-4" /></Button> : null}
                    {isAiReviewActive(run.status) ? <Button variant="ghost" size="icon" onClick={() => void onCancel(run.runId)} title="取消 AI 复核" aria-label="取消 AI 复核"><X className="h-4 w-4" /></Button> : null}
                  </div>
                </div>
                <dl className="mt-3 grid grid-cols-2 gap-3 border-t border-slate-100 pt-3 text-xs sm:grid-cols-3">
                  <AuditValue label="结论" value={run.decision || "-"} />
                  <AuditValue label="评分" value={run.decision ? String(run.score) : "-"} mono />
                  <AuditValue label="Run ID" value={run.runId} mono />
                </dl>
              </article>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function AiReviewDetailDialog({ run, timeline, artifacts, loading, onOpenChange }: {
  run: AiReviewRun | null;
  timeline: AiReviewEvent[];
  artifacts: AiReviewArtifact[];
  loading: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={!!run} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] max-w-6xl flex-col overflow-hidden p-0 sm:max-w-6xl">
        <DialogHeader className="border-b border-slate-200 px-6 py-5 pr-12">
          <DialogTitle className="flex items-center gap-2"><ShieldCheck className="h-5 w-5 text-teal-600" />AI 交付复核详情</DialogTitle>
          <DialogDescription>{run ? `Run ${run.runId} · ${aiReviewStatusLabel(run.status)}` : "复核审计"}</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 space-y-5 overflow-y-auto px-6 py-5">
          {run ? <section className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <InfoField label="模型" value={run.modelName || "-"} mono />
            <InfoField label="结论" value={run.decision || "待定"} />
            <InfoField label="评分" value={run.decision ? `${run.score}/100` : "-"} />
            <InfoField label="返工角色" value={ROLE_LABEL[run.retryFromRole] || run.retryFromRole || "-"} />
          </section> : null}
          {loading ? <div className="py-8 text-center text-sm text-muted-foreground">正在加载复核过程...</div> : (
            <div className="grid gap-6 lg:grid-cols-[0.8fr_1.4fr]">
              <section className="min-w-0 border-t-2 border-teal-500 bg-slate-50/70 p-4">
                <h3 className="mb-4 text-sm font-semibold text-slate-900">复核过程</h3>
                {timeline.length === 0 ? <div className="text-sm text-muted-foreground">暂无状态事件</div> : <ol className="space-y-3">
                  {timeline.map((event) => <li key={event.eventId} className="border-l-2 border-teal-300 pl-3 text-sm">
                    <div className="font-medium">{aiReviewStatusLabel(event.fromStatus)} → {aiReviewStatusLabel(event.toStatus)}</div>
                    <div className="mt-1 break-words text-xs text-muted-foreground">{event.message || event.trigger}</div>
                  </li>)}
                </ol>}
              </section>
              <section className="min-w-0 border-t-2 border-cyan-500 bg-white p-4">
                <div className="mb-4 flex items-center justify-between"><h3 className="text-sm font-semibold text-slate-900">复核内容</h3><span className="font-mono text-xs text-cyan-700">{artifacts.length} artifacts</span></div>
                {artifacts.length === 0 ? <div className="text-sm text-muted-foreground">暂无可展示的脱敏内容</div> : <div className="space-y-3">
                  {artifacts.map((artifact) => <article key={artifact.artifactId} className="border border-slate-200 p-3">
                    <div className="flex flex-wrap items-center justify-between gap-2"><Badge variant="outline">{artifact.artifactType}</Badge><span className="font-mono text-[10px] text-muted-foreground">{artifact.contentHash}</span></div>
                    <pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap break-words bg-slate-950 p-3 font-mono text-xs leading-5 text-slate-100">{artifact.contentPreview || "无可展示预览"}</pre>
                    <div className="mt-2 break-all font-mono text-[10px] text-muted-foreground">{artifact.artifactUri} · {artifact.redacted ? "已脱敏" : "未脱敏"}</div>
                  </article>)}
                </div>}
              </section>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

function QaEvidenceCard({ evidence }: { evidence: RdTaskQaEvidence[] }) {
  const screenshots = evidence.filter((item) => item.type === "QA_SCREENSHOT");
  const records = evidence.filter((item) => item.type !== "QA_SCREENSHOT");
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <div>
          <CardTitle>QA 验证证据</CardTitle>
          <CardDescription>真实截图、命令日志、请求记录与 Playwright trace</CardDescription>
        </div>
        <Badge variant="outline" className="border-teal-200 bg-teal-50 text-teal-700">
          {evidence.length} 项
        </Badge>
      </CardHeader>
      <CardContent className="space-y-5">
        {screenshots.length > 0 ? (
          <div className="grid gap-4 md:grid-cols-2">
            {screenshots.map((item) => (
              <div key={item.artifactId} className="min-w-0 border border-slate-200 bg-slate-50/60 p-3">
                <a href={item.contentUrl} target="_blank" rel="noreferrer" className="block">
                  <img
                    src={item.contentUrl}
                    alt={item.summary || item.name}
                    className="aspect-video w-full border border-slate-200 bg-white object-contain"
                    loading="lazy"
                  />
                </a>
                <EvidenceMeta evidence={item} />
              </div>
            ))}
          </div>
        ) : null}
        {records.length > 0 ? (
          <div className="divide-y divide-slate-200 border-y border-slate-200">
            {records.map((item) => (
              <div key={item.artifactId} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="flex min-w-0 items-start gap-3">
                  <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center border border-slate-200 bg-slate-50 text-slate-600">
                    {item.type === "QA_TRACE" ? <FileArchive className="h-4 w-4" /> : <TerminalSquare className="h-4 w-4" />}
                  </span>
                  <EvidenceMeta evidence={item} />
                </div>
                <Button
                  asChild
                  variant="outline"
                  size="icon"
                  title={`${item.previewable ? "查看" : "下载"}${QA_EVIDENCE_LABEL[item.type] || "证据"}`}
                >
                  <a
                    href={item.contentUrl}
                    target={item.previewable ? "_blank" : undefined}
                    rel={item.previewable ? "noreferrer" : undefined}
                    download={item.previewable ? undefined : true}
                  >
                    {item.previewable ? <FileText className="h-4 w-4" /> : <Download className="h-4 w-4" />}
                  </a>
                </Button>
              </div>
            ))}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

function EvidenceMeta({ evidence }: { evidence: RdTaskQaEvidence }) {
  return (
    <div className="min-w-0">
      <div className="flex flex-wrap items-center gap-2">
        <span className="truncate text-sm font-medium text-slate-800">
          {QA_EVIDENCE_LABEL[evidence.type] || evidence.type}
        </span>
        <span className="text-xs text-muted-foreground">{formatBytes(evidence.sizeBytes)}</span>
      </div>
      <div className="mt-1 truncate text-xs text-slate-600">{evidence.summary || evidence.name}</div>
      <div className="mt-1 max-w-full truncate font-mono text-[10px] text-muted-foreground" title={evidence.sha256}>
        {evidence.sha256 ? `sha256:${evidence.sha256.slice(0, 16)}...` : "hash unavailable"}
      </div>
    </div>
  );
}

function RetrievalRunsCard({ runs, error, onRetry, onCancel, onInspect }: {
  runs: RetrievalRun[];
  error?: string;
  onRetry: (runId: string) => Promise<void>;
  onCancel: (runId: string) => Promise<void>;
  onInspect: (run: RetrievalRun) => Promise<void>;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>RAG 检索</CardTitle>
        <CardDescription>{runs.length} 个运行记录 · {runs.filter((run) => isRetrievalRunActive(run.status)).length} 个进行中</CardDescription>
      </CardHeader>
      <CardContent>
        {error ? <div className="mb-3 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">{error}</div> : null}
        {runs.length === 0 ? <div className="py-3 text-sm text-muted-foreground">{error ? "检索面板暂不可用" : "暂无检索运行记录"}</div> : (
          <div className="divide-y divide-slate-200 border border-slate-200">
            {runs.map((run) => {
              const active = ["CREATED", "PLANNING", "RETRIEVING", "EVALUATING", "PACKAGING", "RECOVERING"].includes(run.status);
              const retryable = ["WAITING_INPUT", "FAILED_RETRYABLE", "FAILED_NEEDS_HUMAN", "DEAD_LETTERED", "CANCELLED"].includes(run.status);
              const consumer = run.consumerType === "AGENT_ROLE"
                ? ROLE_LABEL[run.role] || run.role || "角色上下文"
                : run.consumerType === "BUG_FIX" ? "Bug 修复" : "需求基础";
              return (
                <article key={run.runId} className="min-w-0 px-3 py-3">
                  <div className="flex min-w-0 items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium text-slate-800">{consumer}</span>
                        <Badge variant="outline" className={retrievalRunStatusClass(run.status)}>{retrievalRunStatusLabel(run.status)}</Badge>
                      </div>
                      <div className="mt-1 break-all font-mono text-[11px] text-muted-foreground">{run.runId}</div>
                    </div>
                    <div className="flex shrink-0 justify-end gap-1">
                      <Button variant="ghost" size="icon" onClick={() => void onInspect(run)} aria-label="查看检索详情" title="查看检索详情"><FileText className="h-4 w-4" /></Button>
                      {retryable ? <Button variant="ghost" size="icon" onClick={() => void onRetry(run.runId)} aria-label="重试检索" title="重试检索"><RotateCcw className="h-4 w-4" /></Button> : null}
                      {active ? <Button variant="ghost" size="icon" onClick={() => void onCancel(run.runId)} aria-label="取消检索" title="取消检索"><X className="h-4 w-4" /></Button> : null}
                    </div>
                  </div>
                  <dl className="mt-3 grid grid-cols-2 gap-3 border-t border-slate-100 pt-3 text-xs sm:grid-cols-4 lg:grid-cols-5">
                    <AuditValue label="Attempt" value={`#${run.attemptNo}`} mono />
                    <AuditValue label="迭代" value={`${run.currentIteration}/${run.maxIterations}`} mono />
                    <AuditValue label="证据" value={`${run.selectedEvidenceCount}/${run.candidateCount}`} mono />
                    <AuditValue label="质量" value={run.qualityDecision || "待评估"} />
                    <AuditValue label="原因" value={run.stopReason || run.errorMessage || "-"} />
                  </dl>
                </article>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function RetrievalRunDetailDialog({ run, timeline, artifacts, loading, onOpenChange }: {
  run: RetrievalRun | null;
  timeline: RetrievalRunEvent[];
  artifacts: RetrievalRunArtifact[];
  loading: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const contentArtifacts = artifacts.filter((artifact) => RETRIEVAL_CONTENT_ARTIFACT_TYPES.has(artifact.artifactType));
  const processArtifacts = artifacts.filter((artifact) => !RETRIEVAL_CONTENT_ARTIFACT_TYPES.has(artifact.artifactType));
  return (
    <Dialog open={!!run} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[calc(100vh-2rem)] max-w-6xl flex-col overflow-hidden p-0 sm:max-w-6xl">
        <DialogHeader className="border-b border-slate-200 px-6 py-5 pr-12">
          <DialogTitle className="flex items-center gap-2"><Activity className="h-5 w-5 text-cyan-600" />RAG 检索详情</DialogTitle>
          <DialogDescription>{run ? `Run ${run.runId} · ${retrievalRunStatusLabel(run.status)}` : "检索运行审计"}</DialogDescription>
        </DialogHeader>
        <div className="min-h-0 space-y-6 overflow-y-auto px-6 py-5">
          {run ? (
            <section className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
              <InfoField label="运行 ID" value={run.runId} mono />
              <InfoField label="状态" value={retrievalRunStatusLabel(run.status)} />
              <InfoField label="查询预览" value={run.queryPreview || "-"} />
              <InfoField label="知识库范围" value={run.knowledgeBaseIds.join(", ") || "未绑定"} />
              <InfoField label="候选 / 最终证据" value={`${run.candidateCount} / ${run.selectedEvidenceCount}`} />
              <InfoField label="质量决策" value={run.qualityDecision || "待评估"} />
            </section>
          ) : null}
          {loading ? <div className="py-8 text-center text-sm text-muted-foreground">正在加载检索过程...</div> : (
            <div className="grid gap-6 lg:grid-cols-[0.9fr_1.3fr]">
              <section className="min-w-0 border-t-2 border-cyan-500 bg-slate-50/70 p-4">
                <div className="mb-4 flex items-center justify-between gap-3"><h3 className="text-sm font-semibold text-slate-900">检索过程</h3><span className="font-mono text-xs text-cyan-700">{timeline.length + processArtifacts.length} events</span></div>
                {timeline.length === 0 ? <div className="border border-dashed border-slate-200 px-3 py-4 text-sm text-muted-foreground">暂无状态事件</div> : (
                  <ol className="space-y-3">
                    {timeline.map((event) => <li key={event.eventId} className="relative border-l-2 border-cyan-300 pl-4 text-sm before:absolute before:-left-[5px] before:top-1 before:h-2 before:w-2 before:bg-cyan-500">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-1"><span className="font-medium text-slate-800">{retrievalRunStatusLabel(event.fromStatus)} → {retrievalRunStatusLabel(event.toStatus)}</span><span className="font-mono text-[11px] text-muted-foreground">{event.trigger || "SYSTEM"}</span></div>
                      <div className="mt-1 break-words text-xs text-slate-600">{event.message || "状态已推进"}</div>
                      <div className="mt-1 text-xs text-muted-foreground"><RelativeTime value={new Date(event.occurredAtEpochMillis).toISOString()} /></div>
                    </li>)}
                  </ol>
                )}
                <div className="mt-5 space-y-3">
                  {processArtifacts.map((artifact) => <RetrievalArtifactCard key={artifact.artifactId} artifact={artifact} tone="process" />)}
                </div>
              </section>
              <section className="min-w-0 border-t-2 border-emerald-500 bg-white p-4">
                <div className="mb-4 flex items-center justify-between gap-3"><h3 className="text-sm font-semibold text-slate-900">检索内容</h3><span className="font-mono text-xs text-emerald-700">{contentArtifacts.length} items</span></div>
                {contentArtifacts.length === 0 ? <div className="border border-dashed border-slate-200 px-3 py-4 text-sm text-muted-foreground">暂无可展示的候选或最终证据</div> : (
                  <div className="space-y-3">{contentArtifacts.map((artifact) => <RetrievalArtifactCard key={artifact.artifactId} artifact={artifact} tone="content" />)}</div>
                )}
              </section>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

function RetrievalArtifactCard({ artifact, tone }: { artifact: RetrievalRunArtifact; tone: "process" | "content" }) {
  return (
    <article className={cn("border p-3", tone === "content" ? "border-emerald-200 bg-emerald-50/30" : "border-slate-200 bg-white")}>
      <div className="flex flex-wrap items-center justify-between gap-2"><Badge variant="outline" className={tone === "content" ? "border-emerald-200 bg-white text-emerald-800" : "border-cyan-200 bg-cyan-50 text-cyan-800"}>{artifact.artifactType}</Badge><span className="max-w-[55%] truncate font-mono text-[10px] text-muted-foreground" title={artifact.contentHash}>{artifact.contentHash || "无 hash"}</span></div>
      <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap break-words bg-slate-950 p-3 font-mono text-xs leading-5 text-slate-100">{artifact.contentPreview || "无可展示预览"}</pre>
      <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground"><span className="break-all font-mono">{artifact.artifactUri || "控制台产物"}</span><span>{artifact.redacted ? "已脱敏" : "未脱敏"}</span></div>
    </article>
  );
}

function parseCriteria(value?: string) {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map((item) => String(item)).filter(Boolean) : [];
  } catch {
    return value.split("\n").map((line) => line.trim()).filter(Boolean);
  }
}

function hasExecutionEvidence(task: RdTask) {
  const evidence = task.executionEvidence;
  return Boolean(
    task.pullRequestUrl ||
      evidence?.summary ||
      evidence?.prBody ||
      evidence?.changedFiles?.length ||
      evidence?.testCommands?.length ||
      evidence?.testStatus ||
      evidence?.riskLevel
  );
}

function shouldPollTask(task: RdTask) {
  return !task.paused && ![
    "COMPLETED",
    "MERGED",
    "REJECTED",
    "FAILED_NEEDS_HUMAN",
    "CANCELLED",
    "DEAD_LETTERED",
    "DELETED"
  ].includes(task.status);
}

function canRetryRequirement(task: RdTask) {
  return !task.paused && isRetryableRequirementTaskStatus(task.status);
}

function isTaskLevelRecovery(snapshot: TaskFailureRecoverySnapshot | null) {
  if (!snapshot) return false;
  if (snapshot.retryPoint.failurePhase === "AGENT_ROLE") return false;
  if (snapshot.retryPoint.failurePhase !== "RAG") return true;
  return !snapshot.retryPoint.retryFromRole;
}

function canApproveRequirement(task: RdTask) {
  return !task.paused && task.status === "WAITING_APPROVAL";
}

function InfoField({
  label,
  value,
  mono
}: {
  label: string;
  value: React.ReactNode;
  mono?: boolean;
}) {
  return (
    <div>
      <div className="mb-1 text-xs text-muted-foreground">{label}</div>
      <div className={cn("text-sm text-slate-800", mono && "font-mono break-all")}>{value}</div>
    </div>
  );
}
