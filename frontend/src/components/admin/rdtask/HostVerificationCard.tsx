import { useState } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  Clock3,
  Download,
  FileCode,
  FileText,
  History,
  LoaderCircle,
  TerminalSquare,
  Wrench
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import {
  hostVerificationContentUrl,
  type HostVerificationArtifact,
  type HostVerificationList,
  type HostVerificationRun,
  type HostVerificationStatus,
  type HostVerificationStep,
  type HostVerificationStepStatus
} from "@/services/rdTaskService";

const STATUS_LABELS: Record<HostVerificationStatus, string> = {
  CREATED: "已创建",
  PREPARING: "准备中",
  BUILDING: "编译中",
  STATIC_CHECKING: "静态检查中",
  SUCCEEDED: "已通过",
  FAILED_RETRYABLE: "验证失败 (可重试)",
  FAILED_NEEDS_HUMAN: "验证失败 (需人工)",
  SKIPPED_DOCS_ONLY: "已跳过 (仅文档变更)",
  CANCELLED: "已取消"
};

const STEP_STATUS_LABELS: Record<HostVerificationStepStatus, string> = {
  PENDING: "待执行",
  RUNNING: "执行中",
  SUCCEEDED: "成功",
  FAILED: "失败",
  SKIPPED: "未执行"
};

const STEP_NAMES: Record<string, string> = {
  BUILD: "宿主 BUILD (安装 / 编译 / 仓库测试)",
  STATIC: "宿主 STATIC (类型检查 / Lint)"
};

interface FailureCategoryGuidance {
  label: string;
  userMessage: string;
  nextStep: string;
}

const FAILURE_CATEGORY_GUIDANCE: Record<string, FailureCategoryGuidance> = {
  PRODUCT_DEFECT: {
    label: "代码缺陷",
    userMessage: "候选代码未通过构建或静态检查",
    nextStep: "自动打回 Coding（未达 2 次且 Coding attempt < 3）"
  },
  ENVIRONMENT: {
    label: "环境故障",
    userMessage: "环境或验证基础设施失败",
    nextStep: "需人工排查，不会自动打回"
  },
  QA_INFRASTRUCTURE: {
    label: "基础设施故障",
    userMessage: "环境或验证基础设施失败",
    nextStep: "需人工排查，不会自动打回"
  },
  AUTHENTICATION: {
    label: "鉴权失败",
    userMessage: "执行环境凭据或网络鉴权失败",
    nextStep: "需人工配置凭据"
  },
  REQUIREMENT_AMBIGUITY: {
    label: "配置缺失",
    userMessage: "无法安全探测构建命令",
    nextStep: "需人工或补齐项目构建配置"
  },
  FLAKY: {
    label: "偶发不稳定",
    userMessage: "不稳定失败",
    nextStep: "需人工复核"
  }
};

function formatDuration(ms?: number): string {
  if (!ms || ms <= 0) return "0ms";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${(ms / 60_000).toFixed(1)}min`;
  return `${(ms / 3_600_000).toFixed(1)}h`;
}

function HostVerificationStatusBadge({ status }: { status: HostVerificationStatus }) {
  if (status === "SUCCEEDED") {
    return (
      <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700 font-medium">
        <CheckCircle2 className="mr-1 h-3.5 w-3.5 text-emerald-600" />
        {STATUS_LABELS[status]}
      </Badge>
    );
  }
  if (status === "SKIPPED_DOCS_ONLY") {
    return (
      <Badge variant="outline" className="border-sky-200 bg-sky-50 text-sky-700 font-medium">
        <FileCode className="mr-1 h-3.5 w-3.5 text-sky-600" />
        {STATUS_LABELS[status]}
      </Badge>
    );
  }
  if (status === "FAILED_RETRYABLE" || status === "FAILED_NEEDS_HUMAN") {
    return (
      <Badge variant="outline" className="border-rose-200 bg-rose-50 text-rose-700 font-medium">
        <AlertTriangle className="mr-1 h-3.5 w-3.5 text-rose-600" />
        {STATUS_LABELS[status] || status}
      </Badge>
    );
  }
  if (status === "BUILDING" || status === "STATIC_CHECKING" || status === "PREPARING" || status === "CREATED") {
    return (
      <Badge variant="outline" className="border-teal-200 bg-teal-50 text-teal-700 font-medium">
        <LoaderCircle className="mr-1 h-3.5 w-3.5 animate-spin text-teal-600" />
        {STATUS_LABELS[status] || status}
      </Badge>
    );
  }
  return (
    <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-600">
      <Clock3 className="mr-1 h-3.5 w-3.5 text-slate-400" />
      {STATUS_LABELS[status] || status}
    </Badge>
  );
}

function StepStatusBadge({ status }: { status: HostVerificationStepStatus }) {
  if (status === "SUCCEEDED") {
    return (
      <Badge variant="outline" className="border-emerald-200 bg-emerald-50 text-emerald-700 text-[11px]">
        <CheckCircle2 className="mr-1 h-3 w-3 text-emerald-600" />
        {STEP_STATUS_LABELS[status]}
      </Badge>
    );
  }
  if (status === "FAILED") {
    return (
      <Badge variant="outline" className="border-rose-200 bg-rose-50 text-rose-700 text-[11px]">
        <AlertTriangle className="mr-1 h-3 w-3 text-rose-600" />
        {STEP_STATUS_LABELS[status]}
      </Badge>
    );
  }
  if (status === "RUNNING") {
    return (
      <Badge variant="outline" className="border-teal-200 bg-teal-50 text-teal-700 text-[11px]">
        <LoaderCircle className="mr-1 h-3 w-3 animate-spin text-teal-600" />
        {STEP_STATUS_LABELS[status]}
      </Badge>
    );
  }
  if (status === "SKIPPED") {
    return (
      <Badge variant="outline" className="border-slate-200 bg-slate-100 text-slate-500 text-[11px]">
        {STEP_STATUS_LABELS[status]}
      </Badge>
    );
  }
  return (
    <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-600 text-[11px]">
      {STEP_STATUS_LABELS[status] || status}
    </Badge>
  );
}

export interface HostVerificationCardProps {
  taskId: string;
  verificationList: HostVerificationList | null;
  loading?: boolean;
  error?: string;
  onSelectCodingAttempt?: (attemptNo?: number) => void;
}

export function HostVerificationCard({
  taskId,
  verificationList,
  loading = false,
  error,
  onSelectCodingAttempt
}: HostVerificationCardProps) {
  const [showHistory, setShowHistory] = useState(false);

  if (loading) {
    return (
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="py-4">
          <div className="flex items-center gap-2">
            <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" />
            <CardTitle className="text-base">宿主验证（编译 / 静态检查）</CardTitle>
          </div>
          <CardDescription>正在加载宿主验证状态...</CardDescription>
        </CardHeader>
      </Card>
    );
  }

  if (error) {
    return (
      <Card className="border-slate-200 shadow-sm">
        <CardHeader className="py-4">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base flex items-center gap-2">
              <Wrench className="h-4 w-4 text-slate-600" />
              宿主验证（编译 / 静态检查）
            </CardTitle>
            <Badge variant="outline" className="border-slate-200 bg-slate-50 text-slate-500">
              未启用 / 暂不可用
            </Badge>
          </div>
          <CardDescription>Coding 通过后、浏览器 QA 之前由宿主重跑。不是第五个 Agent。</CardDescription>
        </CardHeader>
        <CardContent className="py-3">
          <div className="rounded-md border border-dashed border-slate-200 bg-slate-50/50 p-4 text-center text-xs text-slate-500">
            {error.includes("404") || error.includes("Not Found")
              ? "此环境尚未启用宿主验证"
              : `宿主验证记录读取提示：${error}`}
          </div>
        </CardContent>
      </Card>
    );
  }

  const runs = verificationList?.runs || [];
  const latestRun = runs[0];
  const cheapRemediationsUsed = verificationList?.cheapRemediationsUsed ?? latestRun?.remediationCount ?? 0;

  return (
    <Card className="border-slate-200 shadow-sm" aria-label="宿主验证面板">
      <CardHeader className="border-b border-slate-100 px-4 py-4 sm:px-5">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle className="text-base font-semibold text-slate-950 flex items-center gap-2">
                <Wrench className="h-4 w-4 text-teal-700" />
                宿主验证（编译 / 静态检查）
              </CardTitle>
              {latestRun ? <HostVerificationStatusBadge status={latestRun.status} /> : null}
            </div>
            <CardDescription className="mt-1 text-xs text-slate-600">
              Coding 通过后、浏览器 QA 之前由宿主重跑。不是第五个 Agent。
            </CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-1.5 rounded-md border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs text-slate-700">
              <span className="text-slate-500">廉价返工：</span>
              <strong className="font-semibold text-slate-900">{cheapRemediationsUsed} / 2</strong>
            </div>
            {runs.length > 1 ? (
              <Button
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                onClick={() => setShowHistory((prev) => !prev)}
              >
                <History className="mr-1.5 h-3.5 w-3.5" />
                {showHistory ? "收起历史轮次" : `全部 ${runs.length} 轮`}
              </Button>
            ) : null}
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-4 px-4 py-4 sm:px-5">
        {runs.length === 0 ? (
          <div className="rounded-md border border-dashed border-slate-200 bg-slate-50/50 py-8 text-center text-xs text-slate-500">
            暂无宿主验证运行记录（在 Coding 完成后自动执行）。
          </div>
        ) : (
          <div className="space-y-4">
            {/* 最新一轮 */}
            <VerificationRunSection
              taskId={taskId}
              run={latestRun}
              isLatest
              onSelectCodingAttempt={onSelectCodingAttempt}
            />

            {/* 历史轮次 */}
            {showHistory && runs.length > 1 ? (
              <div className="mt-4 space-y-4 border-t border-slate-200 pt-4">
                <div className="flex items-center gap-2 text-xs font-semibold text-slate-700">
                  <History className="h-3.5 w-3.5 text-slate-500" />
                  历史验证轮次
                </div>
                <div className="divide-y divide-slate-200 border border-slate-200 rounded-md">
                  {runs.slice(1).map((run) => (
                    <div key={run.runId} className="p-3">
                      <VerificationRunSection
                        taskId={taskId}
                        run={run}
                        isLatest={false}
                        onSelectCodingAttempt={onSelectCodingAttempt}
                      />
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function VerificationRunSection({
  taskId,
  run,
  isLatest,
  onSelectCodingAttempt
}: {
  taskId: string;
  run: HostVerificationRun;
  isLatest: boolean;
  onSelectCodingAttempt?: (attemptNo?: number) => void;
}) {
  const steps = run.steps || [];
  const buildStep = steps.find((s) => s.step === "BUILD");
  const staticStep = steps.find((s) => s.step === "STATIC");
  const guidance = run.failureCategory ? FAILURE_CATEGORY_GUIDANCE[run.failureCategory] : null;

  return (
    <section className={cn("space-y-3", !isLatest && "text-xs")}>
      {/* 轮次元信息 */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-2 text-xs">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold text-slate-900">
            轮次 {run.attemptNo}
            {isLatest ? <span className="ml-1 text-[11px] text-teal-700 font-normal">（最新）</span> : null}
          </span>
          <HostVerificationStatusBadge status={run.status} />
          {run.docsOnly ? (
            <Badge variant="outline" className="border-sky-200 bg-sky-50 text-sky-800 text-[11px]">
              文档变更 · 免编译
            </Badge>
          ) : null}
          {run.codingStageRunId ? (
            <span className="text-slate-500 font-mono">
              关联 Coding：
              {onSelectCodingAttempt ? (
                <button
                  type="button"
                  onClick={() => onSelectCodingAttempt(run.attemptNo)}
                  className="text-primary underline hover:text-primary/80 ml-1"
                >
                  Attempt {run.attemptNo}
                </button>
              ) : (
                <code className="text-slate-700 ml-1">{run.codingStageRunId}</code>
              )}
            </span>
          ) : null}
        </div>
        <div className="flex items-center gap-2 text-slate-500 text-[11px]">
          <span>耗时：{formatDuration(run.finishedAtEpochMillis ? run.finishedAtEpochMillis - run.startedAtEpochMillis : 0)}</span>
          <span className="font-mono text-slate-400">Run ID: {run.runId}</span>
        </div>
      </div>

      {/* Docs-Only 特殊展示 */}
      {run.docsOnly || run.status === "SKIPPED_DOCS_ONLY" ? (
        <div className="rounded-md border border-sky-200 bg-sky-50/70 p-3 text-xs text-sky-900">
          <div className="font-medium">文档变更，已跳过构建与静态检查</div>
          <div className="mt-0.5 text-sky-700">本次改动仅涉及文档或非可执行文本，已直接放行进入 QA 阶段。</div>
        </div>
      ) : null}

      {/* 失败归因与下一步引导 */}
      {run.errorMessage || guidance ? (
        <div className="rounded-md border border-rose-200 bg-rose-50/70 p-3 space-y-1.5 text-xs text-rose-950">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="font-semibold flex items-center gap-1.5">
              <AlertTriangle className="h-3.5 w-3.5 text-rose-600 shrink-0" />
              {guidance?.label ? `失败分类：${guidance.label}` : "宿主验证失败"}
            </div>
            {guidance?.nextStep ? (
              <span className="rounded bg-rose-100 px-2 py-0.5 text-[11px] font-medium text-rose-800">
                下一步：{guidance.nextStep}
              </span>
            ) : null}
          </div>
          {guidance?.userMessage ? (
            <p className="text-rose-900">{guidance.userMessage}</p>
          ) : null}
          {run.errorMessage ? (
            <p className="font-mono text-[11px] text-rose-900 break-words whitespace-pre-wrap bg-white/60 p-2 rounded border border-rose-100">
              {run.errorMessage}
            </p>
          ) : null}
        </div>
      ) : null}

      {/* 步骤列表: BUILD & STATIC */}
      {!run.docsOnly ? (
        <div className="space-y-2">
          <StepDetailRow
            taskId={taskId}
            runId={run.runId}
            stepName="BUILD"
            step={buildStep}
            artifacts={run.artifacts}
          />
          <StepDetailRow
            taskId={taskId}
            runId={run.runId}
            stepName="STATIC"
            step={staticStep}
            artifacts={run.artifacts}
            disabledByPriorFailure={buildStep?.status === "FAILED"}
          />
        </div>
      ) : null}
    </section>
  );
}

function StepDetailRow({
  taskId,
  runId,
  stepName,
  step,
  artifacts = [],
  disabledByPriorFailure = false
}: {
  taskId: string;
  runId: string;
  stepName: "BUILD" | "STATIC";
  step?: HostVerificationStep;
  artifacts: HostVerificationArtifact[];
  disabledByPriorFailure?: boolean;
}) {
  const status: HostVerificationStepStatus = disabledByPriorFailure
    ? "SKIPPED"
    : step?.status || "PENDING";

  const matchingArtifact = artifacts.find(
    (a) => a.artifactId === step?.logArtifactId || a.type.includes(stepName)
  );

  const logUrl = matchingArtifact
    ? matchingArtifact.contentUrl || hostVerificationContentUrl(taskId, runId, matchingArtifact.artifactId)
    : step?.logArtifactId
      ? hostVerificationContentUrl(taskId, runId, step.logArtifactId)
      : null;

  return (
    <div
      className={cn(
        "rounded-md border p-3 transition-colors",
        status === "SUCCEEDED" && "border-emerald-200 bg-emerald-50/20",
        status === "FAILED" && "border-rose-200 bg-rose-50/20",
        status === "RUNNING" && "border-teal-200 bg-teal-50/30",
        (status === "PENDING" || status === "SKIPPED") && "border-slate-200 bg-slate-50/50"
      )}
    >
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-slate-900">
              {STEP_NAMES[stepName] || stepName}
            </span>
            <StepStatusBadge status={status} />
            {step?.exitCode !== null && step?.exitCode !== undefined ? (
              <span
                className={cn(
                  "font-mono text-[11px] px-1.5 py-0.5 rounded",
                  step.exitCode === 0 ? "bg-emerald-100 text-emerald-800" : "bg-rose-100 text-rose-800"
                )}
              >
                exit {step.exitCode}
              </span>
            ) : null}
            {step?.durationMillis ? (
              <span className="text-[11px] text-slate-500">
                耗时 {formatDuration(step.durationMillis)}
              </span>
            ) : null}
          </div>

          {disabledByPriorFailure ? (
            <p className="mt-1 text-xs text-slate-500">
              因 BUILD 步骤未通过，本步骤未执行。
            </p>
          ) : step?.commands && step.commands.length > 0 ? (
            <div className="mt-2 space-y-1">
              {step.commands.map((cmd, idx) => (
                <div
                  key={idx}
                  className="flex items-center gap-1.5 font-mono text-xs bg-slate-900 text-slate-100 px-2.5 py-1.5 rounded overflow-x-auto"
                >
                  <TerminalSquare className="h-3.5 w-3.5 text-slate-400 shrink-0" />
                  <code className="whitespace-pre-wrap break-all">{cmd}</code>
                </div>
              ))}
            </div>
          ) : (
            <p className="mt-1 text-xs text-slate-500">自动探测命令</p>
          )}

          {step?.errorMessage ? (
            <p className="mt-2 text-xs text-rose-800 break-words whitespace-pre-wrap">
              {step.errorMessage}
            </p>
          ) : null}
        </div>

        {/* 日志操作按钮 */}
        {logUrl ? (
          <div className="flex shrink-0 items-center gap-1.5 pt-1 sm:pt-0">
            <Button
              asChild
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              title="查看验证日志"
            >
              <a href={logUrl} target="_blank" rel="noreferrer">
                <FileText className="mr-1 h-3.5 w-3.5" />
                查看日志
              </a>
            </Button>
            <Button
              asChild
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              title="下载日志"
            >
              <a href={logUrl} download>
                <Download className="h-3.5 w-3.5" />
              </a>
            </Button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
