import type {
  CodingMeaResponse,
  CommandReference,
  DecisionReference,
  HostVerificationReference,
  RemediationReference,
  StageReference
} from "@/services/codingMeaService.ts";

export interface MeaManageColumn {
  decisionHash: string;
  roundNo: number;
  roundLabel: string; // "决策第 X 轮"
  route: string;
  routeLabel: string;
  targetRecordIds: string[];
  boundedContractPreview: string;
  boundedContractTruncated: boolean;
  rationale: string;
  commandCreatedAtEpochMillis: number | null;
  stateVersion: number;
}

export interface MeaExecuteColumn {
  stageRunId: string;
  attemptNo: number;
  attemptLabel: string; // "Coding Attempt Z"
  status: string;
  commandId?: string;
  remediationKind?: string;
  remediationRoundLabel?: string; // "修复第 Y 轮"
}

export interface MeaAuditColumn {
  hostVerify?: {
    runId: string;
    status: string;
    passed: boolean;
    docsOnly: boolean;
    summary: string;
  };
  qaStage?: {
    stageRunId: string;
    attemptNo: number;
    status: string;
    linkRole: "QA_AGENT";
  };
  auditConclusion?: {
    completedRecordsCount: number;
    pendingRecordsCount: number;
    summary: string;
  };
}

export interface MeaRoundView {
  roundKey: string;
  roundTitle: string;
  manage: MeaManageColumn | null;
  execute: MeaExecuteColumn | null;
  audit: MeaAuditColumn | null;
  isCurrent: boolean;
}

export interface CodingMeaView {
  available: boolean;
  unavailableReason: string | null;
  currentHeadline: string;
  currentRound: MeaRoundView | null;
  historyRounds: MeaRoundView[];
  isFirstCodingWithoutManager: boolean;
  managerDoneReviewNote: string | null;
  hasPartialHistory: boolean;
  pageHasMore: boolean;
}

export function buildCodingMeaView(
  response: CodingMeaResponse | null | undefined,
  selectedStageRunId: string
): CodingMeaView {
  if (!response || !response.available) {
    return {
      available: false,
      unavailableReason: response?.unavailableReason || "协作追踪暂不可用",
      currentHeadline: "",
      currentRound: null,
      historyRounds: [],
      isFirstCodingWithoutManager: false,
      managerDoneReviewNote: null,
      hasPartialHistory: false,
      pageHasMore: false
    };
  }

  const {
    codingStages = [],
    qaStages = [],
    commands = [],
    decisions = [],
    remediations = [],
    hostVerifications = [],
    head,
    page
  } = response;

  const currentCodingStage = codingStages.find((s) => s.stageRunId === selectedStageRunId)
    || codingStages[0];

  if (!currentCodingStage) {
    return {
      available: true,
      unavailableReason: "未找到当前选中的 Coding 执行记录",
      currentHeadline: "",
      currentRound: null,
      historyRounds: [],
      isFirstCodingWithoutManager: false,
      managerDoneReviewNote: null,
      hasPartialHistory: Boolean(page?.hasMore),
      pageHasMore: Boolean(page?.hasMore)
    };
  }

  // 关联当前 Coding stageRunId 相关的 command
  const codingCommand = commands.find((c) => (
    c.stageRunId === currentCodingStage.stageRunId
    || (c.role === "CODING_AGENT" && c.commandAttemptNo === currentCodingStage.attemptNo)
  ));

  // 查找关联的 remediation round
  const currentRemediation = remediations.find((r) => (
    r.targetCodingStageRunId === currentCodingStage.stageRunId
    || (codingCommand?.remediationRoundId && r.roundId === codingCommand.remediationRoundId)
  ));

  // 查找前置或同轮的 Manager decision
  let currentDecision: DecisionReference | undefined;
  const codingCmdSourceId = (codingCommand as { sourceCommandId?: string | null })?.sourceCommandId;
  if (codingCmdSourceId) {
    currentDecision = decisions.find((d) => d.sourceCommandId === codingCmdSourceId);
  }
  if (!currentDecision && currentRemediation?.firstCommandId) {
    currentDecision = decisions.find((d) => (
      d.sourceCommandId === currentRemediation.firstCommandId
      || d.managerCommandId === currentRemediation.firstCommandId
    ));
  }
  if (!currentDecision && decisions.length > 0) {
    // 寻找以当前 stage 为 targets 的最近 decision
    currentDecision = decisions.find((d) => (
      d.executorRoute === "CODING_AGENT"
      && currentRemediation?.roundId
    )) || (currentCodingStage.attemptNo > 1 ? decisions[0] : undefined);
  }

  // 查找关联的 Host Verification
  const currentHv = hostVerifications.find((hv) => hv.codingStageRunId === currentCodingStage.stageRunId);

  // 查找关联的 QA stage
  let currentQaStage: StageReference | undefined;
  if (currentRemediation?.targetQaStageRunId) {
    currentQaStage = qaStages.find((q) => q.stageRunId === currentRemediation.targetQaStageRunId);
  }
  if (!currentQaStage && currentCodingStage.attemptNo) {
    // 依据同轮次 attemptNo 关联
    currentQaStage = qaStages.find((q) => q.attemptNo === currentCodingStage.attemptNo);
  }

  const isFirstCodingWithoutManager = currentCodingStage.attemptNo === 1 && !currentDecision;

  // 构造三栏
  let manageColumn: MeaManageColumn | null = null;
  if (currentDecision) {
    manageColumn = {
      decisionHash: currentDecision.decisionHash,
      roundNo: currentDecision.roundNo,
      roundLabel: `决策第 ${currentDecision.roundNo} 轮`,
      route: currentDecision.route,
      routeLabel: formatRouteLabel(currentDecision.route),
      targetRecordIds: currentDecision.targetRecordIds || [],
      boundedContractPreview: currentDecision.boundedContractPreview || "",
      boundedContractTruncated: Boolean(currentDecision.boundedContractTruncated),
      rationale: currentDecision.rationale || "",
      commandCreatedAtEpochMillis: currentDecision.commandCreatedAtEpochMillis,
      stateVersion: currentDecision.stateVersion
    };
  }

  const remediationRoundLabel = currentRemediation
    ? `修复第 ${currentRemediation.remediationNo} 轮`
    : (codingCommand?.remediationNo ? `修复第 ${codingCommand.remediationNo} 轮` : undefined);

  const executeColumn: MeaExecuteColumn = {
    stageRunId: currentCodingStage.stageRunId,
    attemptNo: currentCodingStage.attemptNo,
    attemptLabel: `Coding Attempt ${currentCodingStage.attemptNo}`,
    status: currentCodingStage.status,
    commandId: codingCommand?.commandId,
    remediationKind: currentRemediation?.kind || codingCommand?.remediationKind || undefined,
    remediationRoundLabel
  };

  let auditColumn: MeaAuditColumn | null = null;
  if (currentHv || currentQaStage || head) {
    const completedCount = (head?.records || []).filter((r) => r.status === "COMPLETED").length;
    const pendingCount = (head?.records || []).filter((r) => r.blocking && r.status === "PENDING").length;

    auditColumn = {
      hostVerify: currentHv ? {
        runId: currentHv.runId,
        status: currentHv.status,
        passed: currentHv.status === "SUCCEEDED",
        docsOnly: Boolean(currentHv.docsOnly),
        summary: currentHv.status === "SUCCEEDED"
          ? (currentHv.docsOnly ? "文档模式跳过" : "构建与静态检查通过")
          : `宿主验证未通过 (${currentHv.failureCategory || currentHv.status})`
      } : undefined,
      qaStage: currentQaStage ? {
        stageRunId: currentQaStage.stageRunId,
        attemptNo: currentQaStage.attemptNo,
        status: currentQaStage.status,
        linkRole: "QA_AGENT"
      } : undefined,
      auditConclusion: head ? {
        completedRecordsCount: completedCount,
        pendingRecordsCount: pendingCount,
        summary: pendingCount === 0 ? "全部验收项已审计通过" : `尚有 ${pendingCount} 项阻断验收待闭环`
      } : undefined
    };
  }

  const currentRound: MeaRoundView = {
    roundKey: `round-${currentCodingStage.stageRunId}`,
    roundTitle: remediationRoundLabel
      ? `${remediationRoundLabel} (Coding Attempt ${currentCodingStage.attemptNo})`
      : `首轮执行 (Coding Attempt ${currentCodingStage.attemptNo})`,
    manage: manageColumn,
    execute: executeColumn,
    audit: auditColumn,
    isCurrent: true
  };

  // 生成当前轮顶部简要结论文案
  let currentHeadline = "";
  let managerDoneReviewNote: string | null = null;

  if (isFirstCodingWithoutManager) {
    currentHeadline = "首次执行尚无前置 Manager 决策";
  } else if (currentDecision?.route === "DONE") {
    currentHeadline = "验收已闭环，交由交付复核";
    managerDoneReviewNote = "交由交付复核";
  } else if (currentDecision?.targetRecordIds && currentDecision.targetRecordIds.length > 0) {
    const targets = currentDecision.targetRecordIds.join("、");
    if (currentQaStage?.status === "RUNNING") {
      currentHeadline = `修复 ${targets}，等待 QA 审计`;
    } else if (currentCodingStage.status === "RUNNING") {
      currentHeadline = `正在修复 ${targets}`;
    } else {
      currentHeadline = `修复目标：${targets}`;
    }
  } else if (manageColumn) {
    currentHeadline = `${manageColumn.roundLabel}：${manageColumn.routeLabel}`;
  } else {
    currentHeadline = `Coding Attempt ${currentCodingStage.attemptNo}`;
  }

  // 历史轮次（除当前 Coding Attempt 之外的其它 Coding 轮次）
  const historyRounds: MeaRoundView[] = codingStages
    .filter((s) => s.stageRunId !== currentCodingStage.stageRunId)
    .sort((a, b) => b.attemptNo - a.attemptNo)
    .map((historicalCoding) => {
      const hCommand = commands.find((c) => c.stageRunId === historicalCoding.stageRunId);
      const hRemediation = remediations.find((r) => r.targetCodingStageRunId === historicalCoding.stageRunId);
      const hDecision = decisions.find((d) => d.roundNo === historicalCoding.attemptNo);
      const hHv = hostVerifications.find((hv) => hv.codingStageRunId === historicalCoding.stageRunId);
      const hQa = qaStages.find((q) => q.attemptNo === historicalCoding.attemptNo);

      const hRoundLabel = hRemediation
        ? `修复第 ${hRemediation.remediationNo} 轮`
        : `Coding Attempt ${historicalCoding.attemptNo}`;

      return {
        roundKey: `round-${historicalCoding.stageRunId}`,
        roundTitle: `${hRoundLabel} (历史轮次)`,
        manage: hDecision ? {
          decisionHash: hDecision.decisionHash,
          roundNo: hDecision.roundNo,
          roundLabel: `决策第 ${hDecision.roundNo} 轮`,
          route: hDecision.route,
          routeLabel: formatRouteLabel(hDecision.route),
          targetRecordIds: hDecision.targetRecordIds || [],
          boundedContractPreview: hDecision.boundedContractPreview || "",
          boundedContractTruncated: Boolean(hDecision.boundedContractTruncated),
          rationale: hDecision.rationale || "",
          commandCreatedAtEpochMillis: hDecision.commandCreatedAtEpochMillis,
          stateVersion: hDecision.stateVersion
        } : null,
        execute: {
          stageRunId: historicalCoding.stageRunId,
          attemptNo: historicalCoding.attemptNo,
          attemptLabel: `Coding Attempt ${historicalCoding.attemptNo}`,
          status: historicalCoding.status,
          commandId: hCommand?.commandId,
          remediationKind: hRemediation?.kind || hCommand?.remediationKind || undefined,
          remediationRoundLabel: hRoundLabel
        },
        audit: {
          hostVerify: hHv ? {
            runId: hHv.runId,
            status: hHv.status,
            passed: hHv.status === "SUCCEEDED",
            docsOnly: Boolean(hHv.docsOnly),
            summary: hHv.status === "SUCCEEDED" ? "构建通过" : "未通过"
          } : undefined,
          qaStage: hQa ? {
            stageRunId: hQa.stageRunId,
            attemptNo: hQa.attemptNo,
            status: hQa.status,
            linkRole: "QA_AGENT"
          } : undefined
        },
        isCurrent: false
      };
    });

  return {
    available: true,
    unavailableReason: null,
    currentHeadline,
    currentRound,
    historyRounds,
    isFirstCodingWithoutManager,
    managerDoneReviewNote,
    hasPartialHistory: Boolean(page?.hasMore),
    pageHasMore: Boolean(page?.hasMore)
  };
}

function formatRouteLabel(route: string): string {
  switch (route.toUpperCase()) {
    case "EXECUTE":
      return "有界修复";
    case "DONE":
      return "交由交付复核";
    case "BLOCKED":
      return "已阻断";
    case "ASK":
      return "等待用户输入";
    case "REPLAN":
      return "重新规划";
    default:
      return route;
  }
}
