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
import { getManagerDecision, type ManagerDecisionDetail } from "@/services/codingMeaService.ts";

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
  const [fullContractMap, setFullContractMap] = useState<Record<string, ManagerDecisionDetail>>({});
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
      {currentRound ? (
        <CurrentRoundStrip
          round={currentRound}
          currentHeadline={currentHeadline}
          managerDoneReviewNote={managerDoneReviewNote}
          isFirstCodingWithoutManager={isFirstCodingWithoutManager}
          onNavigateToQaAttempt={onNavigateToQaAttempt}
          fullContractMap={fullContractMap}
          loadingContractHash={loadingContractHash}
          onLoadFullDecision={loadFullDecision}
        />
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-slate-50/50 px-4 py-2.5 sm:px-5">
          <h4 id="coding-mea-title" className="text-sm font-semibold text-slate-950">Coding 内 MEA 协作</h4>
          <span className="text-xs text-slate-500">暂无当前轮次协作记录。</span>
        </div>
      )}

      {hasPartialHistory ? (
        <div className="border-b border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-900">
          当前仅加载部分历史轮次。
        </div>
      ) : null}

      {historyRounds.length > 0 ? (
        <div className="p-3 sm:p-4">

          <div className="pt-1">
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
                      aria-expanded={isOpen}
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
        </div>
      ) : null}
      </section>
  );
}


/** 当前轮三职责单行摘要：标题 + Manage / Execute / Audit 短结果常显，决策详情默认折叠。 */
function CurrentRoundStrip({
  round,
  currentHeadline,
  managerDoneReviewNote,
  isFirstCodingWithoutManager,
  onNavigateToQaAttempt,
  fullContractMap,
  loadingContractHash,
  onLoadFullDecision
}: {
  round: MeaRoundView;
  currentHeadline: string;
  managerDoneReviewNote: string | null;
  isFirstCodingWithoutManager: boolean;
  onNavigateToQaAttempt?: (qaAttemptNo: number) => void;
  fullContractMap: Record<string, ManagerDecisionDetail>;
  loadingContractHash: string | null;
  onLoadFullDecision: (decisionHash: string) => void;
}) {
  const { manage, execute, audit } = round;
  const [detailOpen, setDetailOpen] = useState(false);
  const hasDetail = Boolean(
    manage?.rationale || manage?.boundedContractPreview || manage?.commandCreatedAtEpochMillis
    || execute?.commandId || execute?.remediationKind
  );

  const manageShort = isFirstCodingWithoutManager
    ? "首轮无前置决策"
    : manage
      ? `${manage.routeLabel}`
      : (round.manageNote || "无前置决策");
  const executeShort = execute
    ? `${execute.attemptLabel} · ${execute.status}`
    : "暂无执行数据";

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 border-b border-slate-200 bg-slate-50/50 px-4 py-2 text-xs sm:px-5">
        <span className="flex items-center gap-2">
          <h4 id="coding-mea-title" className="text-sm font-semibold text-slate-950">Coding 内 MEA 协作</h4>
          <Badge variant="outline" className="border-teal-300 bg-teal-50 text-teal-800 text-[11px] font-medium">局部闭环</Badge>
          {managerDoneReviewNote ? (
            <Badge variant="outline" className="border-emerald-300 bg-emerald-50 text-emerald-800 text-[11px]">
              {managerDoneReviewNote}
            </Badge>
          ) : null}
        </span>
        <span className="text-slate-300" aria-hidden="true">|</span>
        <span className="flex items-center gap-1.5">
          <span className="font-bold text-slate-900">Manage</span>
          <span className="text-slate-700">{manageShort}</span>
        </span>
        <span className="text-slate-300" aria-hidden="true">|</span>
        <span className="flex items-center gap-1.5">
          <span className="font-bold text-slate-900">Execute</span>
          <span className="text-slate-700">{executeShort}</span>
        </span>
        <span className="text-slate-300" aria-hidden="true">|</span>
        <span className="flex items-center gap-1.5 min-w-0">
          <span className="font-bold text-slate-900">Audit</span>
          {audit?.qaStage ? (
            <button
              type="button"
              className="inline-flex items-center gap-0.5 font-medium text-teal-700 hover:text-teal-900"
              onClick={() => onNavigateToQaAttempt?.(audit.qaStage?.attemptNo || 1)}
            >
              QA Attempt {audit.qaStage.attemptNo} · {audit.qaStage.status}
              <ExternalLink className="h-3 w-3" />
            </button>
          ) : (
            <span className="text-slate-700">待 QA 执行与审计</span>
          )}
        </span>
        {hasDetail ? (
          <button
            type="button"
            aria-expanded={detailOpen}
            onClick={() => setDetailOpen((prev) => !prev)}
            className="ml-auto inline-flex items-center gap-1 font-medium text-teal-700 hover:text-teal-900"
          >
            {detailOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            {detailOpen ? "收起决策详情" : "查看决策详情"}
          </button>
        ) : null}
      </div>

      {detailOpen ? (
        <RoundThreeColumnSection
          round={round}
          isFirstCodingWithoutManager={isFirstCodingWithoutManager}
          onNavigateToQaAttempt={onNavigateToQaAttempt}
          fullContractMap={fullContractMap}
          loadingContractHash={loadingContractHash}
          onLoadFullDecision={onLoadFullDecision}
        />
      ) : null}
    </div>
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
  fullContractMap: Record<string, ManagerDecisionDetail>;
  loadingContractHash: string | null;
  onLoadFullDecision: (decisionHash: string) => void;
}) {
  const { manage, execute, audit } = round;
  // 紧凑呈现：三职责短结果常显；Manager 理由/完整合同/命令 ID/时间默认折叠
  const [detailOpen, setDetailOpen] = useState(false);
  const hasManageDetail = Boolean(
    manage?.rationale || manage?.boundedContractPreview || manage?.commandCreatedAtEpochMillis
  );
  const hasExecuteDetail = Boolean(execute?.commandId || execute?.remediationKind);

  return (
    <div className="space-y-2">
      <div className="grid gap-3 lg:grid-cols-3">
        {/* 1. Manage 栏 */}
        <div className="border border-slate-200 rounded p-3 bg-slate-50/40">
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
              {round.manageNote || "当前轮暂无前置 Manager 决策记录"}
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
            </div>
          )}
        </div>

        {/* 2. Execute 栏 */}
        <div className="border border-slate-200 rounded p-3 bg-slate-50/40">
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
            </div>
          ) : (
            <div className="py-4 text-center text-xs text-slate-500">
              暂无 Coding 执行数据
            </div>
          )}
        </div>

        {/* 3. Audit 栏 */}
        <div className="border border-slate-200 rounded p-3 bg-slate-50/40">
          <div className="flex items-center justify-between gap-1 border-b border-slate-200 pb-2 mb-2">
            <span className="text-xs font-bold text-slate-900 tracking-wide">
              Audit (Host 审计)
            </span>
            <Badge variant="outline" className="border-slate-200 bg-white text-slate-700 text-[11px]">
              闭环验证
            </Badge>
          </div>

          <div className="space-y-2.5 text-xs">
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

            {audit?.auditConclusion ? (
              <div className="text-[11px] text-slate-600">
                <span className="font-medium text-slate-700">审计概况：</span>
                {audit.auditConclusion.summary}
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {/* 决策详情：Manager 理由/完整合同/命令细节默认折叠 */}
      {(hasManageDetail || hasExecuteDetail) ? (
        <div>
          <button
            type="button"
            aria-expanded={detailOpen}
            onClick={() => setDetailOpen((prev) => !prev)}
            className="inline-flex items-center gap-1 text-[11px] font-medium text-teal-700 hover:text-teal-900"
          >
            {detailOpen ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
            {detailOpen ? "收起决策详情" : "查看决策详情"}
          </button>
          {detailOpen ? (
            <div className="mt-2 grid gap-3 lg:grid-cols-3">
              <div className="space-y-2 border border-slate-200 rounded p-2 bg-white">
                <div className="text-[11px] font-semibold text-slate-700">Manager 决策</div>
                {manage?.boundedContractPreview ? (
                  <div className="border border-slate-200 bg-slate-50/60 p-2 rounded">
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
                    <p className="text-[11px] text-slate-700 whitespace-pre-wrap leading-relaxed">
                      {fullContractMap[manage.decisionHash]?.boundedContract || manage.boundedContractPreview}
                    </p>
                  </div>
                ) : null}
                {manage?.rationale ? (
                  <div className="text-[11px] text-slate-600">
                    <span className="font-medium text-slate-700">决策理由：</span>
                    {manage.rationale}
                  </div>
                ) : null}
                {manage?.commandCreatedAtEpochMillis ? (
                  <div className="text-[10px] text-slate-400">
                    决策命令创建时间：{new Date(manage.commandCreatedAtEpochMillis).toLocaleString("zh-CN", { hour12: false })}
                  </div>
                ) : null}
              </div>
              <div className="space-y-2 border border-slate-200 rounded p-2 bg-white">
                <div className="text-[11px] font-semibold text-slate-700">Coding 执行命令</div>
                {execute?.remediationKind ? (
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="text-slate-500">修复类型：</span>
                    <span className="font-mono text-slate-700">{execute.remediationKind}</span>
                  </div>
                ) : null}
                {execute?.commandId ? (
                  <div className="flex items-center justify-between text-[11px]">
                    <span className="text-slate-500">命令 ID：</span>
                    <span className="font-mono text-slate-600 truncate max-w-[160px]" title={execute.commandId}>
                      {execute.commandId}
                    </span>
                  </div>
                ) : null}
                {!execute?.remediationKind && !execute?.commandId ? (
                  <div className="text-[11px] text-slate-400">暂无额外命令细节</div>
                ) : null}
              </div>
              <div className="hidden lg:block" aria-hidden="true" />
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
