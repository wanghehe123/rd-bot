import { useState } from "react";
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock3,
  ExternalLink,
  FileCode2,
  HelpCircle,
  History,
  Layers,
  LoaderCircle,
  ShieldCheck
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { CodingMeaView, MeaRoundView } from "@/pages/admin/rdtask/codingMeaModel.ts";
import { getManagerDecision, type DecisionReference } from "@/services/codingMeaService.ts";

export interface CodingMeaPanelProps {
  taskId: string;
  meaView: CodingMeaView | null;
  loading: boolean;
  error: string;
  onNavigateToQaAttempt?: (qaAttemptNo: number) => void;
}

export function CodingMeaPanel({
  taskId,
  meaView,
  loading,
  error,
  onNavigateToQaAttempt
}: CodingMeaPanelProps) {
  const [expandedHistoryRounds, setExpandedHistoryRounds] = useState<Record<string, boolean>>({});
  const [fullContractMap, setFullContractMap] = useState<Record<string, DecisionReference>>({});
  const [loadingContractHash, setLoadingContractHash] = useState<string | null>(null);

  if (loading) {
    return (
      <section className="border border-slate-200 bg-white p-4" aria-label="Coding 内 MEA 协作">
        <div className="flex items-center gap-2 text-sm text-slate-500">
          <LoaderCircle className="h-4 w-4 animate-spin text-teal-600" />
          正在加载 Coding 内 MEA 协作追踪...
        </div>
      </section>
    );
  }

  if (error || !meaView || !meaView.available) {
    return (
      <section className="border border-slate-200 bg-white p-4" aria-label="Coding 内 MEA 协作">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-2">
          <h4 className="text-sm font-semibold text-slate-900">Coding 内 MEA 协作</h4>
          <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-800">
            只读视图
          </Badge>
        </div>
        <div className="mt-3 border-l-2 border-amber-500 bg-amber-50/70 p-3 text-xs text-amber-900">
          协作追踪暂不可用：{error || meaView?.unavailableReason || "无法读取当前 MEA 关联模型"}
        </div>
      </section>
    );
  }

  const {
    currentHeadline,
    currentRound,
    historyRounds,
    isFirstCodingWithoutManager,
    managerDoneReviewNote,
    hasPartialHistory
  } = meaView;

  const toggleHistory = (roundKey: string) => {
    setExpandedHistoryRounds((prev) => ({
      ...prev,
      [roundKey]: !prev[roundKey]
    }));
  };

  const loadFullDecision = async (decisionHash: string) => {
    if (fullContractMap[decisionHash] || loadingContractHash === decisionHash) return;
    setLoadingContractHash(decisionHash);
    try {
      const decision = await getManagerDecision(taskId, decisionHash);
      setFullContractMap((prev) => ({ ...prev, [decisionHash]: decision }));
    } catch {
      // Graceful fallback
    } finally {
      setLoadingContractHash(null);
    }
  };

  return (
    <section className="border border-slate-200 bg-white" aria-labelledby="coding-mea-title">
      <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-slate-50/50 px-4 py-3 sm:px-5">
        <div className="flex flex-wrap items-center gap-2">
          <h4 id="coding-mea-title" className="text-sm font-semibold text-slate-950">
            Coding 内 MEA 协作
          </h4>
          <Badge variant="outline" className="border-teal-300 bg-teal-50 text-teal-800 text-xs font-medium">
            局部闭环
          </Badge>
          {managerDoneReviewNote ? (
            <Badge variant="outline" className="border-emerald-300 bg-emerald-50 text-emerald-800 text-xs">
              {managerDoneReviewNote}
            </Badge>
          ) : null}
        </div>
        {currentHeadline ? (
          <span className="text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded px-2 py-0.5">
            {currentHeadline}
          </span>
        ) : null}
      </header>

      {hasPartialHistory ? (
        <div className="border-b border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-900">
          当前仅加载部分历史轮次。
        </div>
      ) : null}

      <div className="p-4 sm:p-5 space-y-4">
        {currentRound ? (
          <RoundThreeColumnSection
            round={currentRound}
            isFirstCodingWithoutManager={isFirstCodingWithoutManager}
            onNavigateToQaAttempt={onNavigateToQaAttempt}
            fullContractMap={fullContractMap}
            loadingContractHash={loadingContractHash}
            onLoadFullDecision={loadFullDecision}
          />
        ) : (
          <div className="py-6 text-center text-xs text-slate-500">
            暂无当前轮次协作记录。
          </div>
        )}

        {/* 历史轮次折叠区域 */}
        {historyRounds.length > 0 ? (
          <div className="pt-2 border-t border-slate-200">
            <h5 className="text-xs font-semibold text-slate-700 mb-2 flex items-center gap-1.5">
              <History className="h-3.5 w-3.5 text-slate-500" />
              历史协作轮次（{historyRounds.length}）
            </h5>
            <div className="space-y-2">
              {historyRounds.map((hRound) => {
                const isOpen = Boolean(expandedHistoryRounds[hRound.roundKey]);
                return (
                  <div key={hRound.roundKey} className="border border-slate-200 rounded">
                    <button
                      type="button"
                      onClick={() => toggleHistory(hRound.roundKey)}
                      className="w-full flex items-center justify-between px-3 py-2 text-left bg-slate-50 hover:bg-slate-100/80 transition-colors text-xs font-medium text-slate-800"
                    >
                      <span className="flex items-center gap-1.5">
                        {isOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
                        {hRound.roundTitle}
                      </span>
                      <span className="text-slate-500 font-normal">
                        {hRound.manage?.routeLabel || "无前置决策"} · {hRound.execute?.status || ""}
                      </span>
                    </button>
                    {isOpen ? (
                      <div className="p-3 border-t border-slate-200 bg-white">
                        <RoundThreeColumnSection
                          round={hRound}
                          isFirstCodingWithoutManager={false}
                          onNavigateToQaAttempt={onNavigateToQaAttempt}
                          fullContractMap={fullContractMap}
                          loadingContractHash={loadingContractHash}
                          onLoadFullDecision={loadFullDecision}
                        />
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function RoundThreeColumnSection({
  round,
  isFirstCodingWithoutManager,
  onNavigateToQaAttempt,
  fullContractMap,
  loadingContractHash,
  onLoadFullDecision
}: {
  round: MeaRoundView;
  isFirstCodingWithoutManager: boolean;
  onNavigateToQaAttempt?: (qaAttemptNo: number) => void;
  fullContractMap: Record<string, DecisionReference>;
  loadingContractHash: string | null;
  onLoadFullDecision: (decisionHash: string) => void;
}) {
  const { manage, execute, audit } = round;

  return (
    <div className="grid gap-3 lg:grid-cols-3">
      {/* 1. Manage 栏 */}
      <div className="border border-slate-200 rounded p-3 bg-slate-50/40 flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between gap-1 border-b border-slate-200 pb-2 mb-2">
            <span className="text-xs font-bold text-slate-900 tracking-wide">
              Manage (Host Manager)
            </span>
            {manage ? (
              <Badge variant="outline" className="border-teal-200 bg-teal-50 text-teal-800 text-[11px]">
                {manage.roundLabel}
              </Badge>
            ) : null}
          </div>

          {isFirstCodingWithoutManager ? (
            <div className="py-4 text-center text-xs text-slate-500 leading-relaxed">
              首次执行尚无前置 Manager 决策
            </div>
          ) : !manage ? (
            <div className="py-4 text-center text-xs text-slate-500">
              当前轮暂无前置 Manager 决策记录
            </div>
          ) : (
            <div className="space-y-2 text-xs">
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="text-slate-500">决策路由：</span>
                <Badge variant="outline" className="border-slate-300 bg-white text-slate-800 text-[11px] font-semibold">
                  {manage.routeLabel} ({manage.route})
                </Badge>
              </div>

              {manage.targetRecordIds.length > 0 ? (
                <div>
                  <span className="text-slate-500">目标验收项：</span>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {manage.targetRecordIds.map((acId) => (
                      <Badge key={acId} variant="outline" className="border-rose-200 bg-rose-50 text-rose-800 text-[10px] font-mono">
                        {acId}
                      </Badge>
                    ))}
                  </div>
                </div>
              ) : null}

              {manage.boundedContractPreview ? (
                <div className="border border-slate-200 bg-white p-2 rounded">
                  <div className="flex items-center justify-between text-[11px] text-slate-500 mb-1">
                    <span>有界合同摘要</span>
                    {manage.boundedContractTruncated ? (
                      <button
                        type="button"
                        onClick={() => onLoadFullDecision(manage.decisionHash)}
                        className="text-teal-700 hover:underline inline-flex items-center gap-0.5"
                        disabled={loadingContractHash === manage.decisionHash}
                      >
                        {loadingContractHash === manage.decisionHash ? "加载中..." : "查看完整合同"}
                      </button>
                    ) : null}
                  </div>
                  <p className="text-[11px] text-slate-700 whitespace-pre-wrap leading-relaxed line-clamp-3">
                    {fullContractMap[manage.decisionHash]?.boundedContractPreview || manage.boundedContractPreview}
                  </p>
                </div>
              ) : null}

              {manage.rationale ? (
                <div className="text-[11px] text-slate-600 bg-white p-2 rounded border border-slate-200">
                  <span className="font-medium text-slate-700">决策理由：</span>
                  {manage.rationale}
                </div>
              ) : null}
            </div>
          )}
        </div>

        {manage?.commandCreatedAtEpochMillis ? (
          <div className="mt-2 pt-2 border-t border-slate-200 text-[10px] text-slate-400">
            决策命令创建时间：{new Date(manage.commandCreatedAtEpochMillis).toLocaleString("zh-CN", { hour12: false })}
          </div>
        ) : null}
      </div>

      {/* 2. Execute 栏 */}
      <div className="border border-slate-200 rounded p-3 bg-slate-50/40 flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between gap-1 border-b border-slate-200 pb-2 mb-2">
            <span className="text-xs font-bold text-slate-900 tracking-wide">
              Execute (Coding 执行)
            </span>
            {execute?.remediationRoundLabel ? (
              <Badge variant="outline" className="border-indigo-200 bg-indigo-50 text-indigo-800 text-[11px]">
                {execute.remediationRoundLabel}
              </Badge>
            ) : null}
          </div>

          {execute ? (
            <div className="space-y-2 text-xs">
              <div className="flex items-center justify-between">
                <span className="text-slate-500">执行主体：</span>
                <span className="font-semibold text-slate-900">{execute.attemptLabel}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">执行状态：</span>
                <Badge variant="outline" className="border-slate-300 bg-white text-slate-800 text-[11px]">
                  {execute.status}
                </Badge>
              </div>
              {execute.remediationKind ? (
                <div className="flex items-center justify-between">
                  <span className="text-slate-500">修复类型：</span>
                  <span className="font-mono text-[11px] text-slate-700">{execute.remediationKind}</span>
                </div>
              ) : null}
              {execute.commandId ? (
                <div className="flex items-center justify-between text-[11px]">
                  <span className="text-slate-500">命令 ID：</span>
                  <span className="font-mono text-slate-600 truncate max-w-[140px]" title={execute.commandId}>
                    {execute.commandId}
                  </span>
                </div>
              ) : null}
            </div>
          ) : (
            <div className="py-4 text-center text-xs text-slate-500">
              暂无 Coding 执行数据
            </div>
          )}
        </div>
      </div>

      {/* 3. Audit 栏 */}
      <div className="border border-slate-200 rounded p-3 bg-slate-50/40 flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between gap-1 border-b border-slate-200 pb-2 mb-2">
            <span className="text-xs font-bold text-slate-900 tracking-wide">
              Audit (Host 审计)
            </span>
            <Badge variant="outline" className="border-slate-200 bg-white text-slate-700 text-[11px]">
              闭环验证
            </Badge>
          </div>

          <div className="space-y-2.5 text-xs">
            {/* Host Verify 摘要 */}
            {audit?.hostVerify ? (
              <div className={cn(
                "p-2 rounded border text-[11px]",
                audit.hostVerify.passed
                  ? "border-emerald-200 bg-emerald-50/50 text-emerald-900"
                  : "border-rose-200 bg-rose-50/50 text-rose-900"
              )}>
                <div className="flex items-center gap-1 font-semibold">
                  <ShieldCheck className="h-3.5 w-3.5 shrink-0" />
                  宿主验证：{audit.hostVerify.summary}
                </div>
              </div>
            ) : null}

            {/* QA Attempt 跳转 */}
            {audit?.qaStage ? (
              <div className="border border-slate-200 bg-white p-2 rounded">
                <div className="flex items-center justify-between">
                  <span className="text-slate-500">关联 QA：</span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2 text-xs text-teal-700 hover:text-teal-900 font-medium inline-flex items-center gap-1"
                    onClick={() => onNavigateToQaAttempt?.(audit.qaStage?.attemptNo || 1)}
                  >
                    <span>QA Attempt {audit.qaStage.attemptNo}</span>
                    <ExternalLink className="h-3 w-3" />
                  </Button>
                </div>
                <div className="mt-1 flex items-center justify-between text-[11px] text-slate-500">
                  <span>QA 状态：{audit.qaStage.status}</span>
                </div>
              </div>
            ) : (
              <div className="text-[11px] text-slate-500 py-1">
                待对应 QA Attempt 执行与审计
              </div>
            )}

            {/* 审计结论 */}
            {audit?.auditConclusion ? (
              <div className="text-[11px] text-slate-600">
                <span className="font-medium text-slate-700">审计概况：</span>
                {audit.auditConclusion.summary}
              </div>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}
